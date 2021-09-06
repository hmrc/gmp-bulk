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

import com.kenshoo.play.metrics.PlayModule
import helpers.RandomNino
import org.scalatest.MustMatchers
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import org.scalatestplus.play.PlaySpec
import play.api.i18n.{Messages, MessagesImpl}
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModule}
import play.api.libs.json.Json
import play.api.test.Helpers.stubMessagesControllerComponents
import play.api.{Application, Mode}

class BulkCalculationRequestSpec extends PlaySpec with GuiceOneAppPerSuite with MustMatchers {
  val cc = stubMessagesControllerComponents()
  implicit val messages = MessagesImpl(cc.langs.availables.head, cc.messagesApi)
  def additionalConfiguration: Map[String, String] = Map( "logger.application" -> "ERROR",
    "logger.play" -> "ERROR",
    "logger.root" -> "ERROR",
    "org.apache.logging" -> "ERROR",
    "com.codahale" -> "ERROR")
  private val bindModules: Seq[GuiceableModule] = Seq(new PlayModule)

  implicit override lazy val app: Application = new GuiceApplicationBuilder()
    .configure(additionalConfiguration)
    .bindings(bindModules:_*).in(Mode.Test)
    .build()
  val nino = RandomNino.generate

  val jsonBulkCalculationRequest = Json.parse(
    s"""
    {
        "_id": "bulk1",
        "uploadReference" : "test-uuid1",
        "userId" : "B1234568",
        "email" : "test@test.com",
        "reference" : "REF1234",
        "complete": true,
        "isParent": true,
        "total" : 10,
        "failed" : 3,
        "timestamp" : "2016-04-26T14:53:18.308",
        "calculationRequests" : [
            {
                "bulkId": "4734",
                "lineId" : 1,
                 "isChild": true,
                 "hasValidationErrors": false,
                 "hasResponse": true,
                 "hasValidRequest": true,
                "validCalculationRequest" : {
                    "scon" : "S2730000B",
                    "nino" : "$nino",
                    "surname" : "Richard-Smith",
                    "firstForename" : "Cliff",
                    "memberReference" : "MEMREF123",
                    "calctype" : 1,
                    "revaluationDate" : "2018-01-01",
                    "revaluationRate" : 2,
                    "requestEarnings" : 1,
                    "dualCalc" : 1,
                    "terminationDate" : "2016-06-06"
                },
                "calculationResponse" : {
                    "calculationPeriods" : [
                        {
                            "startDate" : "1996-04-06",
                            "endDate" : "2016-06-06",
                            "gmpTotal" : "0.71",
                            "post88GMPTotal" : "0.71",
                            "revaluationRate" : 1,
                            "errorCode" : 61234,
                            "revalued" : 0,
                            "dualCalcPost90TrueTotal" : "1.23",
                            "dualCalcPost90OppositeTotal" : "4.56",
                            "inflationProofBeyondDod" : 0,
                            "contsAndEarnings" : []
                        }
                    ],
                    "globalErrorCode" : 0,
                    "containsErrors" : true
                }
            },
            {
                "bulkId": "4734",
                "lineId" : 2,
                "isChild": true,
                "hasValidationErrors": true,
                "hasResponse": false,
                "hasValidRequest": false,
                "validationErrors": {
                    "scon": "No scon supplied"
                }
            },
            {
                "bulkId": "4734",
                "lineId" : 3,
                "isChild": true,
                "hasValidationErrors": false,
                "hasResponse": true,
                "hasValidRequest": true,
                "validCalculationRequest" : {
                    "scon" : "S2730000B",
                    "nino" : "$nino",
                    "surname" : "Richard-Smith",
                    "firstForename" : "Cliff",
                    "calctype" : 0
                },
                "calculationResponse" : {
                    "calculationPeriods" : [
                        {
                            "startDate" : "1996-04-06",
                            "endDate" : "2016-04-05",
                            "gmpTotal" : "0.71",
                            "post88GMPTotal" : "0.71",
                            "revaluationRate" : 1,
                            "errorCode" : 0,
                            "revalued" : 0,
                            "dualCalcPost90TrueTotal" : "0.00",
                            "dualCalcPost90OppositeTotal" : "0.00",
                            "inflationProofBeyondDod" : 0,
                            "contsAndEarnings" : []
                        }
                    ],
                    "globalErrorCode" : 63151,
                    "containsErrors" : true
                }
            },
            {
                "bulkId": "4734",
                "lineId" : 4,
                "isChild": true,
                "hasValidationErrors": true,
                "hasResponse": false,
                "hasValidRequest": false,
                "validationErrors": {
                   "scon": "Invalid scon format"
                }
            }
        ]
    }
    """)

