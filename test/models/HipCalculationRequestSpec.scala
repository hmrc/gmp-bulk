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

package models

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite

import java.time.format.DateTimeFormatter
import play.api.libs.json.{JsError, JsNumber, JsString, JsSuccess, Json}


class HipCalculationRequestSpec extends PlaySpec with GuiceOneAppPerSuite{

  private val fullDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  "HipCalculationRequest.from" should {
    "correctly transform CalculationRequest into HipCalculationRequest" in{
      val calcReq = ValidCalculationRequest(
        scon = "S1234567T",
        nino = "AA123456A",
        surname = "lewis",
        firstForename = "stan",
        memberReference = Some("TET123"),
        revaluationRate = None, // Maps to "(NONE)"
        calctype = None, // Maps to "DOL Calculation"
        revaluationDate = Some("2022-06-01"),
        terminationDate = Some("2022-06-30"),
        memberIsInScheme = Some(true), // Maps to true
        dualCalc = Some(1)
      )
      val hipRequest = HipCalculationRequest.from(calcReq)

      hipRequest.schemeContractedOutNumber must be ("S1234567T")
      hipRequest.nationalInsuranceNumber must be ("AA123456A")
      hipRequest.surname must be ("lewis")
      hipRequest.firstForename must be ("stan")
      hipRequest.secondForename must be (None)
      hipRequest.revaluationRate must be (None)
      hipRequest.calculationRequestType must be (None)
      hipRequest.revaluationDate must be ("2022-06-01")
      hipRequest.terminationDate must be ("2022-06-30")
      hipRequest.includeContributionAndEarnings must be (true)
      hipRequest.includeDualCalculation must be (true)
    }

    "correctly transform CalculationRequest int values into HipCalculationRequest String" in{
      val calcReq = ValidCalculationRequest(
        scon = "S1234567T",
        nino = "AA123456A",
        surname = "lewis",
        firstForename = "stan",
        memberReference = Some("TET123"),
        revaluationRate = None, // Maps to "(NONE)"
        calctype = Some(0), // Maps to "DOL Calculation"
        revaluationDate = Some("2022-06-01"),
        terminationDate = Some("2022-06-30"),
        memberIsInScheme = Some(true), // Maps to true
        dualCalc = Some(1)
      )
      val hipRequest = HipCalculationRequest.from(calcReq)

      hipRequest.schemeContractedOutNumber must be ("S1234567T")
      hipRequest.nationalInsuranceNumber must be ("AA123456A")
      hipRequest.surname must be ("lewis")
      hipRequest.firstForename must be ("stan")
      hipRequest.secondForename must be (None)
      hipRequest.revaluationRate must be (None)
      hipRequest.calculationRequestType mustBe Some(EnumCalcRequestType.DOL)
      hipRequest.revaluationDate must be ("2022-06-01")
      hipRequest.terminationDate must be ("2022-06-30")
      hipRequest.includeContributionAndEarnings must be (true)
      hipRequest.includeDualCalculation must be (true)
    }

    "correctly transform CalculationRequest HMRC as revaluationRate into HipCalculationRequest None value" in{
      val calcReq = ValidCalculationRequest(
        scon = "S1234567T",
        nino = "AA123456A",
        surname = "lewis",
        firstForename = "stan",
        memberReference = Some("TET123"),
        revaluationRate = Some(0), // Maps to "(NONE)"
        calctype = None, // Maps to "DOL Calculation"
        revaluationDate = Some("2022-06-01"),
        terminationDate = Some("2022-06-30"),
        memberIsInScheme = Some(true), // Maps to true
        dualCalc = Some(1)
      )
      val hipRequest = HipCalculationRequest.from(calcReq)

      hipRequest.schemeContractedOutNumber must be ("S1234567T")
      hipRequest.nationalInsuranceNumber must be ("AA123456A")
      hipRequest.surname must be ("lewis")
      hipRequest.firstForename must be ("stan")
      hipRequest.secondForename must be (None)
      hipRequest.revaluationRate mustBe Some(EnumRevaluationRate.NONE)
      hipRequest.calculationRequestType must be (None)
      hipRequest.revaluationDate must be ("2022-06-01")
      hipRequest.terminationDate must be ("2022-06-30")
      hipRequest.includeContributionAndEarnings must be (true)
      hipRequest.includeDualCalculation must be (true)
    }


    "correctly transform CalculationRequest missing dates to HipCalculationRequest correctly" in{
      val calcReq = ValidCalculationRequest(
        scon = "S1234567T",
        nino = "AA123456A",
        surname = "lewis",
        firstForename = "stan",
        memberReference = Some("TET123"),
        revaluationRate = None, // Maps to "(NONE)"
        calctype = Some(0), // Maps to "DOL Calculation"
        revaluationDate = None,
        terminationDate = None,
        memberIsInScheme = Some(true), // Maps to true
        dualCalc = Some(1)// Maps to true
      )
      val hipRequest = HipCalculationRequest.from(calcReq)

      hipRequest.schemeContractedOutNumber must be ("S1234567T")
      hipRequest.nationalInsuranceNumber must be ("AA123456A")
      hipRequest.surname must be ("lewis")
      hipRequest.firstForename must be ("stan")
      hipRequest.secondForename must be (None)
      hipRequest.revaluationRate must be (None)
      hipRequest.calculationRequestType mustBe Some(EnumCalcRequestType.DOL)
      hipRequest.revaluationDate must be ("(NONE)")
      hipRequest.terminationDate must be ("(NONE)")
      hipRequest.includeContributionAndEarnings must be (true)
      hipRequest.includeDualCalculation must be (true)
    }

  }

