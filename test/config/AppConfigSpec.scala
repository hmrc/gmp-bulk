/*
 * Copyright 2025 HM Revenue & Customs
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

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.Base64

class AppConfigSpec extends AnyWordSpec with Matchers {

  implicit val config: Configuration = Configuration.from(
    Map(
      "microservice.services.hip.host"              -> "localhost",
      "microservice.services.hip.port"              -> "8080",
      "microservice.services.hip.client-id"         -> "test-client-id",
      "microservice.services.hip.client-secret"     -> "test-secret",
      "microservice.services.hip.originatoridkey"  -> "gov-uk-originator-id",
      "microservice.services.hip.originator-id-value"-> "HMRC-GMP",
      "microservice.services.hip.environment"      -> "test-env",
      "feature.hipIntegration"                     -> true,
      "feature.ifsMigration"                       -> false
    )
  )

  implicit val servicesConfig: ServicesConfig = new ServicesConfig(config)
  implicit val featureSwitches: FeatureSwitches = new FeatureSwitches(config)

  val appConfig = new AppConfig()(using config, servicesConfig, featureSwitches)


  "AppConfig" should {

    "return the HIP base URL" in {
      appConfig.hipUrl mustBe "http://localhost:8080"
    }

    "return the encoded HIP authorisation token" in {
      val expected = Base64.getEncoder.encodeToString("test-client-id:test-secret".getBytes("UTF-8"))
      appConfig.hipAuthorisationToken mustBe expected
    }

    "return the HIP environment header" in {
      appConfig.hipEnvironmentHeader mustBe ("Environment" -> "test-env")
    }

    "expose constant header values from Constants object" in {
      appConfig.originatorIdKey mustBe Constants.OriginatorIdKey
      appConfig.originatorIdValue mustBe  "HMRC-GMP"
      appConfig.originatingSystem mustBe Constants.XOriginatingSystemHeader
      appConfig.transmittingSystem mustBe Constants.XTransmittingSystemHeader
    }

    "return correct feature toggle values" in {
      appConfig.isHipEnabled mustBe true
      appConfig.isIfsEnabled mustBe false
    }
  }
}
