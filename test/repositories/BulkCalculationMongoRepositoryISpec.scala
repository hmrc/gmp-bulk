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

package repositories

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, MongoSupport}
import org.mongodb.scala.*
import uk.gov.hmrc.mongo.MongoComponent

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import metrics.ApplicationMetrics
import connectors.{EmailConnector, ProcessedUploadTemplate}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.http.HeaderCarrier
import models.*

import java.time.LocalDateTime

class BulkCalculationMongoRepositoryISpec
  extends AnyWordSpec
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with CleanMongoCollectionSupport
    with MongoSupport
    with BeforeAndAfterEach {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(50, Millis))

  override def beforeEach(): Unit = {
    super.beforeEach()
    // Clean the single collection used by repository
    // Repository uses collectionName = "bulk-calculation"
    mongoComponent.database.getCollection("bulk-calculation").drop().toFuture().futureValue
  }

  private val metrics: ApplicationMetrics = mock[ApplicationMetrics]
  private val audit: AuditConnector = mock[AuditConnector]
  private val email: EmailConnector = mock[EmailConnector]
  private val appConf: config.ApplicationConfiguration = mock[config.ApplicationConfiguration]

  private def newRepo()(implicit ec: ExecutionContext): BulkCalculationMongoRepository = {
    when(appConf.bulkProcessingBatchSize).thenReturn(50)
    new BulkCalculationMongoRepository(metrics, audit, email, appConf, mongoComponent, ec)
  }

  private def mkBulk(uploadRef: String, withResponses: Boolean = false): BulkCalculationRequest = {
    val calcResp = if (withResponses) Some(GmpBulkCalculationResponse(Nil, 0, None, None, None, containsErrors = false)) else None
    BulkCalculationRequest(
      _id = None,
      uploadReference = uploadRef,
      email = "user@test.com",
      reference = "ref-1",
      calculationRequests = List(
        CalculationRequest(None, 1, Some(ValidCalculationRequest("S1401234Q", "AA111111A", "Smith", "Bill", None, Some(1), None, None, None, None)), None, calcResp),
        CalculationRequest(None, 2, Some(ValidCalculationRequest("S1401234Q", "AA111111A", "Smith", "Bill", None, Some(1), None, None, None, None)), None, None)
      ),
      userId = "user-1",
      timestamp = LocalDateTime.now(),
      complete = Some(false),
      total = Some(0),
      failed = Some(0)
    )
  }

  "BulkCalculationMongoRepository.insertBulkDocument" should {
    "insert parent and children and return true, then prevent duplicate by uploadReference" in {
      val repo = newRepo()

      val bulk = mkBulk("upl-1")
      whenReady(repo.insertBulkDocument(bulk)) { res => res mustBe true }

      // duplicate
      whenReady(repo.insertBulkDocument(bulk)) { res => res mustBe false }

      // findByReference should return the processed bulk with children
      whenReady(repo.findByReference("upl-1")) { opt =>
        opt.isDefined mustBe true
        val processed = opt.get
        processed._id.length must be > 0
        processed.calculationRequests.size mustBe 2
      }
    }
  }

  "BulkCalculationMongoRepository.insertResponseByReference" should {
    "update a child with a response and mark hasResponse true" in {
      val repo = newRepo()
      val bulk = mkBulk("upl-2")
      val inserted = repo.insertBulkDocument(bulk).futureValue
      inserted mustBe true

      // Load the processed bulk to get generated bulkId
      val processed = repo.findByReference("upl-2").futureValue.get
      val bulkId = processed._id

      val resp = GmpBulkCalculationResponse(Nil, 400, None, None, None, containsErrors = true)
      val updated = repo.insertResponseByReference(bulkId, 2, resp).futureValue
      updated mustBe true

      val after = repo.findByReference("upl-2").futureValue.get
      val child2 = after.calculationRequests.find(_.lineId == 2).get
      child2.hasResponse mustBe true
      child2.calculationResponse.isDefined mustBe true
      child2.calculationResponse.get.containsErrors mustBe true
    }
  }

  "BulkCalculationMongoRepository.findByReference with CsvFilter" should {
    "return All, Failed and Successful subsets correctly" in {
      val repo = newRepo()
      val base = mkBulk("upl-3")

      val failedResp = Some(GmpBulkCalculationResponse(Nil, 400, None, None, None, containsErrors = true))
      val okResp = Some(GmpBulkCalculationResponse(Nil, 0, None, None, None, containsErrors = false))
      val enriched = base.copy(calculationRequests = List(
        base.calculationRequests.head.copy(lineId = 1, calculationResponse = okResp),                    // success
        base.calculationRequests(1).copy(lineId = 2, calculationResponse = failedResp),                  // failed (containsErrors)
        base.calculationRequests(1).copy(lineId = 3, validationErrors = Some(Map("nino" -> "bad")))      // failed (validationErrors)
      ))

      repo.insertBulkDocument(enriched).futureValue mustBe true

      val all = repo.findByReference("upl-3", CsvFilter.All).futureValue.get
      all.calculationRequests.map(_.lineId) mustBe List(1,2,3)

      val failed = repo.findByReference("upl-3", CsvFilter.Failed).futureValue.get
      failed.calculationRequests.map(_.lineId) mustBe List(2,3)

      val success = repo.findByReference("upl-3", CsvFilter.Successful).futureValue.get
      success.calculationRequests.map(_.lineId) mustBe List(1)
    }
  }

  "BulkCalculationMongoRepository.findSummaryByReference" should {
    "map parent document to summary and recover to None on failure" in {
      val repo = newRepo()
      repo.insertBulkDocument(mkBulk("upl-4")).futureValue mustBe true

      val summaryOpt = repo.findSummaryByReference("upl-4").futureValue
      summaryOpt.isDefined mustBe true
      val summary = summaryOpt.get
      summary.reference mustBe "ref-1"
      summary.userId mustBe "user-1"

      // Force failure by dropping collection and expect recover path => None
      mongoComponent.database.getCollection("bulk-calculation").drop().toFuture().futureValue
      val none = repo.findSummaryByReference("upl-4").futureValue
      none mustBe None
    }
  }

  "BulkCalculationMongoRepository.findRequestsToProcess" should {
    "return only process-ready children" in {
      val repo = newRepo()
      val base = mkBulk("upl-5", withResponses = false)
      repo.insertBulkDocument(base).futureValue mustBe true

      val maybe = repo.findRequestsToProcess().futureValue
      maybe.isDefined mustBe true
      val list = maybe.get
      list.nonEmpty mustBe true
      // all inserted children have isChild=true, hasValidRequest=true, hasResponse=false, hasValidationErrors=false
      list.map(_.lineId).toSet mustBe Set(1,2)
    }
  }

  "BulkCalculationMongoRepository.findAndComplete and findByUserId" should {
    "mark parent complete, send email & audit, and expose in previous requests" in {
      val repo = newRepo()

      // Insert bulk and mark both children as responded to make countChildDocWithValidRequest return 0
      val bulk = mkBulk("upl-6", withResponses = false)
      repo.insertBulkDocument(bulk).futureValue mustBe true
      val processed = repo.findByReference("upl-6").futureValue.get
      val bulkId = processed._id

      val ok = GmpBulkCalculationResponse(Nil, 0, None, None, None, containsErrors = false)
      val failed = GmpBulkCalculationResponse(Nil, 400, None, None, None, containsErrors = true)
      repo.insertResponseByReference(bulkId, 1, ok).futureValue mustBe true
      repo.insertResponseByReference(bulkId, 2, failed).futureValue mustBe true

      // Stub side-effects to succeed (avoid NPE on .map for audit)
      when(audit.sendEvent(ArgumentMatchers.any())(ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(scala.concurrent.Future.successful(AuditResult.Success))
      when(email.sendProcessedTemplatedEmail(ArgumentMatchers.any())(ArgumentMatchers.any()))
        .thenReturn(scala.concurrent.Future.successful(true))

      // Now run completion
      implicit val hc: HeaderCarrier = HeaderCarrier()
      repo.findAndComplete().futureValue mustBe true

      // Summary should be present and totals computed
      val summaryOpt = repo.findSummaryByReference("upl-6").futureValue
      summaryOpt.isDefined mustBe true
      val summary = summaryOpt.get
      summary.reference mustBe "ref-1"
      summary.userId mustBe "user-1"
      summary.total mustBe Some(2)
      summary.failed mustBe Some(1)

      // Appears in previous requests for the user
      val prev = repo.findByUserId("user-1").futureValue
      prev.isDefined mustBe true
      prev.get.exists(_.uploadReference == "upl-6") mustBe true

      // Email and audit should have been invoked at least once
      verify(email).sendProcessedTemplatedEmail(ArgumentMatchers.any[ProcessedUploadTemplate])(ArgumentMatchers.any())
      verify(audit).sendEvent(ArgumentMatchers.any())(ArgumentMatchers.any(), ArgumentMatchers.any())
    }
  }
}
