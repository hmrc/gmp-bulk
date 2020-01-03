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

package actors

import akka.actor._
import akka.contrib.throttle.Throttler.{SetTarget, _}
import akka.contrib.throttle.TimerBasedThrottler
import config.ApplicationConfiguration
import connectors.DesConnector
import javax.inject.{Inject, Singleton}
import metrics.ApplicationMetrics
import play.api.Logger
import repositories.{BulkCalculationMongoRepository, BulkCalculationRepository}
import uk.gov.hmrc.lock.{LockKeeper, LockMongoRepository, LockRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ProcessingSupervisor @Inject()(applicationConfig: ApplicationConfiguration,
                                     bulkCalculationMongoRepository : BulkCalculationMongoRepository,
                                     mongoApi : play.modules.reactivemongo.ReactiveMongoComponent,
                                     desConnector : DesConnector,
                                     metrics : ApplicationMetrics) extends Actor with ActorUtils {

  val lockrepo = LockMongoRepository(mongoApi.mongoConnector.db)

  val lockKeeper = new LockKeeper {

    override def repo: LockRepository = lockrepo //The repo created before

    override def lockId: String = "bulkprocessing"

    override val forceLockReleaseAfter: org.joda.time.Duration = org.joda.time.Duration.standardMinutes(5)

    // $COVERAGE-OFF$
    override def tryLock[T](body: => Future[T])(implicit ec : ExecutionContext): Future[Option[T]] = {
      repo.lock(lockId, serverId, forceLockReleaseAfter)
        .flatMap { acquired =>
          if (acquired) { body.map { case x => Some(x) } }
          else Future.successful(None)
        }.recoverWith { case ex => repo.releaseLock(lockId, serverId).flatMap(_ => Future.failed(ex)) }
    }
    // $COVERAGE-ON$
  }
  // $COVERAGE-OFF$
  lazy val repository: BulkCalculationRepository = bulkCalculationMongoRepository
  lazy val requestActor: ActorRef = context.actorOf(Props(classOf[DefaultCalculationRequestActor], bulkCalculationMongoRepository, desConnector, metrics ), "calculation-requester")

  lazy val throttler: ActorRef = context.actorOf(Props(classOf[TimerBasedThrottler],
    applicationConfig.bulkProcessingTps msgsPer 1.seconds), "throttler")

  throttler ! SetTarget(Some(requestActor))

  // $COVERAGE-ON$

  override def receive: Receive = {

    case STOP => {
      Logger.debug("[ProcessingSupervisor] received while not processing: STOP received")
      lockrepo.releaseLock(lockKeeper.lockId,lockKeeper.serverId)
    }

    case START => {

      lockKeeper.tryLock {
        context become receiveWhenProcessRunning
        Logger.debug("Starting Processing")

        repository.findRequestsToProcess().map {
          case Some(requests) if requests.nonEmpty => {
            Logger.debug(s"[ProcessingSupervisor][receive] took ${requests.size} request/s")
            for (request <- requests.take(applicationConfig.bulkProcessingBatchSize)) {

              throttler ! request
            }
            throttler ! STOP

          }
          case _ => {

            Logger.debug(s"[ProcessingSupervisor][receive] no requests pending")
            context unbecome;
            throttler ! STOP

          }

        }
      }.map{
        case Some(thing) => Logger.debug(s"[ProcessingSupervisor][receive] obtained mongo lock")
        // $COVERAGE-OFF$
        case _ => Logger.debug(s"[ProcessingSupervisor][receive] failed to obtain mongo lock")
        // $COVERAGE-ON$
      }

    }
  }

  def receiveWhenProcessRunning : Receive = {
    // $COVERAGE-OFF$
    case START => Logger.debug("[ProcessingSupervisor][received while processing] START ignored")
    // $COVERAGE-ON$

    case STOP => {

      Logger.debug("[ProcessingSupervisor][received while processing] STOP received")
      lockrepo.releaseLock(lockKeeper.lockId,lockKeeper.serverId)
      context unbecome
    }
  }

}
