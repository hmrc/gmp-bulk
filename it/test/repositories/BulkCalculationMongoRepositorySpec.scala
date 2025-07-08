/*
 * Copyright 2024 HM Revenue & Customs
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

import org.mockito.Mockito.when
import org.scalatest.OptionValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.PlayMongoRepositorySupport
import config.ApplicationConfiguration
import connectors.EmailConnector
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import metrics.ApplicationMetrics
import models._
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import scala.concurrent.Future
import java.time.{Clock, Instant, ZoneId, LocalDateTime}
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.mockito.ArgumentMatchers.{any, anyString}
import uk.gov.hmrc.play.audit.http.connector.AuditResult

class BulkCalculationMongoRepositorySpec
  extends AnyFreeSpec
    with Matchers
    with PlayMongoRepositorySupport[BulkCalculationRequest]
    with ScalaFutures
    with IntegrationPatience
    with OptionValues
    with MockitoSugar
    with BeforeAndAfterEach
    with BeforeAndAfterAll {

  private val instant = Instant.now.truncatedTo(ChronoUnit.MILLIS)
  private val localDateTime = LocalDateTime.now()

  private val applicationConfiguration = mock[ApplicationConfiguration]
  when(applicationConfiguration.bulkProcessingBatchSize) thenReturn 100
  when(applicationConfiguration.bulkCompletingBatchSize) thenReturn 1

  private val emailConnector = mock[EmailConnector]
  private val auditConnector = mock[AuditConnector]
  when(auditConnector.sendEvent(any())(any(), any())) thenReturn Future.successful(AuditResult.Success)
  private val metrics = mock[ApplicationMetrics]

  protected override val repository = new BulkCalculationMongoRepository(
    metrics = metrics,
    ac = auditConnector,
    emailConnector = emailConnector,
    applicationConfiguration = applicationConfiguration,
    mongo = mongoComponent
  )

  override def beforeEach(): Unit = {
    deleteAll().futureValue
  }

  override def afterAll(): Unit = {
    deleteAll().futureValue
  }

  "findAndComplete" - {

    "must retrieve all incomplete bulks and complete them" in {
      (0 until 9).foreach(int => generateBulk(int.toString))

      val numberOfIncompleteDocumentsBefore = repository.findIncompleteBulk().futureValue
      numberOfIncompleteDocumentsBefore.length mustEqual 9

      repository.findAndComplete().futureValue

      val numberOfIncompleteDocumentsAfter = repository.findIncompleteBulk().futureValue
      numberOfIncompleteDocumentsAfter.length mustEqual 0
    }

  }

  def generateBulk(id: String): Unit = {
    val validationRequest = ValidCalculationRequest("S1401234Q", id, "some", "thing", None, Some(12), None, None, None, None)
    val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List.empty, 0, None, None, None)
    val children = List(
      CalculationRequest(bulkId = Some(id),
        lineId = 1,
        validCalculationRequest = Some(validationRequest),
        validationErrors = None,
        calculationResponse = Some(gmpBulkCalculationResponse)
      )
    )
    val testBulk = BulkCalculationRequest(_id = Some(id),
      uploadReference = id,
      email = "something",
      reference = id,
      calculationRequests = children,
      userId = "user",
      timestamp = localDateTime,
      complete = Some(false),
      total = Some(1),
      failed = Some(0))
    repository.insertBulkDocument(testBulk).futureValue
  }
}