  val jsonCalculationRequestWithValidationError = Json.parse(
    """
      {
        "lineId" : 1,
        "bulkId": "843",
        "isChild": true,
        "hasValidationErrors": true,
        "hasResponse": false,
        "hasValidRequest": false,
        "validationErrors": {
            "scon": "Invalid scon format"
        }
      }
    """)

  val jsonCalculationRequestWithMatchingResponse = Json.parse(
    s"""
      {
        "lineId" : 1,
        "bulkId" : "87467",
        "isChild": true,
        "hasValidationErrors": false,
        "hasResponse": true,
        "hasValidRequest": true,
        "validationErrors": {},
        "validCalculationRequest" : {
            "scon" : "S2730000B",
            "nino" : "$nino",
            "surname" : "Richard-Smith",
            "firstForename" : "Cliff",
            "memberReference" : "MEMREF123",
            "calctype" : 1,
            "revaluationDate" : "2018-01-01",
            "revaluationRate" : 2,
            "requestEarnings" : 1,
            "dualCalc" : 1,
            "terminationDate" : "2016-06-06"
        },
        "calculationResponse" : {
            "calculationPeriods" : [
                {
                    "startDate" : "1996-04-06",
                    "endDate" : "2016-06-06",
                    "gmpTotal" : "0.71",
                    "post88GMPTotal" : "0.71",
                    "revaluationRate" : 1,
                    "errorCode" : 0,
                    "revalued" : 0,
                    "dualCalcPost90TrueTotal" : "1.23",
                    "dualCalcPost90OppositeTotal" : "4.56",
                    "inflationProofBeyondDod" : 0,
                    "contsAndEarnings" : []
                }
            ],
            "globalErrorCode" : 63151,
            "containsErrors": true
        }
      }
    """)

  val jsonCalculationRequestWithMatchingResponseWithNoError = Json.parse(
    s"""
      {
        "lineId" : 1,
        "bulkId": "4732894",
         "isChild": true,
         "hasValidationErrors": false,
         "hasResponse": true,
         "hasValidRequest": true,
        "validCalculationRequest" : {
            "scon" : "S2730000B",
            "nino" : "$nino",
            "surname" : "Richard-Smith",
            "firstForename" : "Cliff",
            "memberReference" : "MEMREF123",
            "calctype" : 1,
            "revaluationDate" : "2018-01-01",
            "revaluationRate" : 2,
            "requestEarnings" : 1,
            "dualCalc" : 1,
            "terminationDate" : "2016-06-06"
        },
        "calculationResponse" : {
            "calculationPeriods" : [
                {
                    "startDate" : "1996-04-06",
                    "endDate" : "2016-06-06",
                    "gmpTotal" : "0.71",
                    "post88GMPTotal" : "0.71",
                    "revaluationRate" : 1,
                    "errorCode" : 0,
                    "revalued" : 0,
                    "dualCalcPost90TrueTotal" : "1.23",
                    "dualCalcPost90OppositeTotal" : "4.56",
                    "inflationProofBeyondDod" : 0,
                    "contsAndEarnings" : []
                }
            ],
            "globalErrorCode" : 0,
            "containsErrors" : false
        }
      }
    """)


  "CalculationRequest hasErrors" must {
    "return true if globalErrorCode defined" in {

      val request = jsonCalculationRequestWithMatchingResponse.as[ProcessReadyCalculationRequest]
      request.hasErrors must be (true)
      request.getGlobalErrorMessageReason must be(Some(Messages("63151.reason")))
      request.getGlobalErrorMessageWhat must be(Some(Messages("63151.what")))
    }

    "return true if validationErrors defined" in {

      val request = jsonCalculationRequestWithValidationError.as[ProcessReadyCalculationRequest]
      request.hasErrors must be (true)
    }

    "return false if no globalErrorCode or validation error" in {

      val request = jsonCalculationRequestWithMatchingResponseWithNoError.as[ProcessReadyCalculationRequest]

      request.hasErrors must be (false)
      request.getGlobalErrorMessageReason must be(None)
      request.getGlobalErrorMessageWhat must be(None)
    }
  }

  "BulkCalculationRequest failedRequestCount" must {
    "return count of failed requests" in {

      val request = jsonBulkCalculationRequest.as[ProcessedBulkCalculationRequest]
      request.failedRequestCount must be(4)
    }
  }
  

  "handle timestamp conversion" in {
    val bprJson = Json.parse(
      """
            {
              "uploadReference":"",
              "reference":"",
              "timestamp":"2016-05-18T17:50:55.511",
              "processedDateTime":"2016-05-18T17:50:55.511"
            }
      """
    )

    Json.toJson(bprJson.as[BulkPreviousRequest]) must equal(bprJson)
  }

}
