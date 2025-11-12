/*
 * Copyright 2024 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package config

import com.google.common.math.IntMath.divide

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.math.RoundingMode
import java.util.Base64

trait ApplicationConfig {
  val bulkProcessingBatchSize: Int
  val numberOfCallsToTriggerStateChange: Int
  val unavailablePeriodDuration: Int
  val unstablePeriodDuration: Int
  val bulkProcessingTps: Int
  val bulkProcessingInterval: Int
  val ifEnabled: Boolean
  val logParentsChildrenEnabled: Boolean
}

class ApplicationConfiguration@Inject()(configuration: Configuration) extends ApplicationConfig {

  override val bulkProcessingBatchSize = configuration.getOptional[Int](s"bulk-batch-size").getOrElse(100)
  override val bulkProcessingTps = configuration.getOptional[Int](s"bulk-processing-tps").getOrElse(10)
  override val bulkProcessingInterval: Int = divide(bulkProcessingBatchSize, bulkProcessingTps, RoundingMode.UP)
  override val numberOfCallsToTriggerStateChange = configuration.getOptional[Int](s"circuit-breaker.number-of-calls-to-trigger-state-change").getOrElse(10)
  override val unavailablePeriodDuration: Int = configuration.getOptional[Int](s"circuit-breaker.unavailable-period-duration").getOrElse(300)
  override val unstablePeriodDuration: Int = configuration.getOptional[Int](s"circuit-breaker.unstable-period-duration").getOrElse(60)
  override val ifEnabled: Boolean = configuration.getOptional[Boolean]("ifs-enabled").getOrElse(false)
  override val logParentsChildrenEnabled: Boolean = configuration.getOptional[Boolean]("log-parents-children-enabled").getOrElse(false)
}
@Singleton
class AppConfig @Inject()(implicit
                          configuration: Configuration,
                          servicesConfig: ServicesConfig,
                          val featureSwitches: FeatureSwitches
                         ) {

  import servicesConfig._

  def hipUrl: String = servicesConfig.baseUrl("hip")
  private val clientId: String = getString("microservice.services.hip.client-id")
  private val secret: String   = getString("microservice.services.hip.client-secret")

  def hipAuthorisationToken: String =
    Base64.getEncoder.encodeToString(s"$clientId:$secret".getBytes("UTF-8"))

  def hipEnvironmentHeader: (String, String) =
    "Environment" -> getString("microservice.services.hip.environment")

  // These are now constants
  def originatorIdKey: String           = Constants.OriginatorIdKey
  def originatorIdValue: String         = getString("microservice.services.hip.originator-id-value")
  def originatingSystem: String         = Constants.XOriginatingSystemHeader
  def transmittingSystem: String        = Constants.XTransmittingSystemHeader

  def isHipEnabled: Boolean = featureSwitches.hipIntegration.enabled
  def isIfsEnabled: Boolean = featureSwitches.ifsMigration.enabled
}