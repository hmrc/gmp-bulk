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

package actors

import akka.actor.{ActorSystem, Props}
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import connectors.{DesConnector, DesGetHiddenRecordResponse, DesGetSuccessResponse}
import helpers.RandomNino
import metrics.ApplicationMetrics
import models._
import org.mockito.Matchers
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, WordSpecLike}
import repositories.BulkCalculationMongoRepository
import uk.gov.hmrc.http.UpstreamErrorResponse

import scala.language.postfixOps
import scala.concurrent.Future
import scala.concurrent.duration._

class CalculationRequestActorMock(val desConnector: DesConnector, val repository: BulkCalculationMongoRepository, val metrics: ApplicationMetrics)
  extends CalculationRequestActor with CalculationRequestActorComponent

class CalculationRequestActorSpec extends TestKit(ActorSystem("TestCalculationActorSystem")) with WordSpecLike with MockitoSugar
  with BeforeAndAfterAll with DefaultTimeout with ImplicitSender with ActorUtils with BeforeAndAfter {

  val mockDesConnector = mock[DesConnector]
  val mockRepository = mock[BulkCalculationMongoRepository]
  val mockMetrics = mock[ApplicationMetrics]

  val testTimeout = 10 seconds

  before {
    reset(mockDesConnector)
    reset(mockRepository)
    reset(mockMetrics)

    when(mockDesConnector.getPersonDetails(Matchers.any())) thenReturn Future.successful(DesGetSuccessResponse)
  }

  override def afterAll: Unit = {
    shutdown()
  }

  "Calculation Request Actor" must {

    val response = CalculationResponse("", 0, None, None, None, Scon("", 0, ""), Nil)

    "successfully save" in {

      when(mockDesConnector.calculate(Matchers.any())).thenReturn(Future.successful(response))
      when(mockRepository.insertResponseByReference(Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(true))

      val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockMetrics))

      within(testTimeout) {

        actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
        expectMsg(true)
      }

    }

    "get failure when fails to send to DES" in {

      when(mockDesConnector.calculate(Matchers.any())).thenThrow(new RuntimeException("The calculation failed"))

      val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockMetrics))

      within(testTimeout) {

        actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
        expectMsgClass(classOf[akka.actor.Status.Failure])

      }

    }

    "insert a failed response when a 400 code is returned from DES" in {

      val ex = UpstreamErrorResponse("Call to Individual Pension calculation on NPS Service failed with status code 400", 400, 400)

      when(mockDesConnector.calculate(Matchers.any())).thenReturn(Future.failed(ex))
      when(mockRepository.insertResponseByReference(Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(true))

      val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockMetrics))

      within(testTimeout) {

        actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
        expectMsg(true)

        verify(mockRepository).insertResponseByReference("test", 1, GmpBulkCalculationResponse(List(), 400, None, None, None, containsErrors = true))
      }

    }

    "insert a 423 failed response when a 423 code is returned from DES" in {

      val nino = "ST281614D"
      when(mockDesConnector.getPersonDetails(Matchers.eq(nino))).thenReturn(Future.successful(DesGetHiddenRecordResponse))
      when(mockRepository.insertResponseByReference(Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(true))

      val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockMetrics))

      within(testTimeout) {

        actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, None, None, None, None, None)), None, None)
        expectMsg(true)

        verify(mockRepository).insertResponseByReference("test", 1, GmpBulkCalculationResponse(List(), 423, None, None, None, containsErrors = true))
        verify(mockDesConnector).getPersonDetails(Matchers.eq(nino))
        verify(mockDesConnector, times(0)).calculate(Matchers.any[ValidCalculationRequest])
      }

    }

    "insert a failed response when a 500 code is returned from DES" in {
      val exObj = UpstreamErrorResponse("Call to Individual Pension calculation on NPS Service failed with status code 500", 500, 500)

      when(mockDesConnector.getPersonDetails(Matchers.any())) thenReturn Future.successful(DesGetSuccessResponse)
      when(mockDesConnector.calculate(Matchers.any())).thenReturn(Future.failed(exObj))
      when(mockRepository.insertResponseByReference(Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(true))

      val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockMetrics))

      within(testTimeout) {

        actorRef ! ProcessReadyCalculationRequest("test", 1, Some(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)), None, None)
        expectMsgClass(classOf[akka.actor.Status.Failure])

        //verify(mockRepository).insertResponseByReference("test", 1, GmpBulkCalculationResponse(List(), 500, None, None, None, containsErrors = true))
      }

    }

    "get failure when message wrong type" in {

      val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockMetrics))

      within(testTimeout) {

        actorRef ! "purple rain"
        expectMsgClass(classOf[akka.actor.Status.Failure])
      }
    }

    "send STOP message to sender when receive the STOP message" in {

      val actorRef = system.actorOf(Props(classOf[DefaultCalculationRequestActor], mockRepository, mockDesConnector, mockMetrics))

      within(testTimeout) {

        actorRef ! STOP
        expectMsg(STOP)
      }
    }

  }

}
