/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.master

import java.io.FileNotFoundException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

import akka.actor._
import akka.pattern.ask
import akka.remote.{DisassociatedEvent, RemotingLifecycleEvent}
import akka.serialization.Serialization
import akka.serialization.SerializationExtension
import org.apache.hadoop.fs.Path

import org.apache.spark.{Logging, SecurityManager, SparkConf, SparkException}
import org.apache.spark.deploy.{ApplicationDescription, DriverDescription,
  ExecutorState, SparkHadoopUtil}
import org.apache.spark.deploy.DeployMessages._
import org.apache.spark.deploy.history.HistoryServer
import org.apache.spark.deploy.master.DriverState.DriverState
import org.apache.spark.deploy.master.MasterMessages._
import org.apache.spark.deploy.master.ui.MasterWebUI
import org.apache.spark.deploy.rest.StandaloneRestServer
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.scheduler.{EventLoggingListener, ReplayListenerBus}
import org.apache.spark.ui.SparkUI
import org.apache.spark.util.{ActorLogReceive, AkkaUtils, SignalLogger, Utils}

private[spark] class Master(
    host: String,
    port: Int,
    webUiPort: Int,
    val securityMgr: SecurityManager,
    val conf: SparkConf)
  extends Actor with ActorLogReceive with Logging with LeaderElectable {//master其实就是akka的一个线程

  import context.dispatcher   // to use Akka's scheduler.schedule()

  val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)

  def createDateFormat = new SimpleDateFormat("yyyyMMddHHmmss")  // For application IDs
  val WORKER_TIMEOUT = conf.getLong("spark.worker.timeout", 60) * 1000
  val RETAINED_APPLICATIONS = conf.getInt("spark.deploy.retainedApplications", 200)
  val RETAINED_DRIVERS = conf.getInt("spark.deploy.retainedDrivers", 200)
  val REAPER_ITERATIONS = conf.getInt("spark.dead.worker.persistence", 15)
  val RECOVERY_MODE = conf.get("spark.deploy.recoveryMode", "NONE")

  val workers = new HashSet[WorkerInfo]
  val idToWorker = new HashMap[String, WorkerInfo]
  val addressToWorker = new HashMap[Address, WorkerInfo]

  val apps = new HashSet[ApplicationInfo]
  val idToApp = new HashMap[String, ApplicationInfo]
  val actorToApp = new HashMap[ActorRef, ApplicationInfo]
  val addressToApp = new HashMap[Address, ApplicationInfo]
  val waitingApps = new ArrayBuffer[ApplicationInfo]
  val completedApps = new ArrayBuffer[ApplicationInfo]
  var nextAppNumber = 0
  val appIdToUI = new HashMap[String, SparkUI]

  val drivers = new HashSet[DriverInfo]
  val completedDrivers = new ArrayBuffer[DriverInfo]
  val waitingDrivers = new ArrayBuffer[DriverInfo] // Drivers currently spooled for scheduling
  var nextDriverNumber = 0

  Utils.checkHost(host, "Expected hostname")

  val masterMetricsSystem = MetricsSystem.createMetricsSystem("master", conf, securityMgr)
  val applicationMetricsSystem = MetricsSystem.createMetricsSystem("applications", conf,
    securityMgr)
  val masterSource = new MasterSource(this)

  val webUi = new MasterWebUI(this, webUiPort)

  val masterPublicAddress = {
    val envVar = conf.getenv("SPARK_PUBLIC_DNS")
    if (envVar != null) envVar else host
  }

  val masterUrl = "spark://" + host + ":" + port
  var masterWebUiUrl: String = _

  var state = RecoveryState.STANDBY

  var persistenceEngine: PersistenceEngine = _

  var leaderElectionAgent: LeaderElectionAgent = _

  private var recoveryCompletionTask: Cancellable = _

  // As a temporary workaround before better ways of configuring memory, we allow users to set
  // a flag that will perform round-robin scheduling across the nodes (spreading out each app
  // among all the nodes) instead of trying to consolidate each app onto a small # of nodes.
  val spreadOutApps = conf.getBoolean("spark.deploy.spreadOut", true)

  // Default maxCores for applications that don't specify it (i.e. pass Int.MaxValue)
  val defaultCores = conf.getInt("spark.deploy.defaultCores", Int.MaxValue)
  if (defaultCores < 1) {
    throw new SparkException("spark.deploy.defaultCores must be positive")
  }

  // Alternative application submission gateway that is stable across Spark versions
  private val restServerEnabled = conf.getBoolean("spark.master.rest.enabled", true)
  private val restServer =
    if (restServerEnabled) {
      val port = conf.getInt("spark.master.rest.port", 6066)
      Some(new StandaloneRestServer(host, port, self, masterUrl, conf))
    } else {
      None
    }
  private val restServerBoundPort = restServer.map(_.start())

  override def preStart() {
    logInfo("Starting Spark master at " + masterUrl)
    logInfo(s"Running Spark version ${org.apache.spark.SPARK_VERSION}")
    // Listen for remote client disconnection events, since they don't go through Akka's watch()
    context.system.eventStream.subscribe(self, classOf[RemotingLifecycleEvent])
    webUi.bind()
    masterWebUiUrl = "http://" + masterPublicAddress + ":" + webUi.boundPort
    context.system.scheduler.schedule(0 millis, WORKER_TIMEOUT millis, self, CheckForWorkerTimeOut)

    masterMetricsSystem.registerSource(masterSource)
    masterMetricsSystem.start()
    applicationMetricsSystem.start()
    // Attach the master and app metrics servlet handler to the web ui after the metrics systems are
    // started.
    masterMetricsSystem.getServletHandlers.foreach(webUi.attachHandler)
    applicationMetricsSystem.getServletHandlers.foreach(webUi.attachHandler)

    val (persistenceEngine_, leaderElectionAgent_) = RECOVERY_MODE match {
      case "ZOOKEEPER" =>
        logInfo("Persisting recovery state to ZooKeeper")
        val zkFactory =
          new ZooKeeperRecoveryModeFactory(conf, SerializationExtension(context.system))
        (zkFactory.createPersistenceEngine(), zkFactory.createLeaderElectionAgent(this))
      case "FILESYSTEM" =>
        val fsFactory =
          new FileSystemRecoveryModeFactory(conf, SerializationExtension(context.system))
        (fsFactory.createPersistenceEngine(), fsFactory.createLeaderElectionAgent(this))
      case "CUSTOM" =>
        val clazz = Class.forName(conf.get("spark.deploy.recoveryMode.factory"))
        val factory = clazz.getConstructor(conf.getClass, Serialization.getClass)
          .newInstance(conf, SerializationExtension(context.system))
          .asInstanceOf[StandaloneRecoveryModeFactory]
        (factory.createPersistenceEngine(), factory.createLeaderElectionAgent(this))
      case _ =>
        (new BlackHolePersistenceEngine(), new MonarchyLeaderAgent(this))
    }
    persistenceEngine = persistenceEngine_
    leaderElectionAgent = leaderElectionAgent_
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    super.preRestart(reason, message) // calls postStop()!
    logError("Master actor restarted due to exception", reason)
  }

  override def postStop() {
    masterMetricsSystem.report()
    applicationMetricsSystem.report()
    // prevent the CompleteRecovery message sending to restarted master
    if (recoveryCompletionTask != null) {
      recoveryCompletionTask.cancel()
    }
    webUi.stop()
    restServer.foreach(_.stop())
    masterMetricsSystem.stop()
    applicationMetricsSystem.stop()
    persistenceEngine.close()
    leaderElectionAgent.stop()
  }

  override def electedLeader() {
    self ! ElectedLeader
  }

  override def revokedLeadership() {
    self ! RevokedLeadership
  }

  override def receiveWithLogging = {
    case ElectedLeader => {
      val (storedApps, storedDrivers, storedWorkers) = persistenceEngine.readPersistedData()
      state = if (storedApps.isEmpty && storedDrivers.isEmpty && storedWorkers.isEmpty) {
        RecoveryState.ALIVE
      } else {
        RecoveryState.RECOVERING
      }
      logInfo("I have been elected leader! New state: " + state)
      if (state == RecoveryState.RECOVERING) {
        beginRecovery(storedApps, storedDrivers, storedWorkers)
        recoveryCompletionTask = context.system.scheduler.scheduleOnce(WORKER_TIMEOUT millis, self,
          CompleteRecovery)
      }
    }

    case CompleteRecovery => completeRecovery()

    case RevokedLeadership => {
      logError("Leadership has been revoked -- master shutting down.")
      System.exit(0)
    }

    case RegisterWorker(id, workerHost, workerPort, cores, memory, workerUiPort, publicAddress) =>
    {
      logInfo("Registering worker %s:%d with %d cores, %s RAM".format(
        workerHost, workerPort, cores, Utils.megabytesToString(memory)))
      if (state == RecoveryState.STANDBY) {
        // ignore, don't send response
      } else if (idToWorker.contains(id)) {
        sender ! RegisterWorkerFailed("Duplicate worker ID")
      } else {
        val worker = new WorkerInfo(id, workerHost, workerPort, cores, memory,
          sender, workerUiPort, publicAddress)
        if (registerWorker(worker)) {
          persistenceEngine.addWorker(worker)
          sender ! RegisteredWorker(masterUrl, masterWebUiUrl)
          schedule()
        } else {
          val workerAddress = worker.actor.path.address
          logWarning("Worker registration failed. Attempted to re-register worker at same " +
            "address: " + workerAddress)
          sender ! RegisterWorkerFailed("Attempted to re-register worker at same address: "
            + workerAddress)
        }
      }
    }

    case RequestSubmitDriver(description) => {
      if (state != RecoveryState.ALIVE) {
        val msg = s"Can only accept driver submissions in ALIVE state. Current state: $state."
        sender ! SubmitDriverResponse(false, None, msg)
      } else {
        logInfo("Driver submitted " + description.command.mainClass)
        val driver = createDriver(description)
        persistenceEngine.addDriver(driver)
        waitingDrivers += driver
        drivers.add(driver)
        schedule()

        // TODO: It might be good to instead have the submission client poll the master to determine
        //       the current status of the driver. For now it's simply "fire and forget".

        sender ! SubmitDriverResponse(true, Some(driver.id),
          s"Driver successfully submitted as ${driver.id}")
      }
    }

    case RequestKillDriver(driverId) => {
      if (state != RecoveryState.ALIVE) {
        val msg = s"Can only kill drivers in ALIVE state. Current state: $state."
        sender ! KillDriverResponse(driverId, success = false, msg)
      } else {
        logInfo("Asked to kill driver " + driverId)
        val driver = drivers.find(_.id == driverId)
        driver match {
          case Some(d) =>
            if (waitingDrivers.contains(d)) {
              waitingDrivers -= d
              self ! DriverStateChanged(driverId, DriverState.KILLED, None)
            } else {
              // We just notify the worker to kill the driver here. The final bookkeeping occurs
              // on the return path when the worker submits a state change back to the master
              // to notify it that the driver was successfully killed.
              d.worker.foreach { w =>
                w.actor ! KillDriver(driverId)
              }
            }
            // TODO: It would be nice for this to be a synchronous response
            val msg = s"Kill request for $driverId submitted"
            logInfo(msg)
            sender ! KillDriverResponse(driverId, success = true, msg)
          case None =>
            val msg = s"Driver $driverId has already finished or does not exist"
            logWarning(msg)
            sender ! KillDriverResponse(driverId, success = false, msg)
        }
      }
    }

    case RequestDriverStatus(driverId) => {
      (drivers ++ completedDrivers).find(_.id == driverId) match {
        case Some(driver) =>
          sender ! DriverStatusResponse(found = true, Some(driver.state),
            driver.worker.map(_.id), driver.worker.map(_.hostPort), driver.exception)
        case None =>
          sender ! DriverStatusResponse(found = false, None, None, None, None)
      }
    }
    /**
     * master的注册机制，同时处理executor和driver状态改变时候的机制
     * 处理application注册的请求
     */
    case RegisterApplication(description) => {
      /**
       * 如果master的状态是standby，也就是当前这个master，是standby master，不是alive master
       * 那么application来注册就什么都不会干
       */
      if (state == RecoveryState.STANDBY) {
        // ignore, don't send response
      } else {
        logInfo("Registering app " + description.name)
        //用applicationdescription信息，创建applicationinfo之后
        val app = createApplication(description, sender)
        registerApplication(app)//进行注册，将application加入等待调度的队列-waitingAPPs
        logInfo("Registered app " + description.name + " with ID " + app.id)
        //使用持久化引擎，将applicationInfo进行持久化
        persistenceEngine.addApplication(app)
        /**
         * actor通信的时候，会将另一个actor带过来，用sender命名，这样就会知道，消息是谁发过来的，
         * 这样就可以反向 向sparkDeploySchedulerBackend的APPclient的clientactor，发送消息，
         * 也就是registeredapplication
         */
        sender ! RegisteredApplication(app.id, masterUrl)
        schedule()
      }
    }
    //executor状态改变机制
    case ExecutorStateChanged(appId, execId, state, message, exitStatus) => {
      //找到executor对应的APP，然后再反过来通过APP内部的exeutor缓存获取executor信息
      val execOption = idToApp.get(appId).flatMap(app => app.executors.get(execId))
      execOption match {
        //如果找到了状态改变的executor
        case Some(exec) => {
          //设置executor的当前状态
          val appInfo = idToApp(appId)
          exec.state = state
          if (state == ExecutorState.RUNNING) { appInfo.resetRetryCount() }
          //向driver同步发送ExecutorUpdated消息
          exec.application.driver ! ExecutorUpdated(execId, state, message, exitStatus)
          //如果executor完成了
          if (ExecutorState.isFinished(state)) {
            // Remove this executor from the worker and app
            logInfo(s"Removing executor ${exec.fullId} because it is $state")
            
            //从app的缓存中移除executor
            appInfo.removeExecutor(exec)
            
            //从运行executor的worker的缓存中移除executor
            exec.worker.removeExecutor(exec)
            //如果executor的退出状态是非正常的
            val normalExit = exitStatus == Some(0)
            // Only retry certain number of times so we don't go into an infinite loop.
            if (!normalExit) {
              
              //判断application当前的重试次数是否达到了最大值10
              if (appInfo.incrementRetryCount() < ApplicationState.MAX_NUM_RETRY) {
                //没有则重新调度
                schedule()
              } else {
                //否则就进行removeApplication操作，application的执行依托于executor，如果executor反复调度都是失败，那么就认为application也就失败了，所以执行这个操作
                val execs = appInfo.executors.values
                if (!execs.exists(_.state == ExecutorState.RUNNING)) {
                  logError(s"Application ${appInfo.desc.name} with ID ${appInfo.id} failed " +
                    s"${appInfo.retryCount} times; removing it")
                  removeApplication(appInfo, ApplicationState.FAILED)//清除内存缓存
                }
              }
            }
          }
        }
        case None =>
          logWarning(s"Got status update for unknown executor $appId/$execId")
      }
    }
    
    /**
     * driver状态改变机制
     */
    case DriverStateChanged(driverId, state, exception) => {
      state match {
        //如果driver的状态是错误，完成、被杀掉，失败，就会被移除
        case DriverState.ERROR | DriverState.FINISHED | DriverState.KILLED | DriverState.FAILED =>
          removeDriver(driverId, state, exception)
        case _ =>
          throw new Exception(s"Received unexpected state update for driver $driverId: $state")
      }
    }

    case Heartbeat(workerId) => {
      idToWorker.get(workerId) match {
        case Some(workerInfo) =>
          workerInfo.lastHeartbeat = System.currentTimeMillis()
        case None =>
          if (workers.map(_.id).contains(workerId)) {
            logWarning(s"Got heartbeat from unregistered worker $workerId." +
              " Asking it to re-register.")
            sender ! ReconnectWorker(masterUrl)
          } else {
            logWarning(s"Got heartbeat from unregistered worker $workerId." +
              " This worker was never registered, so ignoring the heartbeat.")
          }
      }
    }

    case MasterChangeAcknowledged(appId) => {
      idToApp.get(appId) match {
        case Some(app) =>
          logInfo("Application has been re-registered: " + appId)
          app.state = ApplicationState.WAITING
        case None =>
          logWarning("Master change ack from unknown app: " + appId)
      }

      if (canCompleteRecovery) { completeRecovery() }
    }

    case WorkerSchedulerStateResponse(workerId, executors, driverIds) => {
      idToWorker.get(workerId) match {
        case Some(worker) =>
          logInfo("Worker has been re-registered: " + workerId)
          worker.state = WorkerState.ALIVE

          val validExecutors = executors.filter(exec => idToApp.get(exec.appId).isDefined)
          for (exec <- validExecutors) {
            val app = idToApp.get(exec.appId).get
            val execInfo = app.addExecutor(worker, exec.cores, Some(exec.execId))
            worker.addExecutor(execInfo)
            execInfo.copyState(exec)
          }

          for (driverId <- driverIds) {
            drivers.find(_.id == driverId).foreach { driver =>
              driver.worker = Some(worker)
              driver.state = DriverState.RUNNING
              worker.drivers(driverId) = driver
            }
          }
        case None =>
          logWarning("Scheduler state from unknown worker: " + workerId)
      }

      if (canCompleteRecovery) { completeRecovery() }
    }

    case DisassociatedEvent(_, address, _) => {
      // The disconnected client could've been either a worker or an app; remove whichever it was
      logInfo(s"$address got disassociated, removing it.")
      addressToWorker.get(address).foreach(removeWorker)
      addressToApp.get(address).foreach(finishApplication)
      if (state == RecoveryState.RECOVERING && canCompleteRecovery) { completeRecovery() }
    }

    case RequestMasterState => {
      sender ! MasterStateResponse(
        host, port, restServerBoundPort,
        workers.toArray, apps.toArray, completedApps.toArray,
        drivers.toArray, completedDrivers.toArray, state)
    }

    case CheckForWorkerTimeOut => {
      timeOutDeadWorkers()
    }

    case BoundPortsRequest => {
      sender ! BoundPortsResponse(port, webUi.boundPort, restServerBoundPort)
    }
  }

  def canCompleteRecovery =
    workers.count(_.state == WorkerState.UNKNOWN) == 0 &&
      apps.count(_.state == ApplicationState.UNKNOWN) == 0

  def beginRecovery(storedApps: Seq[ApplicationInfo], storedDrivers: Seq[DriverInfo],
      storedWorkers: Seq[WorkerInfo]) {
    for (app <- storedApps) {
      logInfo("Trying to recover app: " + app.id)
      try {
        registerApplication(app)
        app.state = ApplicationState.UNKNOWN
        app.driver ! MasterChanged(masterUrl, masterWebUiUrl)
      } catch {
        case e: Exception => logInfo("App " + app.id + " had exception on reconnect")
      }
    }

    for (driver <- storedDrivers) {
      // Here we just read in the list of drivers. Any drivers associated with now-lost workers
      // will be re-launched when we detect that the worker is missing.
      drivers += driver
    }

    for (worker <- storedWorkers) {
      logInfo("Trying to recover worker: " + worker.id)
      try {
        registerWorker(worker)
        worker.state = WorkerState.UNKNOWN
        worker.actor ! MasterChanged(masterUrl, masterWebUiUrl)
      } catch {
        case e: Exception => logInfo("Worker " + worker.id + " had exception on reconnect")
      }
    }
  }
  
  /**
   * 完成master的主备切换，从字面上的意思来看，其实就是完成master的恢复
   */
  def completeRecovery() {
    // Ensure "only-once" recovery semantics using a short synchronization period.
    synchronized {//首先查看状态
      if (state != RecoveryState.RECOVERING) { return }
      state = RecoveryState.COMPLETING_RECOVERY
    }
    
    /**
     * 将application和worker，过滤出来目前状态还是UNKNOWN的
     * 然后遍历，分别调用removeWorker方法和finishApplication方法，对可能已经出故障的，或者已经死掉的application
     * 和worker进行清除
     * 总结一下清理的机制,总共三点：第一：从内存缓存结构中移除，第二：从相关的组件的内存缓存中移除，第三：从持久化存储中移除
     */
    // Kill off any workers and apps that didn't respond to us.
    workers.filter(_.state == WorkerState.UNKNOWN).foreach(removeWorker)
    //将worker移除掉之后，他的driver也就会被过滤
    apps.filter(_.state == ApplicationState.UNKNOWN).foreach(finishApplication)

    // Reschedule drivers which were not claimed by any workers
    drivers.filter(_.worker.isEmpty).foreach { d =>
      logWarning(s"Driver ${d.id} was not found after master recovery")
      if (d.desc.supervise) {
        logWarning(s"Re-launching ${d.id}")
        relaunchDriver(d)
      } else {
        removeDriver(d.id, DriverState.ERROR, None)
        logWarning(s"Did not re-launch ${d.id} because it was not supervised")
      }
    }

    state = RecoveryState.ALIVE
    schedule()
    logInfo("Recovery complete - resuming operations!")
  }

  /**
   * Can an app use the given worker? True if the worker has enough memory and we haven't already
   * launched an executor for the app on it (right now the standalone backend doesn't like having
   * two executors on the same worker).
   */
  def canUse(app: ApplicationInfo, worker: WorkerInfo): Boolean = {
    worker.memoryFree >= app.desc.memoryPerSlave && !worker.hasExecutor(app)
  }

  /**
   * Schedule the currently available resources among waiting apps. This method will be called
   * every time a new app joins or resource availability changes.
   * 70行的代码，完成了
   */
  private def schedule() {
    //首先判断master状态，不是alive的话，直接返回
    if (state != RecoveryState.ALIVE) { return }

    // First schedule drivers, they take strict precedence over applications
    // Randomization helps balance drivers
    // Random.shuffle的原理就是对传入的集合进行随机打乱，取出了workers中的所有之前注册上来的worker，进行过滤，必须是状态为alive的worker
    //对状态为alive的worker，调用 Random.shuffle方法，进行随机打乱
    val shuffledAliveWorkers = Random.shuffle(workers.toSeq.filter(_.state == WorkerState.ALIVE))
    val numWorkersAlive = shuffledAliveWorkers.size
    var curPos = 0
    
    //首先调度driver，为什么调度driver，什么情况下会注册driver，并且会导致driver被调度
    //其实，只有用yarn-cluster模式提交的时候， 才会注册driver，因为standalone和yarn-client模式，都会在本地直接启动driver，而不会注册driver，就更不可能让master调度driver了
    /**
     * driver的调度机制：遍历waitingdrivers，array buffer
     */
    for (driver <- waitingDrivers.toList) { // iterate over a copy of waitingDrivers array buffer
      // We assign workers to each waiting driver in a round-robin fashion. For each driver, we
      // start from the last worker that was assigned a driver, and continue onwards until we have
      // explored all alive workers.
      var launched = false
      var numWorkersVisited = 0
     
      //while的条件，numWorkersVisited小于numWorkersAlive的意思就是，只要还有或者的worker没有遍历到，那么就会继续遍历
      //而且意思是当前这个driver还没有被启动，也就是launched为false
      while (numWorkersVisited < numWorkersAlive && !launched) {
        val worker = shuffledAliveWorkers(curPos)
        numWorkersVisited += 1
        
        //如果当前这个worker的空闲内存量大于等于driver需要的内存，并且worker的空闲的CPU数量，大于等于driver需要的数量，这个时候就会启动driver
        if (worker.memoryFree >= driver.desc.mem && worker.coresFree >= driver.desc.cores) {
          launchDriver(worker, driver)//启动driver
          waitingDrivers -= driver//从缓存队列中移除，后面就不会调度这个启动过了的driver了
          launched = true//将launch设置为true
        }
        curPos = (curPos + 1) % numWorkersAlive//将指针指向下一个worker
      }
    }
    //上面代码跳出循环之后，说明当前这个driver已经在某个worker上启动了，就会去循环下一个driver，遍历所有的worker，如果某个worker把这个driver启动了，那么就搞定

    // Right now this is a very simple FIFO scheduler. We keep trying to fit in the first app
    // in the queue, then the second app, etc.
    /**
     * application的调度机制，重中之重
     * 首先呢，application的调度机制有两种，spreadoutapps和非spreadoutapps
     */
    if (spreadOutApps) {
      // Try to spread out each app among all the nodes, until it has all its cores
      
      //首先遍历waitingAPPs中的applicationinfo，并且过滤出application还有需要调度的core的application
      for (app <- waitingApps if app.coresLeft > 0) {
        
        //从workers中过滤出状态为alive的，再次过滤可以被application使用的worker，然后按照剩余CPU数量倒序排序
        //可以被application使用的worker，也就是这个worker之前没有启动过executor，同时剩余内存数量知道要达到application设置的一个executor的内存数量
        val usableWorkers = workers.toArray.filter(_.state == WorkerState.ALIVE)
          .filter(canUse(app, _)).sortBy(_.coresFree).reverse
        val numUsable = usableWorkers.length//worker的数量
        
        //创建一个空数组，存储了要分配给每个worker的CPU数量
        val assigned = new Array[Int](numUsable) // Number of cores to give on each node
        //获取到底要分配多少CPU，取APP剩余要分配的CPU的数量和worker总共可用CPU数量的最小值
        var toAssign = math.min(app.coresLeft, usableWorkers.map(_.coresFree).sum)
        
        //通过这种算法，其实会将每个application，要启动的executor，都平均分布到各个worker上去
        //比如有20个CPUcore要分配，实际会循环两遍worker，每次循环，给每个worker分配1个core，最后每个worker分配了2个core
        
        //while条件，只要要分配的CPU，还没有分配完，就继续循环
        var pos = 0
        while (toAssign > 0) {
          
          /**
           * 每一个worker，如果空闲的CPU数量大于已经分配出去的CPU数量
           * 也就是说，worker还有可分配的CPU
           */
          if (usableWorkers(pos).coresFree - assigned(pos) > 0) {
            //将总共要分配的CPU刷领-1，因为这里已经决定在这个worker上分配一个CPU了
            toAssign -= 1
            //给这个worker分配的CPU数量+1
            assigned(pos) += 1
          }
          //指针移动到下一个worker
          pos = (pos + 1) % numUsable
        }
        
        //给每个worker分配完application要求的CPU core之后
        // Now that we've decided how many cores to give on each node, let's actually give them
        for (pos <- 0 until numUsable) {
          //只要判断之前给这个worker分配到了core
          if (assigned(pos) > 0) {
            //首先在application内部缓存结构中，添加executor
            //并且创建executordesc对象，其中封装了给这个executor分配多少个CPU core
            //这里至少是spark 1.3.0这个版本的executor启动的内部机制
            //在spark-submit脚本中可以之指定要多少个executor，每个executor多少个CPU，多少内存
            //基于这个机制，实际上，最后，executor的实际数量，以及每个executor的CPU，可能与配置是不一样的
            //因为这个是基于总的CPU来分配的，就是说，比如要求3个executor，每个要求3个CPU。
            //假设现在有9个worker，每个有1个CPU，那么根据这个分配算法，会给每个worker分配一个core，然后给每一个worker启动一个executor
            //那么最后会启动9个executor，每个executor都有1个CPU core
            val exec = app.addExecutor(usableWorkers(pos), assigned(pos))//在内部创建了一个executordescription这样一个executor的描述信息
        		//那么就在这个worker上启动executor
            launchExecutor(usableWorkers(pos), exec)
            //将application的状态设置为running
            app.state = ApplicationState.RUNNING
          }
        }
      }
    } else {
      
      /**
       * 非spreadoutAPPs调度算法
       * 这种算法和spreadoutAPPs算法正好相反
       * 每个application，都尽可能分配到尽量少的worker上去，比如总共有10个worker，每个有10个core
       * APP总共要分配20个core，那么其实，只会分配到两个worker上去，每个worker都占满10个core
       * 那么其余的APP，就只能分配到下一个worker
       * 比如说：application在spark-submit中配置的是10个executor，每个要2个core，那么总共就是20个core，
       * 但是在这种算法写，其实总共只会启动2个executor，每个有10个core
       * spark后面的源码会尽量满足我们的配置
       * 
       * 将每个application尽可能少的分配到worker上去，
       * 首先，遍历worker，并且是状态为alive，还有开给你先的CPU的worker
       */
      
      // Pack each app into as few nodes as possible until we've assigned all its cores
      for (worker <- workers if worker.coresFree > 0 && worker.state == WorkerState.ALIVE) {
        
        //遍历application，并且是还有需要分配的core的application
        for (app <- waitingApps if app.coresLeft > 0) {
          //判断，如果当前这个worker可以被application使用功能
          if (canUse(app, worker)) {
            //取worker剩余CPU数量，与APP要分配的CPU数量的最小值
            val coresToUse = math.min(worker.coresFree, app.coresLeft)
            //如果worker剩余CPU为0，那么当然就不分配了
            if (coresToUse > 0) {
              //给APP添加一个executor
              val exec = app.addExecutor(worker, coresToUse)
              //在worker上启动executor
              launchExecutor(worker, exec)
              //将application状态设置为running
              app.state = ApplicationState.RUNNING
            }
          }
        }
      }
    }
  }

  def launchExecutor(worker: WorkerInfo, exec: ExecutorDesc) {
    logInfo("Launching executor " + exec.fullId + " on worker " + worker.id)
    //将exector加入worker内部的缓存
    worker.addExecutor(exec)
    //向worker的actor发送LaunchExecutor消息
    worker.actor ! LaunchExecutor(masterUrl,
      exec.application.id, exec.id, exec.application.desc, exec.cores, exec.memory)
      //executor对应的application的driver发送ExecutorAdded消息
    exec.application.driver ! ExecutorAdded(
      exec.id, worker.id, worker.hostPort, exec.cores, exec.memory)
  }

  def registerWorker(worker: WorkerInfo): Boolean = {
    // There may be one or more refs to dead workers on this same node (w/ different ID's),
    // remove them.
    workers.filter { w =>
      (w.host == worker.host && w.port == worker.port) && (w.state == WorkerState.DEAD)
    }.foreach { w =>
      workers -= w
    }

    val workerAddress = worker.actor.path.address
    if (addressToWorker.contains(workerAddress)) {
      val oldWorker = addressToWorker(workerAddress)
      if (oldWorker.state == WorkerState.UNKNOWN) {
        // A worker registering from UNKNOWN implies that the worker was restarted during recovery.
        // The old worker must thus be dead, so we will remove it and accept the new worker.
        removeWorker(oldWorker)
      } else {
        logInfo("Attempted to re-register worker at same address: " + workerAddress)
        return false
      }
    }

    workers += worker
    idToWorker(worker.id) = worker
    addressToWorker(workerAddress) = worker
    true
  }

  def removeWorker(worker: WorkerInfo) {//封装了worker的一些信息，端口，配置如此的
    logInfo("Removing worker " + worker.id + " on " + worker.host + ":" + worker.port)
    worker.setState(WorkerState.DEAD)
    idToWorker -= worker.id//将worker从缓存结构中移除出去
    addressToWorker -= worker.actor.path.address
    for (exec <- worker.executors.values) {//同时遍历worker的executor，向driver发送一个ExecutorUpdated信息，
      //比如worker丢失,executor丢失
      logInfo("Telling app of lost executor: " + exec.id)
      exec.application.driver ! ExecutorUpdated(
        exec.id, ExecutorState.LOST, Some("worker lost"), None)
      exec.application.removeExecutor(exec)//最后从内存缓存结构中将executor移除
    }
    for (driver <- worker.drivers.values) {//同时将这个worker上的driver进行遍历操作
      if (driver.desc.supervise) {//如果这个driver的supervise是true的话，说明这个driver也死掉了
        logInfo(s"Re-launching ${driver.id}")
        relaunchDriver(driver)//则会重新发送这个driver
      } else {
        logInfo(s"Not re-launching ${driver.id} because it was not supervised")
        removeDriver(driver.id, DriverState.ERROR, None)//如果没有supervised，则会移除
      }
    }
    persistenceEngine.removeWorker(worker)//最后从持久化引擎中移除这个worker
  }

  def relaunchDriver(driver: DriverInfo) {
    driver.worker = None
    driver.state = DriverState.RELAUNCHING
    waitingDrivers += driver//将driver加入等待队列中，就是arraybuffer
    schedule()
  }

  def createApplication(desc: ApplicationDescription, driver: ActorRef): ApplicationInfo = {
    val now = System.currentTimeMillis()
    val date = new Date(now)
    //创建一个ApplicationInfo，并且为newApplicationId生成一个唯一的ID
    new ApplicationInfo(now, newApplicationId(date), desc, date, driver, defaultCores)
  }

  def registerApplication(app: ApplicationInfo): Unit = {
    val appAddress = app.driver.path.address//拿到driver的地址，如果已经存在，就返回，说明进行了重复注册
    if (addressToApp.contains(appAddress)) {
      logInfo("Attempted to re-register application at same address: " + appAddress)
      return
    }

    applicationMetricsSystem.registerSource(app.appSource)
    //这里总的作用就是将APP的信息加入内存缓存中
    apps += app//将这个APP存到内存缓存结构总，hash去重
    idToApp(app.id) = app
    actorToApp(app.driver) = app//APP的地址和APP的对应关系
    addressToApp(appAddress) = app
    //将APP加入等待调度的队列 arraybuffer，用的fifo这样一个先入先出的算法
    waitingApps += app
  }

  def finishApplication(app: ApplicationInfo) {
    removeApplication(app, ApplicationState.FINISHED)
  }

  def removeApplication(app: ApplicationInfo, state: ApplicationState.Value) {
    if (apps.contains(app)) {
      logInfo("Removing app " + app.id)
      apps -= app
      idToApp -= app.id
      actorToApp -= app.driver
      addressToApp -= app.driver.path.address
      if (completedApps.size >= RETAINED_APPLICATIONS) {
        val toRemove = math.max(RETAINED_APPLICATIONS / 10, 1)
        completedApps.take(toRemove).foreach( a => {
          appIdToUI.remove(a.id).foreach { ui => webUi.detachSparkUI(ui) }
          applicationMetricsSystem.removeSource(a.appSource)
        })
        completedApps.trimStart(toRemove)
      }
      completedApps += app // Remember it in our history
      waitingApps -= app

      // If application events are logged, use them to rebuild the UI
      rebuildSparkUI(app)

      for (exec <- app.executors.values) {
        exec.worker.removeExecutor(exec)
        exec.worker.actor ! KillExecutor(masterUrl, exec.application.id, exec.id)
        exec.state = ExecutorState.KILLED
      }
      app.markFinished(state)
      if (state != ApplicationState.FINISHED) {
        app.driver ! ApplicationRemoved(state.toString)
      }
      persistenceEngine.removeApplication(app)
      schedule()

      // Tell all workers that the application has finished, so they can clean up any app state.
      workers.foreach { w =>
        w.actor ! ApplicationFinished(app.id)
      }
    }
  }

  /**
   * Rebuild a new SparkUI from the given application's event logs.
   * Return whether this is successful.
   */
  def rebuildSparkUI(app: ApplicationInfo): Boolean = {
    val appName = app.desc.name
    val notFoundBasePath = HistoryServer.UI_PATH_PREFIX + "/not-found"
    try {
      val eventLogFile = app.desc.eventLogDir
        .map { dir => EventLoggingListener.getLogPath(dir, app.id, app.desc.eventLogCodec) }
        .getOrElse {
          // Event logging is not enabled for this application
          app.desc.appUiUrl = notFoundBasePath
          return false
        }

      val fs = Utils.getHadoopFileSystem(eventLogFile, hadoopConf)

      if (fs.exists(new Path(eventLogFile + EventLoggingListener.IN_PROGRESS))) {
        // Event logging is enabled for this application, but the application is still in progress
        val title = s"Application history not found (${app.id})"
        var msg = s"Application $appName is still in progress."
        logWarning(msg)
        msg = URLEncoder.encode(msg, "UTF-8")
        app.desc.appUiUrl = notFoundBasePath + s"?msg=$msg&title=$title"
        return false
      }

      val logInput = EventLoggingListener.openEventLog(new Path(eventLogFile), fs)
      val replayBus = new ReplayListenerBus()
      val ui = SparkUI.createHistoryUI(new SparkConf, replayBus, new SecurityManager(conf),
        appName + " (completed)", HistoryServer.UI_PATH_PREFIX + s"/${app.id}")
      try {
        replayBus.replay(logInput, eventLogFile)
      } finally {
        logInput.close()
      }
      appIdToUI(app.id) = ui
      webUi.attachSparkUI(ui)
      // Application UI is successfully rebuilt, so link the Master UI to it
      app.desc.appUiUrl = ui.basePath
      true
    } catch {
      case fnf: FileNotFoundException =>
        // Event logging is enabled for this application, but no event logs are found
        val title = s"Application history not found (${app.id})"
        var msg = s"No event logs found for application $appName in ${app.desc.eventLogDir}."
        logWarning(msg)
        msg += " Did you specify the correct logging directory?"
        msg = URLEncoder.encode(msg, "UTF-8")
        app.desc.appUiUrl = notFoundBasePath + s"?msg=$msg&title=$title"
        false
      case e: Exception =>
        // Relay exception message to application UI page
        val title = s"Application history load error (${app.id})"
        val exception = URLEncoder.encode(Utils.exceptionString(e), "UTF-8")
        var msg = s"Exception in replaying log for application $appName!"
        logError(msg, e)
        msg = URLEncoder.encode(msg, "UTF-8")
        app.desc.appUiUrl = notFoundBasePath + s"?msg=$msg&exception=$exception&title=$title"
        false
    }
  }

  /** Generate a new app ID given a app's submission date */
  def newApplicationId(submitDate: Date): String = {
    val appId = "app-%s-%04d".format(createDateFormat.format(submitDate), nextAppNumber)
    nextAppNumber += 1
    appId
  }

  /** Check for, and remove, any timed-out workers */
  def timeOutDeadWorkers() {
    // Copy the workers into an array so we don't modify the hashset while iterating through it
    val currentTime = System.currentTimeMillis()
    val toRemove = workers.filter(_.lastHeartbeat < currentTime - WORKER_TIMEOUT).toArray
    for (worker <- toRemove) {
      if (worker.state != WorkerState.DEAD) {
        logWarning("Removing %s because we got no heartbeat in %d seconds".format(
          worker.id, WORKER_TIMEOUT/1000))
        removeWorker(worker)
      } else {
        if (worker.lastHeartbeat < currentTime - ((REAPER_ITERATIONS + 1) * WORKER_TIMEOUT)) {
          workers -= worker // we've seen this DEAD worker in the UI, etc. for long enough; cull it
        }
      }
    }
  }

  def newDriverId(submitDate: Date): String = {
    val appId = "driver-%s-%04d".format(createDateFormat.format(submitDate), nextDriverNumber)
    nextDriverNumber += 1
    appId
  }

  def createDriver(desc: DriverDescription): DriverInfo = {
    val now = System.currentTimeMillis()
    val date = new Date(now)
    new DriverInfo(now, newDriverId(date), desc, date)
  }

  //在某个worker上，启动driver
  def launchDriver(worker: WorkerInfo, driver: DriverInfo) {
    logInfo("Launching driver " + driver.id + " on worker " + worker.id)
    
    //将driver加入worker内存的缓存结构
    //将worker内使用的内存和CPU数量，都加上driver需要的内存和CPU数量
    worker.addDriver(driver)
    //同时把worker也加入到driver内部的缓存结构中
    driver.worker = Some(worker)
    //然后调用worker的actor，给他发送launchDriver消息，让worker来启动driver
    worker.actor ! LaunchDriver(driver.id, driver.desc)
    //将driver的状态设置为running
    driver.state = DriverState.RUNNING
  }
  
  //用Scala的find()高阶函数，找到driverId对应的driver
  def removeDriver(driverId: String, finalState: DriverState, exception: Option[Exception]) {
    drivers.find(d => d.id == driverId) match {
      //如果找到了，some，样例类
      case Some(driver) =>
        logInfo(s"Removing driver: $driverId")
        //将driver从内存缓存中清除
        drivers -= driver
        if (completedDrivers.size >= RETAINED_DRIVERS) {//如果已完成的driver大于等于特定值
          val toRemove = math.max(RETAINED_DRIVERS / 10, 1)
          completedDrivers.trimStart(toRemove)
        }
        //向completedDrivers中加入driver
        completedDrivers += driver
        //使用持久化引擎去除driver的持久化信息
        persistenceEngine.removeDriver(driver)
        //设置driver的state和exception信息
        driver.state = finalState
        driver.exception = exception
        //从driver所在的worker中，移除driver
        driver.worker.foreach(w => w.removeDriver(driver))
        //同样调用调度算法
        schedule()
      case None =>
        logWarning(s"Asked to remove unknown driver: $driverId")
    }
  }
}

