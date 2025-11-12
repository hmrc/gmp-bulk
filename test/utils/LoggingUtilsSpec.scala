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

package utils

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.libs.json.Json

class LoggingUtilsSpec extends PlaySpec with GuiceOneAppPerSuite {

  "LoggingUtils.redactCalculationData" should {
    "redact nino and scon values in a flat JSON object" in {
      val json = Json.obj(
        "nino" -> "AA123456A",
        "scon" -> "S1234567T",
        "other" -> "value"
      ).toString()

      val redacted = LoggingUtils.redactCalculationData(json)
      val js = Json.parse(redacted)

      (js \ "nino").as[String] mustBe "AA1******"
      (js \ "scon").as[String] mustBe "S12******"
      (js \ "other").as[String] mustBe "value"
    }

    "redact name and surname fields in nested JSON" in {
      val json = Json.obj(
        "person" -> Json.obj(
          "firstName" -> "Stan",
          "surname" -> "Lewis",
          "middleName" -> "Q"
        )
      ).toString()

      val redacted = LoggingUtils.redactCalculationData(json)
      val js = Json.parse(redacted)
      val person = (js \ "person").as[play.api.libs.json.JsObject]

      (person \ "firstName").as[String] mustBe "S***"
      (person \ "surname").as[String] mustBe "L****"
      (person \ "middleName").as[String] mustBe "*"
    }

    "redact fields inside arrays of objects" in {
      val json = Json.obj(
        "members" -> Json.arr(
          Json.obj("nino" -> "AA111111A", "name" -> "Alice"),
          Json.obj("nino" -> "BB222222B", "name" -> "Bob")
        )
      ).toString()

      val redacted = LoggingUtils.redactCalculationData(json)
      val js = Json.parse(redacted)
      val members = (js \ "members").as[play.api.libs.json.JsArray].value

      ((members(0) \ "nino").as[String]) mustBe "AA1******"
      ((members(1) \ "nino").as[String]) mustBe "BB2******"
      ((members(0) \ "name").as[String]) mustBe "A****"
      ((members(1) \ "name").as[String]) mustBe "***"
    }

    "return masked string when JSON parsing fails" in {
      val nonJson = "This is not JSON 12345678901234567890 and email test@example.com"

      val redacted = LoggingUtils.redactCalculationData(nonJson)

      // digits replaced with '*'
      redacted must not include ("1")
      redacted must not include ("2")
      redacted must include ("*")
      // email redacted
      redacted must not include ("test@example.com")
      redacted must include ("[email]")
      // length limited to <= 100
      redacted.length must be <= 100
    }

    "leave unrelated fields untouched (except pretty formatting)" in {
      val json = Json.obj(
        "status" -> 200,
        "message" -> "OK"
      ).toString()

      val redacted = LoggingUtils.redactCalculationData(json)
      val js = Json.parse(redacted)

      (js \ "status").as[Int] mustBe 200
      (js \ "message").as[String] mustBe "OK"
    }
  }
}
