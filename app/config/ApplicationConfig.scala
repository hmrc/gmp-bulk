/*
 * Copyright 2019 HM Revenue & Customs
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

import javax.inject.Inject
import play.api.Configuration
import scala.concurrent.duration.FiniteDuration

trait ApplicationConfig {
  val bulkProcessingBatchSize: Int
  val bulkProcessingInterval: FiniteDuration
  val numberOfCallsToTriggerStateChange: Int
  val unavailablePeriodDuration: Int
  val unstablePeriodDuration: Int
  val bulkCompleteInterval: FiniteDuration
  val bulkProcessingTps: Int
}

class ApplicationConfiguration@Inject()(configuration: Configuration) extends ApplicationConfig {

  override lazy val bulkProcessingBatchSize = configuration.getInt(s"bulk-batch-size").getOrElse(100)
  override lazy val bulkProcessingTps = configuration.getInt(s"bulk-processing-tps").getOrElse(10)
  override lazy val bulkProcessingInterval: FiniteDuration = FiniteDuration(configuration.getLong(s"bulk-processing-interval").getOrElse(10L), "seconds")
  override val numberOfCallsToTriggerStateChange = configuration.getInt(s"circuit-breaker.number-of-calls-to-trigger-state-change").getOrElse(10)
  override val unavailablePeriodDuration: Int = configuration.getInt(s"circuit-breaker.unavailable-period-duration").getOrElse(300)
  override val unstablePeriodDuration: Int = configuration.getInt(s"circuit-breaker.unstable-period-duration").getOrElse(60)
  override lazy val bulkCompleteInterval = FiniteDuration.apply(configuration.getLong(s"bulk-complete-interval").getOrElse(10L), "minute")
}
