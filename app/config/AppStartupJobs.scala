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

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}


class Startup @Inject()(
                         appStartupJobs: AppStartupJobs,
                         actorSystem: ActorSystem
                       )(implicit ec: ExecutionContext) {

  actorSystem.scheduler.scheduleOnce(FiniteDuration(1, TimeUnit.MINUTES)) {
    appStartupJobs.runEverythingOnStartUp()
  }
}

class AppStartupJobsImpl @Inject()(val config: Configuration,
                                   val bulkCalcRepo: BulkCalculationMongoRepository,
                                  )(implicit val ec: ExecutionContext) extends AppStartupJobs

trait AppStartupJobs extends Logging {

  implicit val ec: ExecutionContext
  val bulkCalcRepo: BulkCalculationMongoRepository

  val processReadyCalsReqCollection: MongoCollection[ProcessReadyCalculationRequest] =
    bulkCalcRepo.processReadyCalsReqCollection

  val processedBulkCalsReqCollection: MongoCollection[ProcessedBulkCalculationRequest] =
    bulkCalcRepo.processedBulkCalsReqCollection

  private def logParentsMissingCreatedAtAndChildren(): Future[Unit] = {
    val filter = Filters.and(
      Filters.eq("isParent", true),
      Filters.exists("createdAt", false)
    )

    processedBulkCalsReqCollection.find(filter).projection(Projections.include("_id")).toFuture().flatMap { parents =>
      logger.info(s"[runEverythingOnStartUp] Found ${parents.size} parent documents missing createdAt")

      val childCountFutures : Seq[Future[(String, Long)]] = parents.map { parent =>
        val parentId = parent._id

        val childFilter = Filters.and(
          Filters.eq("isChild", true),
          Filters.eq("bulkId", parentId)
        )

        processReadyCalsReqCollection.countDocuments(childFilter).toFuture().map { childCount =>
          parentId -> childCount
        }
      }

      Future.sequence(childCountFutures).map { results =>
        val summaryLines = results.map { case (parentId, childCount) =>
          s"- $parentId -> $childCount children"
        }.mkString("\n")

        logger.info(
          s"""[runEverythingOnStartUp] Parent → Child count summary:
             |$summaryLines
             |""".stripMargin
        )
      }
    }.recover {
      case ex =>
        logger.error("[runEverythingOnStartUp] Failed to fetch parents missing createdAt", ex)
    }
  }


  def runEverythingOnStartUp(): Future[Unit] = {
    logger.info("[runEverythingOnStartUp] Running Startup Jobs...")

    val missingCreatedAtFilter = Filters.and(
      Filters.eq("isChild", true),
      Filters.exists("createdAt", false)
    )

    val incompleteParentsFilter = Filters.and(
      Filters.eq("isParent", true),
      Filters.eq("complete", false)
    )

    for {
      _ <- logCount(
        collection = processReadyCalsReqCollection,
        filter = missingCreatedAtFilter,
        description = "child documents missing createdAt"
      )(ExecutionContext.global)

      _ <- logCount(
        collection = processedBulkCalsReqCollection,
        filter = incompleteParentsFilter,
        description = "incomplete parent documents (complete = false)"
      )(ExecutionContext.global)

      _ <- logParentsMissingCreatedAtAndChildren()
    } yield {
      logger.info("[runEverythingOnStartUp] Startup checks complete.")
    }
  }

  private def logCount[T](
                           collection: MongoCollection[T],
                           filter: org.mongodb.scala.bson.conversions.Bson,
                           description: String
                         )(implicit ec: ExecutionContext): Future[Unit] = {
    collection.countDocuments(filter).toFuture().map { count =>
      logger.info(s"[runEverythingOnStartUp] Found $count $description in gmp-bulk collection")
    }.recover {
      case ex =>
        logger.error(s"[runEverythingOnStartUp] Failed to count $description in gmp-bulk collection", ex)
    }
  }
}


