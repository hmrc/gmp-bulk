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

package config

import models.{ProcessReadyCalculationRequest, ProcessedBulkCalculationRequest}
import org.apache.pekko.actor.ActorSystem
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.model.{Filters, Projections}
import play.api.{Configuration, Logging}
import repositories.BulkCalculationMongoRepository
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import org.mongodb.scala.bson.BsonDocument
import uk.gov.hmrc.mongo.MongoComponent
import org.mongodb.scala.ObservableFuture

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AppStartupJobsImpl @Inject() (
  val config:              Configuration,
  val bulkCalcRepo:        BulkCalculationMongoRepository,
  val mongoLockRepository: MongoLockRepository,
  val applicationConfig:   ApplicationConfiguration,
  val mongo:               MongoComponent,
  actorSystem:             ActorSystem
)(implicit val ec: ExecutionContext)
    extends AppStartupJobs {
  actorSystem.scheduler.scheduleOnce(FiniteDuration(1, TimeUnit.MINUTES)) {
    runEverythingOnStartUp()
  }
}

trait AppStartupJobs extends Logging {

  implicit val ec:         ExecutionContext
  val bulkCalcRepo:        BulkCalculationMongoRepository
  val mongoLockRepository: MongoLockRepository
  val mongo:               MongoComponent

  val applicationConfig: ApplicationConfiguration

  val processReadyCalsReqCollection: MongoCollection[ProcessReadyCalculationRequest] =
    bulkCalcRepo.processReadyCalsReqCollection

  val processedBulkCalsReqCollection: MongoCollection[ProcessedBulkCalculationRequest] =
    bulkCalcRepo.processedBulkCalsReqCollection

  val lockId = "bulkStartupJobLock"
  val lockService: LockService = LockService(mongoLockRepository, lockId = lockId, ttl = 5.minutes)

  private def logParentsMissingCreatedAtAndChildren(): Future[Unit] = {
    val parentFilter = Filters.and(
      Filters.eq("isParent", true),
      Filters.exists("createdAt", exists = false)
    )

    val childFilter = (parentId: String) =>
      Filters.and(
        Filters.eq("isChild", true),
        Filters.eq("bulkId", parentId)
      )

    for {
      parents <- processedBulkCalsReqCollection
                   .find(parentFilter)
                   .projection(Projections.include("_id"))
                   .toFuture()
      parentChild <- Future.sequence(parents.map { parent =>
                       processReadyCalsReqCollection
                         .countDocuments(childFilter(parent._id))
                         .toFuture()
                         .map(parent._id -> _)
                     })
    } yield {
      val logString = parentChild
        .map { case (parentId, childCount) => s"$parentId -> $childCount children" }
        .mkString("|")

      logger.info(s"""[runEverythingOnStartUp] Found ${parentChild.size},
           | Parent → Child count summary: $logString""".stripMargin)
    }
  }.recover { case ex =>
    logger.error("[runEverythingOnStartUp] Failed to fetch parents missing createdAt", ex)
  }

  def logOldestCreatedAt(): Future[Unit] =
    mongo.database
      .getCollection[BsonDocument]("bulk-calculation")
      .find(Filters.exists("createdAt", true))
      .limit(1)
      .projection(Projections.include("createdAt"))
      .toFuture()
      .map(_.headOption)
      .map {
        case Some(document) =>
          val createdAtStr = document.getString("createdAt").getValue
          val createdAt    = LocalDateTime.parse(createdAtStr, DateTimeFormatter.ISO_DATE_TIME)
          logger.info(s"[runEverythingOnStartUp] Oldest createdAt = $createdAt")
        case None =>
          logger.info("[runEverythingOnStartUp] No documents with createdAt found")
      }

  def runEverythingOnStartUp(): Future[Option[Unit]] = {
    logger.info("[runEverythingOnStartUp] Running Startup Jobs...")
    lockService.withLock {
      val missingCreatedAtFilter = Filters.and(
        Filters.eq("isChild", true),
        Filters.exists("createdAt", false)
      )

      val incompleteParentsFilter = Filters.and(
        Filters.eq("isParent", true),
        Filters.eq("complete", false)
      )

      def parentsMissingCreatedAtAndChildren(): Future[Unit] =
        if applicationConfig.logParentsChildrenEnabled then {
          logParentsMissingCreatedAtAndChildren()
        } else {
          Future.successful(())
        }

      for {
        _ <- logCount(
               collection = processReadyCalsReqCollection,
               filter = missingCreatedAtFilter,
               description = "child documents missing createdAt"
             )(using ec)

        _ <- logCount(
               collection = processedBulkCalsReqCollection,
               filter = incompleteParentsFilter,
               description = "incomplete parent documents (complete = false)"
             )(using ec)

        _ <- parentsMissingCreatedAtAndChildren()
        _ <- logOldestCreatedAt()
      } yield logger.info("[runEverythingOnStartUp] Startup checks complete.")
    }
  }

  private def logCount[T](
    collection:  MongoCollection[T],
    filter:      org.mongodb.scala.bson.conversions.Bson,
    description: String
  )(implicit ec: ExecutionContext): Future[Unit] =
    collection
      .countDocuments(filter)
      .toFuture()
      .map { count =>
        logger.info(s"[runEverythingOnStartUp] Found $count $description in gmp-bulk collection")
      }
      .recover { case ex =>
        logger.error(s"[runEverythingOnStartUp] Failed to count $description in gmp-bulk collection", ex)
      }
}
