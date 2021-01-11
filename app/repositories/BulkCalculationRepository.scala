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

package repositories

import java.util.concurrent.TimeUnit

import com.google.inject.{Inject, Provider, Singleton}
import config.ApplicationConfiguration
import connectors.{EmailConnector, ProcessedUploadTemplate}
import events.BulkEvent
import metrics.ApplicationMetrics
import models._
import org.joda.time.{DateTime, LocalDateTime}
import play.api.libs.iteratee.{Iteratee, _}
import play.api.libs.json.{JsObject, Json}
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.collections.GenericCollection
import reactivemongo.api.commands.MultiBulkWriteResult
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{Cursor, DefaultDB, ReadPreference}
import reactivemongo.bson.{BSONDateTime, BSONDocument, BSONObjectID}
import reactivemongo.play.iteratees.cursorProducer
import reactivemongo.play.json.ImplicitBSONHandlers._
import reactivemongo.play.json.JSONSerializationPack
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.play.audit.http.connector.AuditConnector

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

@Singleton
class BulkCalculationMongoRepositoryProvider @Inject()(component: ReactiveMongoComponent,
                                                       metrics: ApplicationMetrics,
                                                       auditConnector: AuditConnector,
                                                       emailConnector : EmailConnector,
                                                       applicationConfig: ApplicationConfiguration)
  extends Provider[BulkCalculationMongoRepository] {
  override def get(): BulkCalculationMongoRepository = {
    new BulkCalculationMongoRepository(metrics, auditConnector, emailConnector : EmailConnector, applicationConfig)(component.mongoConnector.db)
  }
}

