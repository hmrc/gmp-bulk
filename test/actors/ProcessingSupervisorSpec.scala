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

import actors.Throttler.SetTarget
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.testkit._
import config.ApplicationConfiguration
import connectors.{DesConnector, HipConnector, IFConnector}
import helpers.RandomNino
import metrics.ApplicationMetrics
import models.{ProcessReadyCalculationRequest, ValidCalculationRequest}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito._
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import repositories.BulkCalculationMongoRepository
import uk.gov.hmrc.mongo.lock.{Lock, MongoLockRepository, TimePeriodLockService}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps



class ProcessingSupervisorSpec extends TestKit(ActorSystem("TestProcessingSystem")) with AnyWordSpecLike with MockitoSugar
  with BeforeAndAfterAll with DefaultTimeout with ImplicitSender with ActorUtils {

  def additionalConfiguration: Map[String, String] = Map( "logger.application" -> "ERROR",
    "logger.play" -> "ERROR",
    "logger.root" -> "ERROR",
    "org.apache.logging" -> "ERROR",
    "com.codahale" -> "ERROR")

  val applicationConfig  = mock[ApplicationConfiguration]
  val mongoApi  = mock[MongoLockRepository]
  val desConnector = mock[DesConnector]
  val ifConnector = mock[IFConnector]
  val metrics = mock[ApplicationMetrics]
  val hipConnector = mock[HipConnector]
  val mockRepository = mock[BulkCalculationMongoRepository]


  override def beforeAll(): Unit = {
    when(applicationConfig.bulkProcessingBatchSize).thenReturn(1)
    when(mongoApi.refreshExpiry(anyString(), anyString(), any())).thenReturn(Future(true))
    when(mongoApi.takeLock(anyString(),anyString(), any()))
      .thenReturn(Future(Some(Lock("id", "me", Instant.now().minusSeconds(100), Instant.now().plusSeconds(100)))))
  }

  override def afterAll(): Unit = {
    shutdown()
  }

  "processing supervisor" must {
// This test has been commented out due to randomly failing


//    "send requests to throttler" in {
//
//      lazy val throttlerProbe = TestProbe()
//      lazy val calculationActorProbe = TestProbe()
//
//      lazy val processingSupervisor = TestActorRef(Props(new ProcessingSupervisor(applicationConfig, mockRepository, mongoApi, desConnector, metrics) {
//        override lazy val throttler = throttlerProbe.ref
//        override lazy val requestActor = calculationActorProbe.ref
//        override lazy val repository = mockRepository
//      }),"process-supervisor")
//
//
//      val processReadyCalculationRequest = ProcessReadyCalculationRequest("test upload",1,
//        Some(ValidCalculationRequest("S2730000B",RandomNino.generate,"smith","jim",None,None,None,None,None,None)), None, None)
//
//      when(mockRepository.findRequestsToProcess()).thenReturn(Future.successful(Some(List(processReadyCalculationRequest))))
//      within(20 seconds) {
//        processingSupervisor ! START
//        processingSupervisor ! START
//
//        throttlerProbe.expectMsgClass(classOf[SetTarget])
//        throttlerProbe.expectMsg(processReadyCalculationRequest)
//        throttlerProbe.expectMsg(15 seconds, STOP)
//       processingSupervisor ! STOP // simulate stop coming from calc requestor
//
//
//      }
//
//    }

    "send request to start with no requests queued" in {

      val throttlerProbe = TestProbe()
      val calculationActorProbe = TestProbe()

      val processingSupervisor = TestActorRef(Props(new ProcessingSupervisor(applicationConfig, mockRepository, mongoApi, desConnector, ifConnector,hipConnector,metrics) {
        override lazy val throttler = throttlerProbe.ref
        override lazy val requestActor = calculationActorProbe.ref
        override lazy val repository = mockRepository
      }),"process-supervisor2")
      when(mockRepository.findRequestsToProcess()).thenReturn(Future.successful(Some(Nil)))

      within(5 seconds) {
        processingSupervisor ! START
        throttlerProbe.expectMsgClass(classOf[SetTarget])
        throttlerProbe.expectMsg(STOP)
        processingSupervisor ! STOP // simulate stop coming from calc requestor
      }

    }

    "start processing and then stop when finished" in {

      val throttlerProbe = TestProbe()
      val calculationActorProbe = TestProbe()

      val processingSupervisor = TestActorRef(Props(new ProcessingSupervisor(applicationConfig, mockRepository, mongoApi, desConnector, ifConnector, hipConnector,metrics) {

        override lazy val throttler = throttlerProbe.ref
        override lazy val requestActor = calculationActorProbe.ref
        override lazy val repository = mockRepository
      }),"process-supervisor3")

      val processReadyCalculationRequest = ProcessReadyCalculationRequest("test upload",1,
        Some(ValidCalculationRequest("S2730000B",RandomNino.generate,"smith","jim",None,None,None,None,None,None)), None, None)
      when(mockRepository.findRequestsToProcess()).thenReturn(Future.successful(Some(List(processReadyCalculationRequest))))

      within(5 seconds) {

        processingSupervisor ! START
        processingSupervisor ! STOP // simulate stop coming from calc requestor

        throttlerProbe.expectMsgClass(classOf[SetTarget])
        throttlerProbe.expectMsg(processReadyCalculationRequest)
        throttlerProbe.expectMsg(STOP)
        processingSupervisor ! STOP // simulate stop coming from calc requestor
      }
    }
  }

}
