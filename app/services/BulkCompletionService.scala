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

package services

import com.google.inject.Inject
import play.api.Logging
import repositories.{BulkCalculationMongoRepository, BulkCalculationRepository}
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt


class BulkCompletionService @Inject() (bulkCalculationMongoRepository : BulkCalculationMongoRepository,
                                       mongoLockRepository: MongoLockRepository)(implicit ec: ExecutionContext) extends Logging {

val lockId = "bulkcompletion"
  val lockService: LockService = LockService(mongoLockRepository, lockId = lockId, ttl = 5.minutes)

  // $COVERAGE-OFF$
  lazy val repository: BulkCalculationRepository = bulkCalculationMongoRepository
  // $COVERAGE-ON$


  def checkForComplete(): Future[Unit] = {
    logger.info("[BulkCompletionService] Starting..")
    lockService.withLock {
      logger.info("[BulkCompletionService] Got lock")
      repository.findAndComplete()
    }.map {
      case Some(_) =>
        logger.info("[BulkCompletionService][receive] Obtained mongo lock")
      // $COVERAGE-OFF$
      case _ => logger.info("[BulkCompletionService][receive] Failed to obtain mongo lock")
      // $COVERAGE-ON$
    }
  }
}
