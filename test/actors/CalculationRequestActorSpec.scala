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

package actors

import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import config.{AppConfig, ApplicationConfiguration}
import connectors.{DesConnector, DesGetHiddenRecordResponse, DesGetSuccessResponse, HipConnector, IFConnector}
import helpers.RandomNino
import metrics.ApplicationMetrics
import models._
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers
import repositories.BulkCalculationMongoRepository
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.language.postfixOps
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

class CalculationRequestActorMock(val desConnector: DesConnector,
                                  val ifConnector: IFConnector,
                                  val hipConnector: HipConnector,
                                  val repository: BulkCalculationMongoRepository,
                                  val metrics: ApplicationMetrics,
                                  val applicationConfig: ApplicationConfiguration,
                                  val appConfig: AppConfig)
                                 (implicit val ec: ExecutionContext)
  extends CalculationRequestActor with CalculationRequestActorComponent

class CalculationRequestActorSpec extends TestKit(ActorSystem("TestCalculationActorSystem")) with AnyWordSpecLike with MockitoSugar
  with BeforeAndAfterAll with DefaultTimeout with ImplicitSender with ActorUtils with BeforeAndAfter {

  val mockDesConnector = mock[DesConnector]
  val mockIFConnector = mock[IFConnector]
  val mockHipConnector = mock[HipConnector]
  val mockRepository = mock[BulkCalculationMongoRepository]
  val mockMetrics = mock[ApplicationMetrics]
  val mockApplicationConfig = mock[ApplicationConfiguration]
  val mockAppConfig = mock[AppConfig]

  val testTimeout = 10 seconds

  implicit val hc: HeaderCarrier = HeaderCarrier()

  before {
    reset(mockDesConnector)
    reset(mockIFConnector)
    reset(mockHipConnector)
    reset(mockRepository)
    reset(mockMetrics)
    reset(mockApplicationConfig)
    reset(mockAppConfig)
    when(mockDesConnector.getPersonDetails(ArgumentMatchers.any())).thenReturn(Future.successful(DesGetSuccessResponse))
  }

  override def afterAll(): Unit = {
    shutdown()
  }

  "Calculation Request Actor" when {

    val response = CalculationResponse("", 0, None, None, None, Scon("", 0, ""), Nil)
    val hipResponse = HipCalculationResponse(
      nationalInsuranceNumber = "AA123456A",
      schemeContractedOutNumberDetails = "S2730000T",
      payableAgeDate = None,
      statePensionAgeDate = None,
      dateOfDeath = None,
      GuaranteedMinimumPensionDetailsList = Nil
    )

    "hip is enabled" should {
      "successfully save" in {

        when(mockAppConfig.isIfsEnabled).thenReturn(false)
        when(mockAppConfig.isHipEnabled).thenReturn(true)

        when(mockHipConnector.calculateOutcome(ArgumentMatchers.eq("system"), ArgumentMatchers.any())(ArgumentMatchers.any[HeaderCarrier]()))
          .thenReturn(Future.successful(Right(hipResponse)))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsg(true)
        }

      }

      "insert a 422 HIP failure (Left) into DB and ack true" in {

        when(mockAppConfig.isIfsEnabled).thenReturn(false)
        when(mockAppConfig.isHipEnabled).thenReturn(true)

        val hipFailures = HipCalculationFailuresResponse(List(HipFailure("No Match for person details provided", 63119)))

        when(mockHipConnector.calculateOutcome(ArgumentMatchers.eq("system"), ArgumentMatchers.any())(ArgumentMatchers.any[HeaderCarrier]()))
          .thenReturn(Future.successful(Left(hipFailures)))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
          .thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {
          actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsg(true)
          verify(mockRepository).insertResponseByReference(ArgumentMatchers.eq("test"), ArgumentMatchers.eq(1), ArgumentMatchers.any())
        }
      }

      "insert a 503 response when HIP circuit breaker is open (BreakerException)" in {

        when(mockAppConfig.isIfsEnabled).thenReturn(false)
        when(mockAppConfig.isHipEnabled).thenReturn(true)

        // Simulate circuit breaker open via HipConnector inner BreakerException
        val breaker = new mockHipConnector.BreakerException
        when(mockHipConnector.calculateOutcome(ArgumentMatchers.eq("system"), ArgumentMatchers.any())(ArgumentMatchers.any[HeaderCarrier]()))
          .thenReturn(Future.failed(breaker))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {
          val req = Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None))
          actorRef ! ProcessReadyCalculationRequest("test", 7, req, None, None)
          expectMsg(true)
          verify(mockRepository).insertResponseByReference("test", 7, GmpBulkCalculationResponse(List(), 503, None, None, None, containsErrors = true))
        }
      }

      "get failure when fails to send to HIP" in {

        when(mockAppConfig.isIfsEnabled).thenReturn(false)
        when(mockAppConfig.isHipEnabled).thenReturn(true)
        when(mockHipConnector.calculateOutcome(ArgumentMatchers.eq("system"), ArgumentMatchers.any())(ArgumentMatchers.any[HeaderCarrier]()))
          .thenReturn(Future.failed(new RuntimeException("The calculation failed")))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])

        }
      }

      "insert a failed response when a 400 code is returned from HIP" in {

        val ex = UpstreamErrorResponse("Call to Individual Pension calculation on NPS Service failed with status code 400", 400, 400)

        when(mockAppConfig.isIfsEnabled).thenReturn(false)
        when(mockAppConfig.isHipEnabled).thenReturn(true)
        when(mockHipConnector.calculateOutcome(ArgumentMatchers.eq("system"), ArgumentMatchers.any())(ArgumentMatchers.any[HeaderCarrier]()))
          .thenReturn(Future.failed(ex))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsg(true)

          verify(mockRepository).insertResponseByReference("test", 1, GmpBulkCalculationResponse(List(), 400, None, None, None, containsErrors = true))
        }
      }

      "insert a failed response when a 500 code is returned from HIP" in {
        val exObj = UpstreamErrorResponse("Call to Individual Pension calculation on NPS Service failed with status code 500", 500, 500)

        when(mockDesConnector.getPersonDetails(ArgumentMatchers.any())).thenReturn(Future.successful(DesGetSuccessResponse))
        when(mockAppConfig.isIfsEnabled).thenReturn(false)
        when(mockAppConfig.isHipEnabled).thenReturn(true)
        when(mockHipConnector.calculateOutcome(ArgumentMatchers.eq("system"), ArgumentMatchers.any())(ArgumentMatchers.any[HeaderCarrier]()))
          .thenReturn(Future.failed(exObj))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsg(true)
          verify(mockRepository).insertResponseByReference("test", 1, GmpBulkCalculationResponse(List(), 500, None, None, None, containsErrors = true))
        }

      }

      "insert a failed response when a 403 code is returned from HIP" in {
        val exObj = UpstreamErrorResponse("Forbidden", 403, 403)

        when(mockAppConfig.isIfsEnabled).thenReturn(false)
        when(mockAppConfig.isHipEnabled).thenReturn(true)
        when(mockHipConnector.calculateOutcome(ArgumentMatchers.eq("system"), ArgumentMatchers.any())(ArgumentMatchers.any[HeaderCarrier]()))
          .thenReturn(Future.failed(exObj))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {
          actorRef ! ProcessReadyCalculationRequest("test", 2, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsg(true)
          verify(mockRepository).insertResponseByReference("test", 2, GmpBulkCalculationResponse(List(), 403, None, None, None, containsErrors = true))
        }
      }

      "insert a failed response when a 404 code is returned from HIP" in {
        val exObj = UpstreamErrorResponse("Not Found", 404, 404)

        when(mockAppConfig.isIfsEnabled).thenReturn(false)
        when(mockAppConfig.isHipEnabled).thenReturn(true)
        when(mockHipConnector.calculateOutcome(ArgumentMatchers.eq("system"), ArgumentMatchers.any())(ArgumentMatchers.any[HeaderCarrier]()))
          .thenReturn(Future.failed(exObj))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {
          actorRef ! ProcessReadyCalculationRequest("test", 3, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsg(true)
          verify(mockRepository).insertResponseByReference("test", 3, GmpBulkCalculationResponse(List(), 404, None, None, None, containsErrors = true))
        }
      }
    }


    "if is enabled" should {
      "successfully save" in {

        when(mockAppConfig.isIfsEnabled).thenReturn(true)
        when(mockIFConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.successful(response))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsg(true)
        }

      }

      "get failure when fails to send to DES" in {

        when(mockAppConfig.isIfsEnabled).thenReturn(true)
        when(mockIFConnector.calculate(ArgumentMatchers.any())).thenThrow(new RuntimeException("The calculation failed"))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])

        }

      }

      "insert a failed response when a 400 code is returned from DES" in {

        val ex = UpstreamErrorResponse("Call to Individual Pension calculation on NPS Service failed with status code 400", 400, 400)

        when(mockAppConfig.isIfsEnabled).thenReturn(true)
        when(mockIFConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.failed(ex))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsg(true)

          verify(mockRepository).insertResponseByReference("test", 1, GmpBulkCalculationResponse(List(), 400, None, None, None, containsErrors = true))
        }
      }

      "insert a failed response when a 500 code is returned from IF" in {
        val exObj = UpstreamErrorResponse("Call to Individual Pension calculation on NPS Service failed with status code 500", 500, 500)

        when(mockDesConnector.getPersonDetails(ArgumentMatchers.any())) thenReturn Future.successful(DesGetSuccessResponse)
        when(mockAppConfig.isIfsEnabled).thenReturn(true)
        when(mockIFConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.failed(exObj))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! ProcessReadyCalculationRequest("test", 10, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])
        }

      }

      "insert a failed response when a 503 code is returned from IF" in {
        val exObj = UpstreamErrorResponse("Service Unavailable", 503, 503)

        when(mockDesConnector.getPersonDetails(ArgumentMatchers.any())) thenReturn Future.successful(DesGetSuccessResponse)
        when(mockAppConfig.isIfsEnabled).thenReturn(true)
        when(mockIFConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.failed(exObj))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {
          actorRef ! ProcessReadyCalculationRequest("test", 11, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])
        }
      }

      "return a failure when a 500 code is returned from IF" in {
        val exObj = UpstreamErrorResponse("Internal Server Error", 500, 500)

        when(mockDesConnector.getPersonDetails(ArgumentMatchers.any())) thenReturn Future.successful(DesGetSuccessResponse)
        when(mockAppConfig.isIfsEnabled).thenReturn(true)
        when(mockIFConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.failed(exObj))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {
          actorRef ! ProcessReadyCalculationRequest("test", 12, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])
        }
      }

      "return a failure for other error codes from IF" in {
        val exObj = UpstreamErrorResponse("Not Found", 404, 404)

        when(mockDesConnector.getPersonDetails(ArgumentMatchers.any())) thenReturn Future.successful(DesGetSuccessResponse)
        when(mockAppConfig.isIfsEnabled).thenReturn(true)
        when(mockIFConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.failed(exObj))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {
          actorRef ! ProcessReadyCalculationRequest("test", 13, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])
        }
      }
    }

    "if is disabled" should {
      "successfully save" in {

        when(mockAppConfig.isIfsEnabled).thenReturn(false)
        when(mockDesConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.successful(response))
        when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsg(true)
        }

      }

      "get failure when fails to send to DES" in {

        when(mockAppConfig.isIfsEnabled).thenReturn(false)
        when(mockDesConnector.calculate(ArgumentMatchers.any())).thenThrow(new RuntimeException("The calculation failed"))

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
          expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])

        }

      }

      "insert a failed response when a 400 code is returned from DES" in {

          val ex = UpstreamErrorResponse("Call to Individual Pension calculation on NPS Service failed with status code 400", 400, 400)

          when(mockAppConfig.isIfsEnabled).thenReturn(false)
          when(mockDesConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.failed(ex))
          when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

          val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

          within(testTimeout) {

            actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
            expectMsg(true)

            verify(mockRepository).insertResponseByReference("test", 1, GmpBulkCalculationResponse(List(), 400, None, None, None, containsErrors = true))
          }

        }

        "insert a 423 failed response when a 423 code is returned from DES" in {

          val nino = "ST281614D"
          when(mockDesConnector.getPersonDetails(ArgumentMatchers.eq(nino))).thenReturn(Future.successful(DesGetHiddenRecordResponse))
          when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

          val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

          within(testTimeout) {

            actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, None, None, None, None, None)), None, None)
            expectMsg(true)

            verify(mockRepository).insertResponseByReference("test", 1, GmpBulkCalculationResponse(List(), 423, None, None, None, containsErrors = true))
            verify(mockDesConnector).getPersonDetails(ArgumentMatchers.eq(nino))
            verify(mockDesConnector, times(0)).calculate(ArgumentMatchers.any[ValidCalculationRequest])
          }

        }

        "insert a failed response when a 500 code is returned from DES" in {
          val exObj = UpstreamErrorResponse("Call to Individual Pension calculation on NPS Service failed with status code 500", 500, 500)

          when(mockDesConnector.getPersonDetails(ArgumentMatchers.any())) thenReturn Future.successful(DesGetSuccessResponse)
          when(mockAppConfig.isIfsEnabled).thenReturn(false)
          when(mockDesConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.failed(exObj))
          when(mockRepository.insertResponseByReference(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(true))

          val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

          within(testTimeout) {
            actorRef ! ProcessReadyCalculationRequest("test", 99, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
            expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])
          }
        }

        "return a failure for 500 from DES" in {
          val exObj = UpstreamErrorResponse("Internal Server Error", 500, 500)

          when(mockDesConnector.getPersonDetails(ArgumentMatchers.any())) thenReturn Future.successful(DesGetSuccessResponse)
          when(mockAppConfig.isIfsEnabled).thenReturn(false)
          when(mockDesConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.failed(exObj))

          val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

          within(testTimeout) {
            actorRef ! ProcessReadyCalculationRequest("test", 100, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
            expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])
          }
        }

        "return a failure for 503 from DES" in {
          val exObj = UpstreamErrorResponse("Service Unavailable", 503, 503)

          when(mockDesConnector.getPersonDetails(ArgumentMatchers.any())) thenReturn Future.successful(DesGetSuccessResponse)
          when(mockAppConfig.isIfsEnabled).thenReturn(false)
          when(mockDesConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.failed(exObj))

          val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

          within(testTimeout) {
            actorRef ! ProcessReadyCalculationRequest("test", 101, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
            expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])
          }
        }

        "return a failure for other error codes from DES" in {
          val exObj = UpstreamErrorResponse("Not Found", 404, 404)

          when(mockDesConnector.getPersonDetails(ArgumentMatchers.any())) thenReturn Future.successful(DesGetSuccessResponse)
          when(mockAppConfig.isIfsEnabled).thenReturn(false)
          when(mockDesConnector.calculate(ArgumentMatchers.any())).thenReturn(Future.failed(exObj))

          val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

          within(testTimeout) {
            actorRef ! ProcessReadyCalculationRequest("test", 101, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
            expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])
          }
        }
      }

      "the message is the wrong type should get failure" in {

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! "purple rain"
          expectMsgClass(classOf[org.apache.pekko.actor.Status.Failure])
        }
      }

      "a STOP message is recieved should send STOP message to sender" in {

        val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockIFConnector, mockHipConnector, mockMetrics, mockApplicationConfig, mockAppConfig, system.dispatcher))

        within(testTimeout) {

          actorRef ! STOP
          expectMsg(STOP)
        }
      }

    }

  }