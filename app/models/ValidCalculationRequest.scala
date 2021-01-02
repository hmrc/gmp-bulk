/*
 * Copyright 2021 HM Revenue & Customs
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

import java.net.URLEncoder

import play.api.libs.json.Json

case class ValidCalculationRequest(scon: String,
                                   nino: String,
                                   surname: String,
                                   firstForename: String,
                                   memberReference: Option[String],
                                   calctype: Option[Int],
                                   revaluationDate: Option[String] = None,
                                   revaluationRate: Option[Int] = None,
                                   dualCalc: Option[Int] = None,
                                   terminationDate: Option[String] = None,
                                   memberIsInScheme: Option[Boolean] = None
                                  ) {

  val queryParams: Seq[(String, String)] =
    Seq(
      "revalrate" -> revaluationRate,
      "revaldate" -> revaluationDate,
      "calctype" -> calctype,
      "request_earnings" -> Some(1),
      "dualcalc" -> dualCalc,
      "term_date" -> terminationDate
    ).collect{case (k, v) if v.isDefined => (k, v.get.toString)}

  //TODO align scon formatting to api spec. ([s])([1-9]{1,7}[A-Z])
  def desUri: String = {
    val truncatedSurname = URLEncoder.encode(surname.replaceAllLiterally(" ", "").take(3).toUpperCase, "UTF-8")
    val initial = URLEncoder.encode(firstForename.take(1).toUpperCase, "UTF-8")
    val (sconPrefix, sconNumber, sconSuffix) =
      (scon.substring(0, 1).toUpperCase, scon.substring(1, 8), scon.substring(8, 9).toUpperCase)

    s"""/scon/$sconPrefix/$sconNumber/$sconSuffix/nino/${nino.toUpperCase}/surname/$truncatedSurname/firstname/$initial/calculation/"""
  }
}

object ValidCalculationRequest {
  implicit val formats = Json.format[ValidCalculationRequest]
}
