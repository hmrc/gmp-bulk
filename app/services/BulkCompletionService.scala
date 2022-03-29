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

package services

import com.google.inject.Inject
import play.api.Logging
import repositories.BulkCalculationMongoRepository
import repositories.BulkCalculationRepository
import uk.gov.hmrc.lock.{LockKeeper, LockMongoRepository, LockRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class BulkCompletionService @Inject() (bulkCalculationMongoRepository : BulkCalculationMongoRepository, mongoApi : play.modules.reactivemongo.ReactiveMongoComponent) extends Logging {

  val connection = {
    mongoApi.mongoConnector.db
  }

  val lockrepo = LockMongoRepository(connection)
  val lockKeeper = new LockKeeper {

    override def repo: LockRepository = lockrepo //The repo created before

    override def lockId: String = "bulkcompletion"

    override val forceLockReleaseAfter: org.joda.time.Duration = org.joda.time.Duration.standardMinutes(5)

    // $COVERAGE-OFF$
    override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] = {
      logger.debug("Trying to get completion lock")
      repo.lock(lockId, serverId, forceLockReleaseAfter)
        .flatMap { acquired =>
          if (acquired)
            body.map { x => logger.debug("Got completion lock"); Some(x) }(global).recover {
              case e => logger.error("Exception getting lock: " + e.getMessage, e)
                throw e
            }(global)
          else {
            logger.debug("Couldnt get lock");
            Future.successful(None)
          }
        }(global).recoverWith { case ex => {
        logger.error("Exception getting lock: " + ex.getMessage, ex)
        repo.releaseLock(lockId, serverId).flatMap(_ => Future.failed(ex))(global)
      }
      }(global)
    }

    // $COVERAGE-ON$
  }

  // $COVERAGE-OFF$
  lazy val repository: BulkCalculationRepository = bulkCalculationMongoRepository
  // $COVERAGE-ON$


  def checkForComplete() = {

    logger.debug("[BulkCompletionService] Starting..")

    lockKeeper.tryLock {
      logger.debug("[BulkCompletionService] Got lock")
      repository.findAndComplete().map {
        case true => logger.debug("[BulkCompletionService] Found and completed successfully"); lockrepo.releaseLock(lockKeeper.lockId, lockKeeper.serverId)
        case false => logger.warn("[BulkCompletionService] Failed"); lockrepo.releaseLock(lockKeeper.lockId, lockKeeper.serverId)
      }
    }.map {
      case Some(thing) => logger.debug("[BulkCompletionService][receive] Obtained mongo lock")
      // $COVERAGE-OFF$
      case _ => logger.debug("[BulkCompletionService][receive] Failed to obtain mongo lock")
      // $COVERAGE-ON$
    }
  }
}
