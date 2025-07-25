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

import play.api.libs.json._

object EnumRevaluationRate extends Enumeration {
  type EnumRevaluationRate = Value
  val NONE = Value("(NONE)")
  val FIXED = Value("FIXED")
  val LIMITED= Value("LIMITED")
  val S148 = Value("S148")

  implicit val format: Format[EnumRevaluationRate.Value] = new Format[EnumRevaluationRate.Value] {
    def writes(enum: EnumRevaluationRate.Value): JsValue = JsString(enum.toString)

    def reads(json: JsValue): JsResult[EnumRevaluationRate.Value] = json match {
      case JsString(str) => JsSuccess(EnumRevaluationRate.withName(str))
      case _ => JsError("EnumRevaluationRate expected String")
    }
  }
}

object EnumCalcRequestType extends Enumeration {
  type EnumCalcRequestType = Value
  val DOL = Value("DOL Calculation")
  val Revaluation = Value("Re-valuation Calculation")
  val PayableAge = Value("Payable Age Calculation")
  val Survivor = Value("Survivor Calculation")
  val SPA = Value("SPA Calculation")

  implicit val format: Format[EnumCalcRequestType.Value] = new Format[EnumCalcRequestType.Value] {
    def writes(enum: EnumCalcRequestType.Value): JsValue = JsString(enum.toString)

    def reads(json: JsValue): JsResult[EnumCalcRequestType.Value] = json match {
      case JsString(str) => JsSuccess(EnumCalcRequestType.withName(str))
      case _ => JsError("EnumCalcRequestType expected String")
    }
  }
}

case class HipCalculationRequest(schemeContractedOutNumber: String,
                                 nationalInsuranceNumber: String,
                                 surname: String,
                                 firstForename: String,
                                 secondForename: Option[String],
                                 revaluationRate: Option[EnumRevaluationRate.Value],
                                 calculationRequestType: Option[EnumCalcRequestType.Value],
                                 revaluationDate: String,
                                 terminationDate: String,
                                 includeContributionAndEarnings: Boolean,
                                 includeDualCalculation: Boolean)


object HipCalculationRequest {
  implicit val formats: OFormat[HipCalculationRequest] = Json.format[HipCalculationRequest]

  def from(calcReq: ValidCalculationRequest): HipCalculationRequest = {
    val revalEnum = calcReq.revaluationRate.map {
      case 1 => EnumRevaluationRate.FIXED
      case 2 => EnumRevaluationRate.LIMITED
      case 3 => EnumRevaluationRate.S148
      case 0 => EnumRevaluationRate.NONE
    }
    val calcTypeEnum = calcReq.calctype.map {
      case 0 => EnumCalcRequestType.DOL
      case 1 => EnumCalcRequestType.Revaluation
      case 2 => EnumCalcRequestType.PayableAge
      case 3 => EnumCalcRequestType.Survivor
      case 4 => EnumCalcRequestType.SPA
    }
    HipCalculationRequest(
      schemeContractedOutNumber = calcReq.scon,
      nationalInsuranceNumber = calcReq.nino,
      surname = calcReq.surname,
      firstForename = calcReq.firstForename,
      secondForename = None,
      revaluationRate = revalEnum,
      calculationRequestType = calcTypeEnum,
      revaluationDate = calcReq.revaluationDate.getOrElse("(NONE)"),
      terminationDate = calcReq.terminationDate.getOrElse("(NONE)"),
      includeContributionAndEarnings = true,
      includeDualCalculation = true
    )
  }
}
