/*
 * Copyright 2018 HM Revenue & Customs
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
import akka.contrib.throttle.Throttler.SetTarget
import akka.testkit._
import helpers.RandomNino
import models.{ValidCalculationRequest, ProcessReadyCalculationRequest}
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll}
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.OneServerPerSuite
import reactivemongo.api.DefaultDB
import reactivemongo.play.json.collection.JSONCollection
import repositories.BulkCalculationRepository
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.play.test.UnitSpec
import org.mockito.Matchers._
import scala.concurrent.duration._
import scala.concurrent.Future
import play.api.{Application, Mode}
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModule}
import com.kenshoo.play.metrics.PlayModule

class ProcessingSupervisorSpec extends TestKit(ActorSystem("TestProcessingSystem")) with UnitSpec with MockitoSugar with OneServerPerSuite
  with BeforeAndAfterAll with DefaultTimeout with ImplicitSender with ActorUtils {

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

  val mockLockRepo = mock[LockRepository]

  override def beforeAll = {
    when(mockLockRepo.lock(anyString, anyString, any[org.joda.time.Duration])) thenReturn true
  }

  override def afterAll: Unit = {
    shutdown()
  }

  "processing supervisor" must {

    "send requests to throttler" in {

      val throttlerProbe = TestProbe()
      val calculationActorProbe = TestProbe()
      val mockRepository = mock[BulkCalculationRepository]

      val processingSupervisor = TestActorRef(Props(new ProcessingSupervisor {
        override lazy val throttler = throttlerProbe.ref
        override lazy val requestActor = calculationActorProbe.ref
        override lazy val repository = mockRepository
        override val lockrepo = mockLockRepo
      }),"process-supervisor")


      val processReadyCalculationRequest = ProcessReadyCalculationRequest("test upload",1,
        Some(ValidCalculationRequest("scon",RandomNino.generate,"smith","jim",None,None,None,None,None,None)), None, None)

      when(mockRepository.findRequestsToProcess()).thenReturn(Future.successful(Some(List(processReadyCalculationRequest))))

      within(5 seconds) {

        println("sending start")
        processingSupervisor ! START
        println("sending start again")
        processingSupervisor ! START

        throttlerProbe.expectMsgClass(classOf[SetTarget])
        throttlerProbe.expectMsg(processReadyCalculationRequest)
        throttlerProbe.expectMsg(STOP)
        processingSupervisor ! STOP // simulate stop coming from calc requestor

      }

    }

    "send request to start with no requests queued" in {

      val throttlerProbe = TestProbe()
      val calculationActorProbe = TestProbe()
      val mockRepository = mock[BulkCalculationRepository]

      val processingSupervisor = TestActorRef(Props(new ProcessingSupervisor {
        override lazy val throttler = throttlerProbe.ref
        override lazy val requestActor = calculationActorProbe.ref
        override lazy val repository = mockRepository
        override val lockrepo = mockLockRepo
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
      val mockRepository = mock[BulkCalculationRepository]

      val processingSupervisor = TestActorRef(Props(new ProcessingSupervisor {

        override lazy val throttler = throttlerProbe.ref
        override lazy val requestActor = calculationActorProbe.ref
        override lazy val repository = mockRepository
        override val lockrepo = mockLockRepo
      }),"process-supervisor3")

      val processReadyCalculationRequest = ProcessReadyCalculationRequest("test upload",1,
        Some(ValidCalculationRequest("scon",RandomNino.generate,"smith","jim",None,None,None,None,None,None)), None, None)
      
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