class BulkCalculationMongoRepository @Inject()(override val metrics: ApplicationMetrics,
                                               ac: AuditConnector,
                                               override val emailConnector : EmailConnector,
                                               applicationConfiguration: ApplicationConfiguration)(implicit mongo: () => DefaultDB)
  extends ReactiveRepository[BulkCalculationRequest, BSONObjectID](
    "bulk-calculation",
    mongo,
    BulkCalculationRequest.formats) with BulkCalculationRepository {

  override val auditConnector: AuditConnector = ac



  lazy val proxyCollection: GenericCollection[JSONSerializationPack.type] = collection

  // $COVERAGE-OFF$
  {


    val childrenEnumerator: Enumerator[BSONDocument] = proxyCollection.find(Json.obj("bulkId" -> Json.obj("$exists" -> true), "isChild" -> Json.obj("$exists" -> false)), Option.empty[JsObject]).cursor[BSONDocument]().enumerator()

    val processChildren: Iteratee[BSONDocument, Unit] = {
      Iteratee.foreach { child =>
        val childId = child.getAs[BSONObjectID]("_id")
        val hasResponse = child.get("calculationResponse")
        val hasValidRequest = child.get("validCalculationRequest")
        val hasValidationErrors = child.get("validationErrors")
        val selector = BSONDocument("_id" -> childId.get)
        val data = BSONDocument("$set" -> BSONDocument("isChild" -> true, "hasResponse" -> hasResponse.isDefined, "hasValidRequest" -> hasValidRequest.isDefined, "hasValidationErrors" -> hasValidationErrors.isDefined))

        proxyCollection.update(ordered = false).one(selector, data, upsert = true)
      }
    }

    childrenEnumerator.run(processChildren)
  }
  // $COVERAGE-ON$

  override def indexes: Seq[Index] = Seq(
    Index(Seq("createdAt" -> IndexType.Ascending), Some("bulkCalculationRequestExpiry"), options = BSONDocument("expireAfterSeconds" -> 2592000), sparse = true, background = true),
    Index(Seq("bulkId" -> IndexType.Ascending), Some("bulkId"), background = true),
    Index(Seq("uploadReference" -> IndexType.Ascending), Some("UploadReference"), sparse = true, unique = true),
    Index(Seq("bulkId" -> IndexType.Ascending, "lineId" -> IndexType.Ascending), Some("BulkAndLine")),
    Index(Seq("userId" -> IndexType.Ascending), Some("UserId"), background = true),
    Index(Seq("lineId" -> IndexType.Descending), Some("LineIdDesc"), background = true),
    Index(Seq("isParent" -> IndexType.Ascending, "complete" -> IndexType.Ascending), Some("isParentAndComplete")),
    Index(Seq("isParent" -> IndexType.Ascending), Some("isParent")),
    Index(Seq("isChild" -> IndexType.Ascending, "hasValidRequest" -> IndexType.Ascending, "hasResponse" -> IndexType.Ascending, "hasValidationErrors" -> IndexType.Ascending), Some("childQuery")),
    Index(Seq("isChild" -> IndexType.Ascending, "bulkId" -> IndexType.Ascending), Some("childBulkIndex"))
  )

  override def insertResponseByReference(bulkId: String, lineId: Int, calculationResponse: GmpBulkCalculationResponse): Future[Boolean] = {

    val startTime = System.currentTimeMillis()
    val selector = Json.obj("bulkId" -> bulkId, "lineId" -> lineId)
    val modifier = Json.obj("$set" -> Json.obj("calculationResponse" -> calculationResponse, "hasResponse" -> true))
    val result = proxyCollection.update(ordered = false).one(selector, modifier)

    result onComplete {
      case _ => metrics.insertResponseByReferenceTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
    }

    result.map {
      lastError => Logger.debug(s"[BulkCalculationRepository][insertResponseByReference] bulkResponse: $calculationResponse, result : $lastError ")
        lastError.ok
    }.recover {
      // $COVERAGE-OFF$
      case e => Logger.error("Failed to update request", e)
        false
      // $COVERAGE-ON$
    }
  }

  override def findByReference(uploadReference: String, csvFilter: CsvFilter = CsvFilter.All): Future[Option[ProcessedBulkCalculationRequest]] = {

    val startTime = System.currentTimeMillis()

    val tryResult = Try {

      val request = proxyCollection.find(Json.obj("uploadReference" -> uploadReference), Option.empty[JsObject]).one[ProcessedBulkCalculationRequest]

      val result = request.flatMap {
        case Some(br) => {

          val childQuery = csvFilter match {
            case CsvFilter.Failed => Json.obj("bulkId" -> br._id, "$or" -> Json.arr(Json.obj("validationErrors" -> Json.obj("$exists" -> true)), Json.obj("calculationResponse.containsErrors" -> true)))
            case CsvFilter.Successful => Json.obj("bulkId" -> br._id, "validationErrors" -> Json.obj("$exists" -> false), "calculationResponse.containsErrors" -> false)
            case _ => Json.obj("bulkId" -> br._id)
          }
          proxyCollection.find(childQuery, Option.empty[JsObject]).sort(Json.obj("lineId" -> 1)).cursor[ProcessReadyCalculationRequest](ReadPreference.primary)
            .collect[List](-1, Cursor.FailOnError[List[ProcessReadyCalculationRequest]]()).map {
            calcRequests => Some(br.copy(calculationRequests = calcRequests))
          }
        }
      }

      result onComplete {
        case _ => metrics.findByReferenceTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }

      result
    }

    tryResult match {
      case Success(s) => {
        s.map { x =>
          Logger.debug(s"[BulkCalculationRepository][findByReference] uploadReference: $uploadReference, result: $x ")
          x
        }
      }
      case Failure(f) => {
        Logger.error(s"[BulkCalculationRepository][findByReference] uploadReference: $uploadReference, exception: ${f.getMessage}")
        Future.successful(None)
      }
    }
  }

  override def findSummaryByReference(uploadReference: String): Future[Option[BulkResultsSummary]] = {

    val startTime = System.currentTimeMillis()

    val tryResult = Try {

      val result = proxyCollection.find(Json.obj("uploadReference" -> uploadReference), Some(Json.obj("reference" -> 1, "total" -> 1, "failed" -> 1, "userId" -> 1)))
        .cursor[BulkResultsSummary](ReadPreference.primary).collect[List](-1,Cursor.FailOnError[List[BulkResultsSummary]]())
      result onComplete {
        case _ => metrics.findSummaryByReferenceTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }
      result
    }

    tryResult match {
      case Success(s) => {
        s.map { x =>
          Logger.debug(s"[BulkCalculationRepository][findSummaryByReference] uploadReference : $uploadReference, result: $x")
          x.headOption
        }
      }
      case Failure(f) => {
        Logger.error(s"[BulkCalculationRepository][findSummaryByReference] uploadReference : $uploadReference, exception: ${f.getMessage}")
        Future.successful(None)
      }
    }
  }

  override def findByUserId(userId: String): Future[Option[List[BulkPreviousRequest]]] = {

    val startTime = System.currentTimeMillis()

    val tryResult = Try {
      val result = proxyCollection
        .find(Json.obj("userId" -> userId, "complete" -> true), Some(Json.obj("uploadReference" -> 1, "reference" -> 1, "timestamp" -> 1, "processedDateTime" -> 1)))
        .cursor[BulkPreviousRequest](ReadPreference.primary)
        .collect[List](-1, Cursor.FailOnError[List[BulkPreviousRequest]]())

      result onComplete {
        case _ => metrics.findByUserIdTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }
      result
    }

    tryResult match {
      case Success(s) => {
        s.map { x =>
          Logger.debug(s"[BulkCalculationRepository][findByUserId] userId : $userId, result: ${x.size}")
          Some(x)
        }
      }
      case Failure(f) => {
        Logger.error(s"[BulkCalculationRepository][findByUserId] exception: ${f.getMessage}")
        Future.successful(None)
      }
    }
  }

  override def findRequestsToProcess(): Future[Option[List[ProcessReadyCalculationRequest]]] = {

    val startTime = System.currentTimeMillis()

    val testResult = Try {

      val incompleteBulk = proxyCollection.find(Json.obj("isParent" -> true, "complete" -> false), Option.empty[JsObject]).sort(Json.obj("_id" -> 1)).cursor[ProcessedBulkCalculationRequest](ReadPreference.primary)
        .collect[List](-1, Cursor.FailOnError[List[ProcessedBulkCalculationRequest]]())

      incompleteBulk.map {
        bulkList =>
          bulkList.map {
            bulkRequest => {

              val childRequests = proxyCollection.find(Json.obj("isChild" -> true, "hasValidationErrors" -> false, "bulkId" -> bulkRequest._id,
                "hasValidRequest" -> true,
                "hasResponse" -> false), Option.empty[JsObject]).cursor[ProcessReadyCalculationRequest](ReadPreference.primary).collect[List](applicationConfiguration.bulkProcessingBatchSize, Cursor.FailOnError[List[ProcessReadyCalculationRequest]]())

              childRequests
            }
          }
      }
    }

    testResult match {
      case Success(s) => {
        s.flatMap {
          x => {

            val sequenced = Future.sequence(x).map {
              thing => Some(thing.flatten)
            }
            Logger.debug(s"[BulkCalculationRepository][findRequestsToProcess] SUCCESS")

            sequenced onComplete {
              case _ => metrics.findRequestsToProcessTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
            }

            sequenced
          }
        }
      }

      case Failure(f) => {
        Logger.error(s"[BulkCalculationRepository][findRequestsToProcess] failed: ${f.getMessage}")
        metrics.findRequestsToProcessTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
        Future.successful(None)
      }
    }

  }

  override def findAndComplete(): Future[Boolean] = {

    val startTime = System.currentTimeMillis()

    Logger.debug("[BulkCalculationRepository][findAndComplete]: starting ")
    val findResult = Try {

      val incompleteBulk = proxyCollection.find(Json.obj("isParent" -> true, "complete" -> false), Option.empty[JsObject]).sort(Json.obj("_id" -> 1))
        .cursor[ProcessedBulkCalculationRequest](ReadPreference.primary)
        .collect[List](-1,Cursor.FailOnError[List[ProcessedBulkCalculationRequest]]())

      incompleteBulk onComplete {
        case _ => metrics.findAndCompleteParentTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }

      incompleteBulk.flatMap {
        bulkList =>
          Future.sequence(bulkList.par.map {
            bulkRequest => {

              val childrenStartTime = System.currentTimeMillis()

              proxyCollection.count(Some(Json.obj("bulkId" -> bulkRequest._id, "isChild" -> true, "hasResponse" -> false, "hasValidationErrors" -> false, "hasValidRequest" -> true))) flatMap {
                case result => result match {
                  case count if count == 0 =>
                    val processedChildren = proxyCollection.find(Json.obj("isChild" -> true, "bulkId" -> bulkRequest._id), Option.empty[JsObject])
                      .cursor[ProcessReadyCalculationRequest](ReadPreference.primary).collect[List](-1, Cursor.FailOnError[List[ProcessReadyCalculationRequest]]()).flatMap { allChildren =>
                      Future.successful(Some(bulkRequest.copy(calculationRequests = allChildren)))
                    }

                    processedChildren onComplete {
                      case _ => metrics.findAndCompleteChildrenTimer(System.currentTimeMillis() - childrenStartTime, TimeUnit.MILLISECONDS)
                    }

                    processedChildren

                  case _ => Future.successful(None)
                }
              }
            }
          }.toList
          ) map {
            br => br.filter(z => z.isDefined)
          }
      }
    }

    Logger.debug(s"[BulkCalculationRepository][findAndComplete]: processing")

    findResult match {
      case Success(s) => {
        val result = s.flatMap { requests =>
          Future.sequence(requests.map { request =>

            Logger.debug(s"Got request $request")

            val totalRequests = request.get.calculationRequests.size
            val failedRequests = request.get.failedRequestCount

            val selector = Json.obj("uploadReference" -> request.get.uploadReference)
            val modifier = Json.obj("$set" -> Json.obj("complete" -> true, "total" -> totalRequests, "failed" -> failedRequests, "createdAt" -> BSONDateTime(DateTime.now().getMillis), "processedDateTime" -> LocalDateTime.now().toString))

            val result = proxyCollection.update(ordered = false).one(selector, modifier)

            result.map {
              writeResult => Logger.debug(s"[BulkCalculationRepository][findAndComplete] : { result : $writeResult }")
                // $COVERAGE-OFF$
                if (writeResult.ok) {
                  implicit val hc = HeaderCarrier()
                  val resultsEventResult = auditConnector.sendEvent(new BulkEvent(
                    request.get.userId,
                    totalRequests - failedRequests,
                    request.get.calculationRequests.count(x => x.validationErrors != None),
                    request.get.calculationRequests.count(x => x.hasNPSErrors),
                    totalRequests,
                    request.get.calculationRequests.collect {
                      case x if x.calculationResponse.isDefined => x.calculationResponse.get.errorCodes
                    }.flatten,
                    request.get.calculationRequests.collect {
                      case x if x.validCalculationRequest.isDefined && x.calculationResponse.isDefined => x.validCalculationRequest.get.scon
                    },
                    request.get.calculationRequests.collect {
                      case x if x.validCalculationRequest.isDefined && x.calculationResponse.isDefined && x.validCalculationRequest.get.dualCalc.isDefined && x.validCalculationRequest.get.dualCalc.get == 1 => true
                      case x if x.validCalculationRequest.isDefined && x.calculationResponse.isDefined && x.validCalculationRequest.get.dualCalc.isDefined && x.validCalculationRequest.get.dualCalc.get == 0 => false
                    },
                    request.get.calculationRequests.collect {
                      case x if x.validCalculationRequest.isDefined && x.calculationResponse.isDefined => x.validCalculationRequest.get.calctype.get
                    }
                  ))
                  resultsEventResult.onFailure {
                    case e: Throwable => Logger.error(s"[BulkCalculationRepository][findAndComplete] resultsEventResult: ${e.getMessage}", e)
                  }

                  val childSelector = Json.obj("bulkId" -> request.get._id)
                  val childModifier = Json.obj("$set" -> Json.obj("createdAt" -> BSONDateTime(DateTime.now().getMillis)))
                  val childResult = proxyCollection.update(ordered = false).one(childSelector, childModifier, multi = true)

                  childResult.map {
                    childWriteResult => Logger.debug(s"[BulkCalculationRepository][findAndComplete] childResult: $childWriteResult")
                  }

                  emailConnector.sendProcessedTemplatedEmail(ProcessedUploadTemplate(
                    request.get.email,
                    request.get.reference,
                    request.get.timestamp.toLocalDate,
                    request.get.userId))
                }
                // $COVERAGE-ON$
                writeResult.ok
            }
          }).map {
            x => x.foldLeft(true) {
              _ && _
            }
          }
        }
        result onComplete {
          case _ => {
            metrics.findAndCompleteTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
          }
        }
        result
      }
      case Failure(f) => {
        Logger.error(s"[BulkCalculationRepository][findAndComplete] ${f.getMessage}", f)
        metrics.findAndCompleteTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
        Future.successful(false)
        }.recover {
        // $COVERAGE-OFF$
        case e: Exception => {
          metrics.findAndCompleteTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
          Logger.error(s"[BulkCalculationRepository][findAndComplete] ${e.getMessage}", e)
          false
        }
        // $COVERAGE-ON$
      }
    }
  }

  override def insertBulkDocument(bulkCalculationRequest: BulkCalculationRequest): Future[Boolean] = {

    Logger.info(s"[BulkCalculationRepository][insertBulkDocument][numDocuments]: ${bulkCalculationRequest.calculationRequests.size}")

    val startTime = System.currentTimeMillis()

    findDuplicateUploadReference(bulkCalculationRequest.uploadReference).flatMap {

      case true => Logger.debug(s"[BulkCalculationRepository][insertBulkDocument] Duplicate request found (${bulkCalculationRequest.uploadReference})")
        Future.successful(false)
      case false => {

        val strippedBulk = ProcessedBulkCalculationRequest(BSONObjectID.generate.stringify,
          bulkCalculationRequest.uploadReference,
          bulkCalculationRequest.email,
          bulkCalculationRequest.reference,
          List(),
          bulkCalculationRequest.userId,
          bulkCalculationRequest.timestamp,
          complete = bulkCalculationRequest.complete.getOrElse(false),
          bulkCalculationRequest.total.getOrElse(0),
          bulkCalculationRequest.failed.getOrElse(0),
          isParent = true)

        val calculationRequests = bulkCalculationRequest.calculationRequests.map {
          request => request.copy(bulkId = Some(strippedBulk._id))
        }

        val insertResult = Try {

          val bulkDocs: immutable.Seq[ProcessReadyCalculationRequest] = calculationRequests map { c => ProcessReadyCalculationRequest(
            c.bulkId.get,
            c.lineId,
            c.validCalculationRequest,
            c.validationErrors,
            calculationResponse = c.calculationResponse,
            isChild = true,
            hasResponse = c.calculationResponse.isDefined,
            hasValidRequest = c.validCalculationRequest.isDefined,
            hasValidationErrors = c.hasErrors)
          }

          val insertResult = proxyCollection.insert(ordered = false).one(strippedBulk).flatMap {_ =>
            proxyCollection.insert(ordered = false).many[ProcessReadyCalculationRequest](bulkDocs)
          }

          insertResult onComplete {
            case _ => metrics.insertBulkDocumentTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
          }

          insertResult
        }

        insertResult match {
          case Success(s) => {
            s.map {
              case x: MultiBulkWriteResult if x.writeErrors == Nil =>
                Logger.debug(s"[BulkCalculationRepository][insertBulkDocument] $x")
                true
            }.recover {
              case e: Throwable =>
                // $COVERAGE-OFF$
                Logger.error("Error inserting document", e)
                false
              // $COVERAGE-ON$
            }
          }

          case Failure(f) => {
            Logger.error(s"[BulkCalculationRepository][insertBulkDocument] failed: ${f.getMessage}")
            Future.successful(false)
          }
        }
      }
    }
  }

  private def findDuplicateUploadReference(uploadReference: String): Future[Boolean] = {

    val tryResult = Try {

      proxyCollection.find(Json.obj("uploadReference" -> uploadReference), Option.empty[JsObject])
        .cursor[BulkResultsSummary](ReadPreference.primary).collect[List](-1, Cursor.FailOnError[List[BulkResultsSummary]]())
    }

    tryResult match {
      case Success(s) =>
        s.map {
          x =>
            Logger.debug(s"[BulkCalculationRepository][findDuplicateUploadReference] uploadReference : $uploadReference, result: ${
              x.nonEmpty
            }")
            x.nonEmpty
        }

      // $COVERAGE-OFF$
      case Failure(e) =>
        Logger.error(s"[BulkCalculationRepository][findDuplicateUploadReference] ${
          e.getMessage
        } ($uploadReference)", e)
        Future.successful(false)
      // $COVERAGE-ON$
    }
  }
}

trait BulkCalculationRepository extends ReactiveRepository[BulkCalculationRequest, BSONObjectID] {

  def metrics: ApplicationMetrics

  val emailConnector: EmailConnector
  val auditConnector: AuditConnector

  def insertResponseByReference(reference: String, lineId: Int, calculationResponse: GmpBulkCalculationResponse): Future[Boolean]

  def findByReference(reference: String, filter: CsvFilter = CsvFilter.All): Future[Option[ProcessedBulkCalculationRequest]]

  def findSummaryByReference(reference: String): Future[Option[BulkResultsSummary]]

  def findByUserId(userId: String): Future[Option[List[BulkPreviousRequest]]]

  def findRequestsToProcess(): Future[Option[List[ProcessReadyCalculationRequest]]]

  //def findCountRemaining: Future[Option[Int]]

  def findAndComplete(): Future[Boolean]

  def insertBulkDocument(bulkCalculationRequest: BulkCalculationRequest): Future[Boolean]
}
