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

import actors.Throttler.{RateInt, SetTarget}
import org.apache.pekko.actor._
import config.{AppConfig, ApplicationConfiguration}
import connectors.{DesConnector, HipConnector, IFConnector}
import metrics.ApplicationMetrics
import play.api.Logging
import repositories.{BulkCalculationMongoRepository, BulkCalculationRepository}
import uk.gov.hmrc.mongo.lock.{LockRepository, MongoLockRepository, TimePeriodLockService}

import javax.inject.Singleton
import scala.concurrent.duration._
import scala.annotation.nowarn


@nowarn
@Singleton
class ProcessingSupervisor (applicationConfig: ApplicationConfiguration,
                                     bulkCalculationMongoRepository : BulkCalculationMongoRepository,
                                     val mongoLockRepository: MongoLockRepository,
                                     desConnector : DesConnector,
                                     ifConnector: IFConnector,
                                     hipConnector: HipConnector,
                                     metrics : ApplicationMetrics, appConfig: AppConfig)
  extends Actor with ActorUtils with TimePeriodLockService with Logging {

  override val lockRepository: LockRepository = mongoLockRepository
  override val lockId: String = "bulkprocessing"
  override val ttl: Duration = (2 * applicationConfig.bulkProcessingInterval).seconds

  // $COVERAGE-OFF$
  lazy val repository: BulkCalculationRepository = bulkCalculationMongoRepository
  lazy val requestActor: ActorRef = context.actorOf(Props(
    classOf[DefaultCalculationRequestActor],
    bulkCalculationMongoRepository,
    desConnector,
    ifConnector,
    hipConnector,
    metrics,
    applicationConfig,
    appConfig,
    context.dispatcher
  ), "calculation-requester")

  lazy val throttler: ActorRef = context.actorOf(Props(classOf[TimerBasedThrottler],
    applicationConfig.bulkProcessingTps msgsPer 1.seconds), "throttler")

  throttler ! SetTarget(Some(requestActor))

  // $COVERAGE-ON$

  override def receive: Receive = {
    implicit val ec = context.dispatcher

    {
      case STOP =>
        logger.info("[ProcessingSupervisor] received while not processing: STOP received")
      case START =>
        withRenewedLock {
          context become receiveWhenProcessRunning
          logger.info("Starting Processing")

          repository.findRequestsToProcess().map {

            case Some(requests) if requests.nonEmpty =>
              logger.info(s"[ProcessingSupervisor][receive] took ${requests.size} request/s")
              for (request <- requests.take(applicationConfig.bulkProcessingBatchSize)) {
                throttler ! request
              }
              throttler ! STOP

            case _ =>
              logger.info(s"[ProcessingSupervisor][receive] no requests pending")
              this.context.unbecome()
              throttler ! STOP
          }
        }.map {
          case Some(res) => logger.info(s"[ProcessingSupervisor][receive] Finished with $res. Mongo Lock has been renewed.")
          // $COVERAGE-OFF$
          case _ => logger.info(s"[ProcessingSupervisor][receive] Failed to obtain mongo lock")
          // $COVERAGE-ON$
        }
    }
  }

  def receiveWhenProcessRunning: Receive = {
    // $COVERAGE-OFF$
    case START =>
      logger.info("[ProcessingSupervisor][received while processing] START ignored")
    // $COVERAGE-ON$

    case STOP =>
      logger.info("[ProcessingSupervisor][received while processing] STOP received")
      context.unbecome()
  }

}