private[spark] object Master extends Logging {
  val systemName = "sparkMaster"
  private val actorName = "Master"

  def main(argStrings: Array[String]) {
    SignalLogger.register(log)
    val conf = new SparkConf
    val args = new MasterArguments(argStrings, conf)
    val (actorSystem, _, _, _) = startSystemAndActor(args.host, args.port, args.webUiPort, conf)
    actorSystem.awaitTermination()
  }

  /**
   * Returns an `akka.tcp://...` URL for the Master actor given a sparkUrl `spark://host:port`.
   *
   * @throws SparkException if the url is invalid
   */
  def toAkkaUrl(sparkUrl: String, protocol: String): String = {
    val (host, port) = Utils.extractHostPortFromSparkUrl(sparkUrl)
    AkkaUtils.address(protocol, systemName, host, port, actorName)
  }

  /**
   * Returns an akka `Address` for the Master actor given a sparkUrl `spark://host:port`.
   *
   * @throws SparkException if the url is invalid
   */
  def toAkkaAddress(sparkUrl: String, protocol: String): Address = {
    val (host, port) = Utils.extractHostPortFromSparkUrl(sparkUrl)
    Address(protocol, systemName, host, port)
  }

  /**
   * Start the Master and return a four tuple of:
   *   (1) The Master actor system
   *   (2) The bound port
   *   (3) The web UI bound port
   *   (4) The REST server bound port, if any
   */
  def startSystemAndActor(
      host: String,
      port: Int,
      webUiPort: Int,
      conf: SparkConf): (ActorSystem, Int, Int, Option[Int]) = {
    val securityMgr = new SecurityManager(conf)
    val (actorSystem, boundPort) = AkkaUtils.createActorSystem(systemName, host, port, conf = conf,
      securityManager = securityMgr)
    val actor = actorSystem.actorOf(
      Props(classOf[Master], host, boundPort, webUiPort, securityMgr, conf), actorName)
    val timeout = AkkaUtils.askTimeout(conf)
    val portsRequest = actor.ask(BoundPortsRequest)(timeout)
    val portsResponse = Await.result(portsRequest, timeout).asInstanceOf[BoundPortsResponse]
    (actorSystem, boundPort, portsResponse.webUIPort, portsResponse.restPort)
  }
}