  "EnumCalcRequestType" should {
    "serialize to JSON correctly" in {
      Json.toJson(EnumCalcRequestType.DOL) mustBe JsString("DOL Calculation")
      Json.toJson(EnumCalcRequestType.Revaluation) mustBe JsString("Re-valuation Calculation")
      Json.toJson(EnumCalcRequestType.PayableAge) mustBe JsString("Payable Age Calculation")
      Json.toJson(EnumCalcRequestType.Survivor) mustBe JsString("Survivor Calculation")
      Json.toJson(EnumCalcRequestType.SPA) mustBe JsString("SPA Calculation")
    }
    "deserialize from JSON correctly" in {
      Json.fromJson[EnumCalcRequestType.Value](JsString("DOL Calculation")) mustBe JsSuccess(EnumCalcRequestType.DOL)
      Json.fromJson[EnumCalcRequestType.Value](JsString("Re-valuation Calculation")) mustBe JsSuccess(EnumCalcRequestType.Revaluation)
      Json.fromJson[EnumCalcRequestType.Value](JsString("Payable Age Calculation")) mustBe JsSuccess(EnumCalcRequestType.PayableAge)
      Json.fromJson[EnumCalcRequestType.Value](JsString("Survivor Calculation")) mustBe JsSuccess(EnumCalcRequestType.Survivor)
      Json.fromJson[EnumCalcRequestType.Value](JsString("SPA Calculation")) mustBe JsSuccess(EnumCalcRequestType.SPA)
    }
  }

  "EnumRevaluationRate" should {
    "serialize to JSON correctly" in {
      Json.toJson(EnumRevaluationRate.NONE) mustBe JsString("(NONE)")
      Json.toJson(EnumRevaluationRate.FIXED) mustBe JsString("FIXED")
      Json.toJson(EnumRevaluationRate.LIMITED) mustBe JsString("LIMITED")
      Json.toJson(EnumRevaluationRate.S148) mustBe JsString("S148")
    }
    "deserialize from JSON correctly" in {
      Json.fromJson[EnumRevaluationRate.Value](JsString("(NONE)")) mustBe JsSuccess(EnumRevaluationRate.NONE)
      Json.fromJson[EnumRevaluationRate.Value](JsString("FIXED")) mustBe JsSuccess(EnumRevaluationRate.FIXED)
      Json.fromJson[EnumRevaluationRate.Value](JsString("LIMITED")) mustBe JsSuccess(EnumRevaluationRate.LIMITED)
      Json.fromJson[EnumRevaluationRate.Value](JsString("S148")) mustBe JsSuccess(EnumRevaluationRate.S148)
    }
  }
}
