/*
 * Copyright 2016 HM Revenue & Customs
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

package repositories

import java.util.UUID

import connectors.{EmailConnector, ProcessedUploadTemplate}
import helpers.RandomNino
import metrics.Metrics
import models._
import org.joda.time.LocalDateTime
import org.mockito.Mockito._
import org.mockito.Matchers
import org.scalatest.BeforeAndAfterEach
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.{OneServerPerSuite, PlaySpec}
import play.api.libs.json.{JsObject, Json}
import reactivemongo.api.Cursor
import reactivemongo.api.commands.UpdateWriteResult
import reactivemongo.api.indexes.CollectionIndexesManager
import reactivemongo.json._
import reactivemongo.json.collection.{JSONQueryBuilder, JSONCollection}
import uk.gov.hmrc.mongo.{Awaiting, MongoSpecSupport}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.http.HeaderCarrier
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.collection.generic.CanBuildFrom
import scala.concurrent.{ExecutionContext, Future}

class BulkCalculationRepositorySpec extends PlaySpec with OneServerPerSuite with MongoSpecSupport with Awaiting with MockitoSugar with BeforeAndAfterEach {

  val mockCollection = mock[JSONCollection]
  val mockAuditConnector = mock[AuditConnector]
  val mockEmailConnector = mock[EmailConnector]
  val bulkCalculationRepository = new TestBulkCalculationMongoRepository
  val mockMetrics = mock[Metrics]

  override protected def beforeEach() {
    await(bulkCalculationRepository.collection.remove(Json.obj()))
    reset(mockEmailConnector)
    reset(mockCollection)
  }

  class TestCalculationRepository extends BulkCalculationMongoRepository {
    override lazy val collection = mockCollection
    override val metrics = mockMetrics
  }

  class TestBulkCalculationMongoRepository extends BulkCalculationMongoRepository{
    override val auditConnector = mockAuditConnector
    override val emailConnector = mockEmailConnector
    override val metrics = mockMetrics
  }

  val nino = RandomNino.generate

  val json = Json.parse(
    s"""
              {
                "uploadReference" : "UPLOAD4321",
                "email" : "test@test.com",
                "reference" : "REF1234",
                "calculationRequests" : [
                  {
                    "lineId": 1,

                    "validCalculationRequest": {
                      "scon" : "S2730000B",
                      "memberReference": "MEMREF123",
                      "nino" : "$nino",
                      "surname" : "Richard-Smith",
                      "firstForename": "Cliff",
                      "calctype" : 1,
                      "revaluationDate": "2018-01-01",
                      "revaluationRate": 2,
                      "requestEarnings": 1,
                      "dualCalc" : 1,
                      "terminationDate" : "2016-07-07"
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
                    "validCalculationRequest": {
                      "scon" : "S2730000B",
                      "nino" : "$nino",
                      "surname" : "Richard-Smith",
                      "firstForename": "Cliff",
                      "calctype" : 0
                    }
                  },
                  {
                    "lineId" : 4,
                    "validationErrors": {
                       "scon": "No SCON supplied"
                    }
                  }],
                "userId" : "123456",
                "timestamp" : "2016-04-26T14:53:18.308",
                "complete" : true,
                "createdAt" : "2016-04-26T14:53:18.308",
                "processedDateTime" : "2016-04-26T14:53:18.308"
              }
    """)

  val jsonWithEmptyResponse = Json.parse(
    s"""
              {
                "uploadReference" : "UPLOAD1234",
                "email" : "test@test.com",
                "reference" : "REF1234",
                "calculationRequests" : [
                  {
                    "lineId" : 3,
                    "validCalculationRequest": {
                      "scon" : "S2730000B",
                      "nino" : "$nino",
                      "surname" : "Smith-Richard",
                      "firstForename": "Cliff",
                      "calctype" : 0
                    },
                    "calculationResponse": {
                      "calculationPeriods": [],
                      "globalErrorCode": 0,
                      "containsErrors" : false
                    }
                  }],
                "userId" : "123456",
                "timestamp" : "2016-04-26T14:53:18.308"
              }
    """)

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
                "validCalculationRequest" : {
                    "scon" : "S2730000B",
                    "nino" : "$nino",
                    "surname" : "Richard-Smith",
                    "firstForename" : "Cliff",
                    "calctype" : 0
                },
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
                "validCalculationRequest" : {
                    "scon" : "S2730000B",
                    "nino" : "$nino",
                    "surname" : "Richard-Smith",
                    "firstForename" : "Cliff",
                    "calctype" : 0
                },
                "validationErrors": {
                   "scon": "No SCON supplied"
                }
            }
        ]
    }
  """)

  val jsonWithValidationErrors = Json.parse(
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
                "validationErrors": {
                   "scon": "No SCON supplied"
                }
            }
        ]
    }
    """)

  "BulkCalculationMongoRepository" must {


    "inserting a calculation" must {

      "persist a calculation in the repo" in {

        val request = json.as[BulkCalculationRequest]

        val cached = await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = UUID.randomUUID().toString)))
        cached must be(true)
      }


    }

    "inserting a bulk calculation" must {
      "cope with failures" in {
        val request = json.as[BulkCalculationRequest]
        val mockIndexesManager = mock[CollectionIndexesManager]

        when(mockCollection.indexesManager).thenReturn(mockIndexesManager)

        when (mockCollection.insert(Matchers.any(),Matchers.any())(Matchers.any(),Matchers.any())).thenThrow(new RuntimeException)
        when(mockCollection.indexesManager.create(Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(true,0,0,Nil,Nil,None,None,None)))
        when(mockCollection.ImplicitlyDocumentProducer).thenThrow(new RuntimeException)
        val testRepository = new TestCalculationRepository

        val cached = await(testRepository.insertBulkDocument(request.copy(uploadReference = UUID.randomUUID().toString)))
        cached must be(false)
      }
    }

    "find and complete" must {
      "cope with failures" in {
        val request = json.as[BulkCalculationRequest]
        val mockIndexesManager = mock[CollectionIndexesManager]

        when(mockCollection.indexesManager).thenReturn(mockIndexesManager)

        when (mockCollection.update(Matchers.any(),Matchers.any(),Matchers.any(),Matchers.any(),Matchers.any())(Matchers.any(),Matchers.any(),Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(false,0,0,Nil,Nil,None,None,None)))
        when(mockCollection.indexesManager.create(Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(true,0,0,Nil,Nil,None,None,None)))
        when(mockEmailConnector.sendProcessedTemplatedEmail(Matchers.any())(Matchers.any())).thenReturn(Future.successful(true))
        val testRepository = new TestCalculationRepository

        val cached = await(testRepository.findAndComplete())
        cached must be(false)
      }

      "send out processed email notification on complete" in {

        when(mockAuditConnector.sendEvent(Matchers.any())(Matchers.any(), Matchers.any())).thenReturn(Future.successful(AuditResult.Success))
        val request = jsonWithResponses.as[BulkCalculationRequest]
        val uploadRef = UUID.randomUUID().toString
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = uploadRef)))

        val mockIndexesManager = mock[CollectionIndexesManager]

        when(mockCollection.indexesManager).thenReturn(mockIndexesManager)
        when(mockCollection.update(Matchers.any(),Matchers.any(),Matchers.any(),Matchers.any(),Matchers.any())(Matchers.any(),Matchers.any(),Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(true,0,0,Nil,Nil,None,None,None)))
        when(mockCollection.indexesManager.create(Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(true,0,0,Nil,Nil,None,None,None)))

        when(mockEmailConnector.sendProcessedTemplatedEmail(Matchers.any[ProcessedUploadTemplate])(Matchers.any[HeaderCarrier])).thenReturn(Future.successful(true))

        await(bulkCalculationRepository.findAndComplete())

        verify(mockEmailConnector).sendProcessedTemplatedEmail(Matchers.any[ProcessedUploadTemplate])(Matchers.any[HeaderCarrier])

      }
    }

    "finding requests to process" must {
      "return calculations" in {
        val request = json.as[BulkCalculationRequest]
        val requestWithResponse = jsonWithEmptyResponse.as[BulkCalculationRequest]

        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = UUID.randomUUID().toString, complete = None)))
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = UUID.randomUUID().toString, complete = None)))
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = UUID.randomUUID().toString)))
        await(bulkCalculationRepository.insertBulkDocument(requestWithResponse.copy(uploadReference = UUID.randomUUID().toString)))

        val result = await(bulkCalculationRepository.findRequestsToProcess())
        result.get.size must be(4)
      }

      "handle failure in requests to process" in {
        val mockIndexesManager = mock[CollectionIndexesManager]

        when(mockCollection.indexesManager).thenReturn(mockIndexesManager)
        when(mockCollection.aggregate(Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any())(Matchers.any())).thenThrow(new RuntimeException)
        when(mockCollection.indexesManager.create(Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(true,0,0,Nil,Nil,None,None,None)))

        val testRepository = new TestCalculationRepository

        val found = await(testRepository.findRequestsToProcess())
        found must be(None)
      }
    }

    "finding count of remaining requests" must {
      "return count" in {
        val request = json.as[BulkCalculationRequest]
        val requestWithResponse = jsonWithEmptyResponse.as[BulkCalculationRequest]

        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = UUID.randomUUID().toString, complete = None)))
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = UUID.randomUUID().toString, complete = None)))
        await(bulkCalculationRepository.insertBulkDocument(requestWithResponse.copy(uploadReference = UUID.randomUUID().toString)))

        val result = await(bulkCalculationRepository.findCountRemaining)
        result.get must be(4)
      }

      "handle failure in getting count" in {
        val mockIndexesManager = mock[CollectionIndexesManager]

        when(mockCollection.indexesManager).thenReturn(mockIndexesManager)
        when (mockCollection.count[CalculationRequest](Matchers.any(),Matchers.any(),Matchers.any(),Matchers.any())(Matchers.any(),Matchers.any())).thenThrow(new RuntimeException)
        when(mockCollection.indexesManager.create(Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(true,0,0,Nil,Nil,None,None,None)))
        val testRepository = new TestCalculationRepository

        val found = await(testRepository.findCountRemaining)
        found must be(None)
      }
    }

    "finding a calculation" must {

      "return the found calculation in sorted order" in {
        val request = jsonWithResponses.as[BulkCalculationRequest]
        val uploadReference = UUID.randomUUID().toString
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = uploadReference)))

        val cached = await(bulkCalculationRepository.findByReference(uploadReference))
        cached.get.uploadReference must be(uploadReference)
        cached.get.calculationRequests.size must be(4)
        cached.get.calculationRequests.head.lineId must be(1)
        cached.get.calculationRequests(1).lineId must be(2)
        cached.get.calculationRequests(2).lineId must be(3)
        cached.get.calculationRequests(3).lineId must be(4)
      }

      "return the found calculation with only failures" in {
        val request = jsonWithResponses.as[BulkCalculationRequest]
        val uploadReference = UUID.randomUUID().toString
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = uploadReference)))

        val cached = await(bulkCalculationRepository.findByReference(uploadReference, CsvFilter.Failed))
        cached.get.uploadReference must be(uploadReference)
        cached.get.calculationRequests.size must be(3)
      }

      "return the found calculation with only successes" in {
        val request = jsonWithResponses.as[BulkCalculationRequest]
        val uploadReference = UUID.randomUUID().toString
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = uploadReference)))

        val cached = await(bulkCalculationRepository.findByReference(uploadReference, CsvFilter.Successful))
        cached.get.uploadReference must be(uploadReference)
        cached.get.calculationRequests.size must be(1)

      }

      "return None when mongo find returns error" in {

        val mockIndexesManager = mock[CollectionIndexesManager]

        when(mockCollection.indexesManager).thenReturn(mockIndexesManager)
        when(mockCollection.find(Matchers.any())(Matchers.any())).thenThrow(new RuntimeException)
        when(mockCollection.indexesManager.create(Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(true,0,0,Nil,Nil,None,None,None)))

        val testRepository = new TestCalculationRepository

        val request = BulkCalculationRequest(None,"ur", "jimemail", "thing", Nil, "", LocalDateTime.now(), Some(false), None, None)

        val found = await(testRepository.findByReference(request.uploadReference))
        found must be(None)
      }
    }

    "finding requests" must {
      "return the found calculation" in {

        val mockIndexesManager = mock[CollectionIndexesManager]

        val timeStamp = LocalDateTime.now()
        val processedDateTime = LocalDateTime.now()

        when(mockCollection.indexesManager).thenReturn(mockIndexesManager)
        when(mockCollection.indexesManager.create(Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(true,0,0,Nil,Nil,None,None,None)))
        setupFindFor(mockCollection, Seq(BulkPreviousRequest("", "", timeStamp, processedDateTime)))

        val testRepository = new TestCalculationRepository

        val found = await(testRepository.findByUserId("A1234567"))
        found must be(Some(List(BulkPreviousRequest("", "", timeStamp, processedDateTime))))
      }

      "return None when mongo find by user id returns error" in {

        val mockIndexesManager = mock[CollectionIndexesManager]

        when(mockCollection.indexesManager).thenReturn(mockIndexesManager)
        when(mockCollection.find(Matchers.any())(Matchers.any())).thenThrow(new RuntimeException)
        when(mockCollection.indexesManager.create(Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(true,0,0,Nil,Nil,None,None,None)))

        val testRepository = new TestCalculationRepository

        val request = BulkCalculationRequest(None,"ur", "jimemail", "thing", Nil, "", LocalDateTime.now(), Some(false), None, None)

        val found = await(testRepository.findByUserId(request.userId))
        found must be(None)
      }
    }


    "updating a calculation" must {

      "inserting a calculation" must {

        "persist a calculation in the repo" in {


          val request = json.as[BulkCalculationRequest].copy(uploadReference = UUID.randomUUID().toString)
          val response = GmpBulkCalculationResponse(Nil, 0, None, None, None)

          await(bulkCalculationRepository.insertBulkDocument(request).map {
            insert => {
              val result = bulkCalculationRepository.insertResponseByReference(request.uploadReference, 1, response)

              val cached = await(result)

              cached must be(true)
            }
          })
        }

        "cannot insert duplicate upload references" in {
          val request = json.as[BulkCalculationRequest]
          val requestWithResponse = jsonWithEmptyResponse.as[BulkCalculationRequest]
          val _uploadReference = UUID.randomUUID().toString

          val x = await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = _uploadReference, complete = None)))
          val y = await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = _uploadReference, complete = None)))

          x must be(true)
          y must be(false)
        }
      }
    }

    "creating a bulk request summary" must {

      "return correct summary" in {

        val request = json.as[BulkCalculationRequest].copy(uploadReference = UUID.randomUUID().toString, total = Some(10), failed = Some(1), userId = "USER_ID")

        await(bulkCalculationRepository.insertBulkDocument(request).map {
          insert => {

            val result = bulkCalculationRepository.findSummaryByReference(request.uploadReference)
            val complete = await(result)
            complete must not be (None)
            complete.get.failed.get must be(1)
          }
        })

      }

      "return None when mongo find returns error" in {

        val mockIndexesManager = mock[CollectionIndexesManager]

        when(mockCollection.indexesManager).thenReturn(mockIndexesManager)
        when(mockCollection.find(Matchers.any())(Matchers.any())).thenThrow(new RuntimeException)
        when(mockCollection.indexesManager.create(Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(false,0,0,Nil,Nil,None,None,None)))

        val testRepository = new TestCalculationRepository

        val request = BulkCalculationRequest(None,"ur", "jimemail", "thing", Nil, "", LocalDateTime.now(), Some(false), None, None)

        val found = await(testRepository.findSummaryByReference(request.uploadReference))
        found must be(None)
      }
    }

    "find documents that need to be completed and mark them as complete" must {
      "return true if found documents and complete them" in {
        when(mockAuditConnector.sendEvent(Matchers.any())(Matchers.any(), Matchers.any())).thenReturn(Future.successful(AuditResult.Success))
        val request = jsonWithResponses.as[BulkCalculationRequest]
        val uploadRef = UUID.randomUUID().toString
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = uploadRef)))

        when(mockEmailConnector.sendProcessedTemplatedEmail(Matchers.any())(Matchers.any())).thenReturn(Future.successful(true))

        val result = await(bulkCalculationRepository.findAndComplete())
        result must be(true)

        val found = await(bulkCalculationRepository.findByReference(uploadRef))
        found.isDefined must be(true)
        found.get.complete must be(Some(true))
        found.get.total.get must be(4)
        found.get.failed.get must be(3)
      }

      "return true if found documents with only validation errors and complete them" in {
        when(mockAuditConnector.sendEvent(Matchers.any())(Matchers.any(), Matchers.any())).thenReturn(Future.successful(AuditResult.Success))
        val request = jsonWithValidationErrors.as[BulkCalculationRequest]
        val uploadRef = UUID.randomUUID().toString
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = uploadRef)))

        when(mockEmailConnector.sendProcessedTemplatedEmail(Matchers.any())(Matchers.any())).thenReturn(Future.successful(true))

        val result = await(bulkCalculationRepository.findAndComplete())
        result must be(true)

        val found = await(bulkCalculationRepository.findByReference(uploadRef))
        found.isDefined must be(true)
        found.get.complete must be(Some(true))
        found.get.total.get must be(1)
        found.get.failed.get must be(1)
      }

      "return None if documents not ready" in {
        when(mockAuditConnector.sendEvent(Matchers.any())(Matchers.any(), Matchers.any())).thenReturn(Future.successful(AuditResult.Success))
        val request = jsonWithResponses.as[BulkCalculationRequest]
        val uploadRef = UUID.randomUUID().toString
        val cr = request.calculationRequests.head.copy(calculationResponse = None)
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = uploadRef,calculationRequests = List(cr))))

        when(mockEmailConnector.sendProcessedTemplatedEmail(Matchers.any())(Matchers.any())).thenReturn(Future.successful(true))

        val result = await(bulkCalculationRepository.findAndComplete())
        result must be(true)

        val found = await(bulkCalculationRepository.findByReference(uploadRef))
        found.isDefined must be(true)
        found.get.complete must be(None)
      }

      "return false if it fails" in {
        when(mockAuditConnector.sendEvent(Matchers.any())(Matchers.any(), Matchers.any())).thenReturn(Future.successful(AuditResult.Success))
        val mockIndexesManager = mock[CollectionIndexesManager]

        when(mockCollection.indexesManager).thenReturn(mockIndexesManager)
        when(mockCollection.find(Matchers.any())(Matchers.any())).thenThrow(new RuntimeException)
        when(mockCollection.indexesManager.create(Matchers.any())).thenReturn(Future.successful(UpdateWriteResult(false,0,0,Nil,Nil,None,None,None)))

        val testRepository = new TestCalculationRepository

        val found = await(testRepository.findAndComplete())
        found must be(false)
      }

      "handle audit failure" in {
        when(mockAuditConnector.sendEvent(Matchers.any())(Matchers.any(), Matchers.any())).thenReturn(Future.failed(new Exception()))
        when(mockEmailConnector.sendProcessedTemplatedEmail(Matchers.any())(Matchers.any())).thenReturn(Future.successful(true))
        val request = jsonWithResponses.as[BulkCalculationRequest]
        val uploadRef = UUID.randomUUID().toString
        await(bulkCalculationRepository.insertBulkDocument(request.copy(uploadReference = uploadRef)))

        val result = await(bulkCalculationRepository.findAndComplete())
        result must be(true)

        val found = await(bulkCalculationRepository.findByReference(uploadRef))
        found.isDefined must be(true)
        found.get.complete must be(Some(true))
        found.get.total.get must be(4)
        found.get.failed.get must be(3)
      }
    }
  }

  def setupFindFor[T](collection: JSONCollection, returns: Traversable[T])(implicit manifest: Manifest[T]) = {

    val queryBuilder = mock[JSONQueryBuilder]
    val cursor = mock[Cursor[T]]

    when(
      collection.find(Matchers.any[JsObject], Matchers.any())(Matchers.any(), Matchers.any())
    ) thenReturn queryBuilder

    when(
      queryBuilder.cursor[T](Matchers.any(), Matchers.any())(Matchers.any(), Matchers.any[ExecutionContext], Matchers.any())
    ) thenAnswer new Answer[Cursor[T]] {
      def answer(i: InvocationOnMock) = cursor
    }

    when(
      cursor.collect[Traversable](Matchers.anyInt(), Matchers.anyBoolean())(Matchers.any[CanBuildFrom[Traversable[_], T, Traversable[T]]], Matchers.any[ExecutionContext])
    ) thenReturn Future.successful(returns)

  }
}
