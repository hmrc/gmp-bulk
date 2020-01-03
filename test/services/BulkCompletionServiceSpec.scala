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

package services

import java.util.UUID

import helpers.RandomNino
import models.BulkCalculationRequest
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.OneServerPerSuite
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.libs.json.Json
import repositories.{BulkCalculationMongoRepository, BulkCalculationRepository}
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future

class BulkCompletionServiceSpec extends UnitSpec with MockitoSugar with GuiceOneAppPerSuite with BeforeAndAfterEach with MongoSpecSupport {

  lazy val bulkCalculationRespository =app.injector.instanceOf[BulkCalculationMongoRepository]
  val mongoApi  = app.injector.instanceOf[play.modules.reactivemongo.ReactiveMongoComponent]

  object TestBulkCompletionService extends BulkCompletionService(bulkCalculationRespository, mongoApi ) {
    override lazy val repository = bulkCalculationRespository
  }

  val nino = RandomNino.generate

  val jsonWithResponses = Json.parse(
    s"""
    {
        "uploadReference" : "test-uuid1",
        "userId" : "B1234568",
        "email" : "test@test.com",
        "reference" : "REF1234",
        "total" : 10,
        "failed" : 1,
        "timestamp" : "2016-04-26T14:53:18.308",
        "calculationRequests" : [
            {
                "lineId" : 1,
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
                    "globalErrorCode" : 1,
                     "containsErrors" : true
                }
            },
            {
                "lineId" : 2,
                "validationErrors": {
                   "scon": "No SCON supplied"
                }
            },
            {
                "lineId" : 3,
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
                    "globalErrorCode" : 0,
                    "containsErrors" : false
                }
            },
            {
                "lineId" : 4,
                "validationErrors": {
                   "scon": "No SCON supplied"
                }
            }
        ]
    }
    """)

  "completion service" must {

    "get a lock and check for completed documents" in {

      val request = jsonWithResponses.as[BulkCalculationRequest]
      val uploadRef = UUID.randomUUID().toString

      await(bulkCalculationRespository.insertBulkDocument(request.copy(uploadReference = uploadRef)))
      await(TestBulkCompletionService.checkForComplete())

      val result = await(bulkCalculationRespository.findByReference(uploadRef))
      result.get.complete should be(true)
      result.get.total should be(4)
    }

    "cant get a lock" in {
      val mockRepository = mock[BulkCalculationRepository]
      object TestBulkCompletionService extends BulkCompletionService(bulkCalculationRespository, mongoApi) {
        override lazy val repository = mockRepository
      }

      when(mockRepository.findAndComplete()).thenReturn(Future.successful(false))

      await(TestBulkCompletionService.checkForComplete())
    }
  }

}
