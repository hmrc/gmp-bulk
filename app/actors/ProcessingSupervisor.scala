/*
 * Copyright 2023 HM Revenue & Customs
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

import actors.Throttler.{RateInt, SetTarget}
import akka.actor._
import com.github.ghik.silencer.silent
import config.ApplicationConfiguration
import connectors.DesConnector
import metrics.ApplicationMetrics
import play.api.Logging
import repositories.{BulkCalculationMongoRepository, BulkCalculationRepository, LockClient}
import uk.gov.hmrc.mongo.lock.{LockRepository, MongoLockRepository, TimePeriodLockService}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

@silent
@Singleton
class ProcessingSupervisor @Inject()(applicationConfig: ApplicationConfiguration,
                                     bulkCalculationMongoRepository : BulkCalculationMongoRepository,
                                     val mongoLockRepository: MongoLockRepository,
                                     desConnector : DesConnector,
                                     metrics : ApplicationMetrics)
  extends Actor with ActorUtils with Logging with TimePeriodLockService with LockClient {

  override val lockRepository: LockRepository = mongoLockRepository
  override val lockId: String = "bulkprocessing"
  override val ttl: Duration = 45.minutes

  // $COVERAGE-OFF$
  lazy val repository: BulkCalculationRepository = bulkCalculationMongoRepository
  lazy val requestActor: ActorRef = context.actorOf(Props(classOf[DefaultCalculationRequestActor], bulkCalculationMongoRepository, desConnector, metrics ), "calculation-requester")

  lazy val throttler: ActorRef = context.actorOf(Props(classOf[TimerBasedThrottler],
    applicationConfig.bulkProcessingTps msgsPer 1.seconds), "throttler")

  throttler ! SetTarget(Some(requestActor))

  // $COVERAGE-ON$

  override def receive: Receive = {

    case STOP => logger.debug("[ProcessingSupervisor] received while not processing: STOP received")
      mongoLockRepository.releaseLock(lockId, ownerId)
    case START =>
      tryLock {
        context become receiveWhenProcessRunning
        logger.debug("Starting Processing")

        repository.findRequestsToProcess().map {

          case Some(requests) if requests.nonEmpty =>
            logger.debug(s"[ProcessingSupervisor][receive] took ${requests.size} request/s")
            for (request <- requests.take(applicationConfig.bulkProcessingBatchSize)) {
              throttler ! request
            }
            throttler ! STOP

          case _ =>
            logger.debug(s"[ProcessingSupervisor][receive] no requests pending")
            context unbecome;
            throttler ! STOP
        }
      }.map{
        case Some(res) => logger.debug(s"[ProcessingSupervisor][receive] Finished with $res. Mongo Lock has been renewed.")
        // $COVERAGE-OFF$
        case _ => logger.debug(s"[ProcessingSupervisor][receive] Failed to obtain mongo lock")
        // $COVERAGE-ON$
      }
  }

  def receiveWhenProcessRunning : Receive = {
    // $COVERAGE-OFF$
    case START => logger.debug("[ProcessingSupervisor][received while processing] START ignored")
    // $COVERAGE-ON$

    case STOP =>
      import scala.language.postfixOps
      logger.debug("[ProcessingSupervisor][received while processing] STOP received")
      mongoLockRepository.releaseLock(lockId, ownerId)
      context unbecome
  }

}
