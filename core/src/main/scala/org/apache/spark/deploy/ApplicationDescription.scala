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

package org.apache.spark.deploy

private[spark] class ApplicationDescription(
    val name: String,//应用程序的名字
    val maxCores: Option[Int],//executor分配的内存，这些信息从spark-submit中的信息得到的
    val memoryPerSlave: Int,//分配多少内存
    val command: Command,//一些参数
    var appUiUrl: String,
    val eventLogDir: Option[String] = None,
    // short name of compression codec used when writing event logs, if any (e.g. lzf)
    val eventLogCodec: Option[String] = None)
  extends Serializable {

  val user = System.getProperty("user.name", "<unknown>")

  def copy(
      name: String = name,
      maxCores: Option[Int] = maxCores,
      memoryPerSlave: Int = memoryPerSlave,
      command: Command = command,
      appUiUrl: String = appUiUrl,
      eventLogDir: Option[String] = eventLogDir,
      eventLogCodec: Option[String] = eventLogCodec): ApplicationDescription =
    new ApplicationDescription(
      name, maxCores, memoryPerSlave, command, appUiUrl, eventLogDir, eventLogCodec)

  override def toString: String = "ApplicationDescription(" + name + ")"
}
