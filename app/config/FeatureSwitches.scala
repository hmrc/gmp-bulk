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

import play.api.Configuration

import javax.inject.{Inject, Singleton}

/**
 * Represents a single feature toggle.
 */
case class FeatureSwitch(name: String, enabled: Boolean)

/**
 * Loads all GMP-related feature switches from configuration.
 */
@Singleton
class FeatureSwitches @Inject()(config: Configuration) {

  private val prefix = "feature"

  /**
   * Fetches the value of a given feature switch key from config.
   */
  private def isEnabled(name: String): Boolean =
    config.getOptional[Boolean](s"$prefix.$name").getOrElse(false)

  /** Feature toggles **/
  val hipIntegration: FeatureSwitch = FeatureSwitch("hipIntegration", isEnabled("hipIntegration"))
  val ifsMigration: FeatureSwitch   = FeatureSwitch("ifsMigration", isEnabled("ifMigration"))

  /**
   * All known switches for inspection or logging.
   */
  val all: Seq[FeatureSwitch] = Seq(hipIntegration, ifsMigration)

  /**
   * Look up a switch by name.
   */
  def byName(name: String): Option[FeatureSwitch] = all.find(_.name == name)
}
