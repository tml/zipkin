/*
 * Copyright 2012 Twitter Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.zipkin.config

import com.sun.net.httpserver.HttpExchange
import com.twitter.zipkin.storage.Store
import com.twitter.zipkin.collector.{WriteQueue, ZipkinCollector}
import com.twitter.zipkin.collector.filter.{ServiceStatsFilter, SamplerFilter, ClientIndexFilter}
import com.twitter.zipkin.collector.sampler.{AdaptiveSampler, ZooKeeperGlobalSampler, GlobalSampler}
import com.twitter.zipkin.config.collector.CollectorServerConfig
import com.twitter.zipkin.config.sampler._
import com.twitter.common.zookeeper.ZooKeeperClient
import com.twitter.conversions.time._
import com.twitter.ostrich.admin.{UnknownCommandError, CgiRequestHandler, ServiceTracker, RuntimeEnvironment}
import com.twitter.util.{FuturePool, Config}
import com.twitter.zk._
import java.net.{InetAddress, InetSocketAddress}
import org.apache.zookeeper.ZooDefs.Ids
import scala.collection.JavaConverters._
import com.twitter.zipkin.collector.processor._
import com.twitter.zipkin.common.Span
import com.twitter.finagle.{Filter, Service}
import com.twitter.zipkin.builder.{ZooKeeperClientBuilder, Builder}

trait ZipkinCollectorConfig extends ZipkinConfig[ZipkinCollector] {

  var serverPort : Int = 9410
  var adminPort  : Int = 9900

  /* ZooKeeper paths */
  var zkConfigPath            : String = "/twitter/service/zipkin/config"
  var zkServerSetPath         : String = "/twitter/service/zipkin/collector"

  /* ZooKeeper key for `AdjustableRateConfig`s */
  var zkSampleRateKey         : String = "samplerate"
  var zkStorageRequestRateKey : String = "storagerequestrate"

  /* Prefix for service/endpoint stats */
  var serviceStatsPrefix : String = "agg."

  /* Do not publish .p<percent> stats */
  adminStatsFilters = (serviceStatsPrefix + """.*\.p([0-9]*)""").r :: adminStatsFilters

  def storeBuilder: Builder[Store]
  lazy val store: Store = storeBuilder.apply()

  /* ZooKeeper */
  def zkClientBuilder: ZooKeeperClientBuilder
  lazy val zkClient: ZooKeeperClient = zkClientBuilder.apply()

  lazy val connector: Connector =
    CommonConnector(zkClient)(FuturePool.defaultPool)

  lazy val zClient: ZkClient =
    ZkClient(connector)
      .withAcl(Ids.OPEN_ACL_UNSAFE.asScala)
      .withRetryPolicy(RetryPolicy.Exponential(1.second, 1.5)(timer))

  /* `AdjustableRateConfig`s */
  lazy val sampleRateConfig: AdjustableRateConfig =
    ZooKeeperSampleRateConfig(zClient, zkConfigPath, zkSampleRateKey)
  lazy val storageRequestRateConfig: AdjustableRateConfig =
    ZooKeeperStorageRequestRateConfig(zClient, zkConfigPath, zkStorageRequestRateKey)

  /**
   *  Adaptive Sampler
   *  Dynamically adjusts the sample rate so we have a stable write throughput
   *  Default is a NullAdaptiveSamplerConfig that does nothing
   **/
  def adaptiveSamplerConfig: AdaptiveSamplerConfig = new NullAdaptiveSamplerConfig {}
  lazy val adaptiveSampler: AdaptiveSampler = adaptiveSamplerConfig.apply()

  def globalSampler: GlobalSampler = new ZooKeeperGlobalSampler(sampleRateConfig)

  /**
   * To accommodate a particular input type `T`, define a `rawDataFilter` that
   * converts the input data type (ex: Scrooge-generated Thrift) into a `com.twitter.zipkin.common.Span`
   */
  type T
  def rawDataFilter: Filter[T, Unit, Span, Unit]

  lazy val processor: Service[T, Unit] =
    rawDataFilter andThen
    new SamplerFilter(globalSampler) andThen
    new ServiceStatsFilter andThen
    new FanoutService[Span](
      new StorageService(store.storage) ::
      (new ClientIndexFilter andThen new IndexService(store.index))
    )

  def writeQueueConfig: WriteQueueConfig[T]
  lazy val writeQueue: WriteQueue[T] = writeQueueConfig.apply(processor)

  val serverConfig: CollectorServerConfig

  def apply(runtime: RuntimeEnvironment): ZipkinCollector = {
    addConfigEndpoint()
    new ZipkinCollector(this)
  }

  /**
   * Add endpoints to the Ostrich admin service for configuring the adjustable values
   *
   * Methods:
   *   GET  /config/sampleRate
   *   POST /config/sampleRate?value=0.1
   *   GET  /config/storageRequestRate
   *   POST /config/storageRequestRate?value=100
   */
  private[config] def addConfigEndpoint() {
    adminHttpService map {
      _.addContext("/config", new ConfigRequestHandler(sampleRateConfig, storageRequestRateConfig))
    }
  }
}

class ConfigRequestHandler(
  sampleRateConfig: AdjustableRateConfig,
  storageRequestRateConfig: AdjustableRateConfig
) extends CgiRequestHandler {
  def handle(exchange: HttpExchange, path: List[String], parameters: List[(String, String)]) {
    if (path.length != 2) {
      render("invalid command", exchange, 404)
    }

    val paramMap = Map(parameters:_*)

    path(1) match {
      case "sampleRate"         => handleAction(exchange, paramMap, sampleRateConfig)
      case "storageRequestRate" => handleAction(exchange, paramMap, storageRequestRateConfig)
      case _                    => render("invalid command\n", exchange, 404)
    }
  }

  private def handleAction(exchange: HttpExchange, paramMap: Map[String, String], a: AdjustableRateConfig) {
    exchange.getRequestMethod match {
      case "GET" =>
        render(a.get.toString, exchange, 200)
      case "POST" =>
        paramMap.get("value") match {
          case Some(value) =>
            try {
              a.set(value.toDouble)
              render("success", exchange, 200)
            } catch {
              case e =>
                render("invalid input", exchange, 500)
            }
          case None =>
            render("invalid command", exchange, 404)
        }
    }
  }
}

trait WriteQueueConfig[T] extends Config[WriteQueue[T]] {

  var writeQueueMaxSize: Int = 500
  var flusherPoolSize: Int = 10

  def apply(service: Service[T, Unit]): WriteQueue[T] = {
    val wq = new WriteQueue[T](writeQueueMaxSize, flusherPoolSize, service)
    wq.start()
    ServiceTracker.register(wq)
    wq
  }

  def apply(): WriteQueue[T] = {
    val wq = new WriteQueue[T](writeQueueMaxSize, flusherPoolSize, new NullService[T])
    wq.start()
    ServiceTracker.register(wq)
    wq
  }
}
