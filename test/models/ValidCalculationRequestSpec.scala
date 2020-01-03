/*
 * Copyright 2020 HM Revenue & Customs
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

package models

import helpers.RandomNino
import org.scalatest.MustMatchers
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}

class ValidCalculationRequestSpec extends PlaySpec with OneAppPerSuite with MustMatchers {

  private val nino = RandomNino.generate

  "ValidCalculationRequest" should {
    val emptyRequest = ValidCalculationRequest("S1234567A", nino, "Pan", "Peter", None, None)

    "construct query parameters" when {
      "all params are None" in {
        emptyRequest.queryParams.toSet mustEqual Seq(("request_earnings", "1")).toSet
      }

      val paramTests: List[(ValidCalculationRequest, (String, String))] = List(
        (emptyRequest.copy(calctype = Some(666)), ("calctype", "666")),
        (emptyRequest.copy(revaluationRate = Some(10)), ("revalrate", "10")),
        (emptyRequest.copy(revaluationDate = Some("someDate")), ("revaldate", "someDate")),
        (emptyRequest.copy(dualCalc = Some(420)), ("dualcalc", "420")),
        (emptyRequest.copy(terminationDate = Some("apocalypse")), ("term_date", "apocalypse"))
      )

      for((request, params) <- paramTests) {
        s"${params._1} is some" in {
          request.queryParams.toSet mustEqual Seq(("request_earnings", "1"), params).toSet
        }
      }
    }

    "format the surname in the uri" when {
      "surname is longer than 3 letters" in {
        emptyRequest.copy(surname = "Hook").desUri must include("surname/HOO/")
      }

      "Surname is 4 letters long" in {
        emptyRequest.copy(surname = "May").desUri must include("surname/MAY/")
      }

      "Surname is less than 3 Letters long" in {
        emptyRequest.copy(surname = "Xi").desUri must include("surname/XI/")
      }

      "Surname contains apostrophe" in {
        emptyRequest.copy(surname = "O'Neil").desUri must include("surname/O%27N/")
      }
    }

    "Take first name Initial" in {
      emptyRequest.copy(firstForename = "Pascal").desUri must include("firstname/P/")
    }

    "format the scon" in {
      emptyRequest.desUri must include("scon/S")
      emptyRequest.desUri must include("/1234567/")
      emptyRequest.desUri must include("/A/nino")
    }

    "Construct the request uri" in {
      emptyRequest.desUri mustEqual s"/scon/S/1234567/A/nino/$nino/surname/PAN/firstname/P/calculation/"
    }
  }
}
