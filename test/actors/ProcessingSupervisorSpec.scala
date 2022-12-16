/*
 * Copyright 2022 HM Revenue & Customs
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
import akka.actor.{ActorSystem, Props}
import akka.testkit._
import com.kenshoo.play.metrics.PlayModule
import config.ApplicationConfiguration
import connectors.DesConnector
import helpers.RandomNino
import metrics.ApplicationMetrics
import models.{ProcessReadyCalculationRequest, ValidCalculationRequest}
import org.mockito.Matchers.{any, anyString}
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModule}
import play.api.{Application, Mode}
import repositories.{BulkCalculationMongoRepository, LockClient}
import uk.gov.hmrc.mongo.lock.{LockRepository, MongoLockRepository}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global



class ProcessingSupervisorSpec extends TestKit(ActorSystem("TestProcessingSystem")) with WordSpecLike with MockitoSugar
  with BeforeAndAfterAll with DefaultTimeout with ImplicitSender with ActorUtils {

  def additionalConfiguration: Map[String, String] = Map( "logger.application" -> "ERROR",
    "logger.play" -> "ERROR",
    "logger.root" -> "ERROR",
    "org.apache.logging" -> "ERROR",
    "com.codahale" -> "ERROR")

  val applicationConfig  = mock[ApplicationConfiguration]
  val mongoApi  = mock[MongoLockRepository]
  val desConnector = mock[DesConnector]
  val metrics = mock[ApplicationMetrics]
  val mockRepository = mock[BulkCalculationMongoRepository]

  val mockLockRepo = LockClient(mongoApi, "bulkprocessing", 5.minutes)



  override def beforeAll:Unit = {
    when(applicationConfig.bulkProcessingBatchSize).thenReturn(1)
    when(mongoApi.releaseLock(anyString(), anyString())).thenReturn(Future(())).thenReturn(Future(())).thenReturn(Future(())).thenReturn(Future(()))
    when(mongoApi.takeLock(anyString(),anyString(), any())).thenReturn(Future(true))
  }

  override def afterAll: Unit = {
    shutdown()
  }

  "processing supervisor" must {

    "send requests to throttler" in {

      lazy val throttlerProbe = TestProbe()
      lazy val calculationActorProbe = TestProbe()

      lazy val processingSupervisor = TestActorRef(Props(new ProcessingSupervisor(applicationConfig, mockRepository, mongoApi, desConnector, metrics) {
        override lazy val throttler = throttlerProbe.ref
        override lazy val requestActor = calculationActorProbe.ref
        override lazy val repository = mockRepository
        override val lockClient = mockLockRepo
      }),"process-supervisor")


      val processReadyCalculationRequest = ProcessReadyCalculationRequest("test upload",1,
        Some(ValidCalculationRequest("S2730000B",RandomNino.generate,"smith","jim",None,None,None,None,None,None)), None, None)

      when(mockRepository.findRequestsToProcess()).thenReturn(Future.successful(Some(List(processReadyCalculationRequest))))
      within(20 seconds) {
        processingSupervisor ! START
        processingSupervisor ! START

        throttlerProbe.expectMsgClass(classOf[SetTarget])
        throttlerProbe.expectMsg(processReadyCalculationRequest)
        throttlerProbe.expectMsg(15 seconds, STOP)
       processingSupervisor ! STOP // simulate stop coming from calc requestor


      }

    }

    "send request to start with no requests queued" in {

      val throttlerProbe = TestProbe()
      val calculationActorProbe = TestProbe()

      val processingSupervisor = TestActorRef(Props(new ProcessingSupervisor(applicationConfig, mockRepository, mongoApi, desConnector, metrics) {
        override lazy val throttler = throttlerProbe.ref
        override lazy val requestActor = calculationActorProbe.ref
        override lazy val repository = mockRepository
        override val lockClient = mockLockRepo
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

      val processingSupervisor = TestActorRef(Props(new ProcessingSupervisor(applicationConfig, mockRepository, mongoApi, desConnector, metrics) {

        override lazy val throttler = throttlerProbe.ref
        override lazy val requestActor = calculationActorProbe.ref
        override lazy val repository = mockRepository
        override val lockClient = mockLockRepo
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
