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

package repositories

import java.util.concurrent.TimeUnit
import com.google.inject.{Inject, Provider, Singleton}
import config.ApplicationConfiguration
import connectors.{EmailConnector, ProcessedUploadTemplate}
import events.BulkEvent
import metrics.ApplicationMetrics
import models._
import org.joda.time.LocalDateTime
import org.mongodb.scala.bson.{BsonDocument, ObjectId}
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, Projections, Sorts, Updates}
import org.mongodb.scala.result.InsertManyResult
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.play.json.{Codecs, CollectionFactory, PlayMongoRepository}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJodaFormats
import uk.gov.hmrc.play.audit.http.connector.AuditConnector

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


@Singleton
class BulkCalculationMongoRepositoryProvider @Inject()(metrics: ApplicationMetrics,
                                                       auditConnector: AuditConnector,
                                                       emailConnector : EmailConnector,
                                                       applicationConfig: ApplicationConfiguration,
                                                       mongo: MongoComponent)
  extends Provider[BulkCalculationMongoRepository] {
  override def get(): BulkCalculationMongoRepository = {
    new BulkCalculationMongoRepository(metrics, auditConnector, emailConnector : EmailConnector, applicationConfig, mongo)
  }
}

class BulkCalculationMongoRepository @Inject()(override val metrics: ApplicationMetrics,
                                               ac: AuditConnector,
                                               override val emailConnector : EmailConnector,
                                               applicationConfiguration: ApplicationConfiguration,
                                               mongo: MongoComponent)
  extends PlayMongoRepository[BulkCalculationRequest](
      collectionName = "bulk-calculation",
      mongoComponent = mongo,
      domainFormat = BulkCalculationRequest.formats,
      indexes = Seq(
        IndexModel(Indexes.ascending("createdAt"), IndexOptions().name("bulkCalculationRequestExpiry").expireAfter(2592000, TimeUnit.SECONDS).sparse(true).background(true)),
        IndexModel(Indexes.ascending("bulkId"), IndexOptions().name("bulkId").background(true)),
        IndexModel(Indexes.ascending("uploadReference"), IndexOptions().name("UploadReference").sparse(true).unique(true)),
        IndexModel(Indexes.ascending("bulkId", "lineId"), IndexOptions().name("BulkAndLine")),
        IndexModel(Indexes.ascending("userId"), IndexOptions().name("UserId").background(true)),
        IndexModel(Indexes.ascending("lineId"), IndexOptions().name("LineIdDesc").background(true)),
        IndexModel(Indexes.ascending("isParent"), IndexOptions().name("isParent")),
        IndexModel(Indexes.ascending("isParent","complete"), IndexOptions().name("isParentAndComplete")),
        IndexModel(Indexes.ascending("isChild", "hasValidRequest", "hasResponse", "hasValidationErrors"), IndexOptions().name("childQuery")),
        IndexModel(Indexes.ascending("isChild", "bulkId"), IndexOptions().name("childBulkIndex"))
      )
    ) with BulkCalculationRepository with Logging {

  override val auditConnector: AuditConnector = ac
  val bulkCalcReqCollection = CollectionFactory.collection(mongo.database, collectionName, BulkCalculationRequest.formats)
  val processedBulkCalsReqCollection = CollectionFactory.collection(mongo.database, collectionName, ProcessedBulkCalculationRequest.formats)
  val processReadyCalsReqCollection = CollectionFactory.collection(mongo.database, collectionName, ProcessReadyCalculationRequest.formats)
  val bulkPreviousReqCollection = CollectionFactory.collection(mongo.database, collectionName, BulkPreviousRequest.formats)


  // $COVERAGE-OFF$
  {

    val childrenEnumerator: Future[Seq[BsonDocument]] =
      mongo.database.getCollection[BsonDocument](collectionName = collectionName).find(Filters.and(
        Filters.exists("bulkId", true),
        Filters.exists("isChild", false)
      ))
        .toFuture()

    childrenEnumerator.map {
      _.map { child =>
        val childId = child.getObjectId("_id")
        val hasResponse = child.containsKey("calculationResponse")
        val hasValidRequest = child.containsKey("validCalculationRequest")
        val hasValidationErrors = child.containsKey("validationErrors")
        val selector = Filters.equal("_id", childId.getValue)
        processReadyCalsReqCollection
          .findOneAndUpdate(
            filter = selector,
            update = Updates.combine(
              Updates.set("isChild", true),
              Updates.set("hasResponse", hasResponse),
              Updates.set("hasValidRequest", hasValidRequest),
              Updates.set("hasValidationErrors", hasValidationErrors),
            )
          )
      }
    }

  }
  // $COVERAGE-ON$


  override def insertResponseByReference(bulkId: String, lineId: Int, calculationResponse: GmpBulkCalculationResponse): Future[Boolean] = {
    val startTime = System.currentTimeMillis()
    val selector = Filters.and(
      Filters.equal("bulkId", bulkId),
      Filters.equal("lineId", lineId))
    val modifier = Updates.combine(
      Updates.set("calculationResponse", Codecs.toBson(calculationResponse)),
      Updates.set("hasResponse", true))
    val result = processReadyCalsReqCollection.findOneAndUpdate(selector, modifier).toFuture()

    result onComplete {
      case _ => metrics.insertResponseByReferenceTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
    }

    result.map {
      lastError => logger.debug(s"[BulkCalculationRepository][insertResponseByReference] bulkResponse: $calculationResponse, result : $lastError ")
        true
    }.recover {
      // $COVERAGE-OFF$
      case e => logger.info("Failed to update request", e)
        false
      // $COVERAGE-ON$
    }
  }

  override def findByReference(uploadReference: String, csvFilter: CsvFilter = CsvFilter.All): Future[Option[ProcessedBulkCalculationRequest]] = {

    val startTime = System.currentTimeMillis()

    val request: Future[Option[ProcessedBulkCalculationRequest]] = processedBulkCalsReqCollection.find(Filters.equal("uploadReference", uploadReference)).headOption()
    val result: Future[Option[ProcessedBulkCalculationRequest]] = request.flatMap {
      case Some(br) => {
        val query = createQuery(csvFilter, br)
        processReadyCalsReqCollection.find(query)
          .sort(Sorts.ascending("lineId"))
          .collect()
          .toFuture()
          .map { calcRequests =>
            logger.debug(s"[BulkCalculationRepository][findByReference] uploadReference: $uploadReference, result: $br ")
            Some(br.copy(calculationRequests = calcRequests.toList))
          }
      }
      case _ => logger.debug(s"[BulkCalculationRepository][findByReference] uploadReference: $uploadReference, result: No ProcessedBulkCalculationRequest found  ")
        Future.successful(None)
    }

    result onComplete {
      case _ => metrics.findByReferenceTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
    }
    result
  }


  private def createQuery(csvFilter: CsvFilter, br: ProcessedBulkCalculationRequest) = csvFilter match {
    case CsvFilter.Failed => Filters.and(
      Filters.equal("bulkId", br._id),
      Filters.or(
        Filters.exists("validationErrors"),
        Filters.equal("calculationResponse.containsErrors", true)
      )
    )
      Filters.or( Filters.equal("bulkId", br._id))
    case CsvFilter.Successful => Filters.and(
      Filters.equal("bulkId", br._id),
      Filters.exists("validationErrors", false),
      Filters.equal("calculationResponse.containsErrors", false)
    )
    case _ => Filters.equal("bulkId", br._id)
  }

  override def findSummaryByReference(uploadReference: String): Future[Option[BulkResultsSummary]] = {

    val startTime = System.currentTimeMillis()

    val result = bulkCalcReqCollection
      .find(Filters.equal("uploadReference", uploadReference))
      .collect()
      .toFuture().map(_.toList)
      .map {
        _.map { res =>
          BulkResultsSummary(
            res.reference,
            res.total,
            res.failed,
            res.userId)
        }
      }
    result onComplete {
      case _ => metrics.findSummaryByReferenceTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
    }

    result.map { brs =>
      logger.debug(s"[BulkCalculationRepository][findSummaryByReference] uploadReference : $uploadReference, result: $brs")
      brs.headOption
    }.recover { case e =>
      logger.error(s"[BulkCalculationRepository][findSummaryByReference] uploadReference : $uploadReference, exception: ${e.getMessage}")
      None
    }


  }

  override def findByUserId(userId: String): Future[Option[List[BulkPreviousRequest]]] = {

    val startTime = System.currentTimeMillis()

    val result = bulkPreviousReqCollection
      .find(Filters.and(
        Filters.eq("userId", userId),
        Filters.eq("complete", true)))
      //.projection(Projections.include("uploadReference","reference","timestamp","processedDateTime"))
      .collect()
      .toFuture().map(_.toList)

    result onComplete {
      case _ => metrics.findByUserIdTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
    }

    result.map { bulkRequest =>
      logger.debug(s"[BulkCalculationRepository][findByUserId] userId : $userId, result: ${bulkRequest.size}")
      Some(bulkRequest)
    }.recover {
      case e => logger.error(s"[BulkCalculationRepository][findByUserId] exception: ${e.getMessage}")
        None
    }
    }

  override def findRequestsToProcess(): Future[Option[List[ProcessReadyCalculationRequest]]] = {

    val startTime = System.currentTimeMillis()

    val incompleteBulk: Future[List[ProcessedBulkCalculationRequest]] = processedBulkCalsReqCollection.find(Filters.and(
      Filters.equal("isParent", true),
      Filters.equal("complete", false)))
      .sort(Sorts.ascending("_id"))
      .collect()
      .toFuture().map(_.toList)

    val result: Future[List[Future[List[ProcessReadyCalculationRequest]]]] = incompleteBulk.map {
      bulkList =>
        bulkList.map {
          bulkRequest => {
            processReadyCalsReqCollection.find[ProcessReadyCalculationRequest](
              Filters.and(
                Filters.equal("isChild", true),
                Filters.equal("hasValidationErrors", false),
                Filters.equal("bulkId", bulkRequest._id),
                Filters.equal("hasValidRequest", true),
                Filters.equal("hasResponse", false)
              )
            ).limit(applicationConfiguration.bulkProcessingBatchSize)
              .collect()
              .toFuture().map(_.toList)
          }
        }
    }

    result.flatMap {x =>
      val sequenced = Future.sequence(x).map {
        thing => Some(thing.flatten)
      }
      logger.debug(s"[BulkCalculationRepository][findRequestsToProcess] SUCCESS")

      sequenced onComplete {
        case _ => metrics.findRequestsToProcessTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      }
      sequenced
    }
      .recover {
        case e => logger.error(s"[BulkCalculationRepository][findRequestsToProcess] failed: ${e.getMessage}")
          metrics.findRequestsToProcessTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
          None
      }

  }

  def findAndComplete1() = {

    val startTime = System.currentTimeMillis()
    implicit val hc = HeaderCarrier()
    logger.debug("[BulkCalculationRepository][findAndComplete]: starting ")
    val result: Future[Boolean] = for {
      processedBulkCalReqList <- getProcessedBulkCalRequestList(startTime)

      booleanList <-  Future.sequence(processedBulkCalReqList.map { request =>
        val req: ProcessedBulkCalculationRequest = request.getOrElse(sys.error("Processed Bulk calculation Request missing"))
        logger.debug(s"Got request $request")
        updateRequestAndSendEmailAndEvent(req)
      })
      boolean = booleanList.foldLeft(true)(_ && _)
    } yield boolean

    result.map{ res =>
      logTimer(startTime)
      res
    }
      .recover {
        case e =>
          logger.error(s"[BulkCalculationRepository][findAndComplete] ${e.getMessage}", e)
          logTimer(startTime)
          false
      }

  }

  private def updateRequestAndSendEmailAndEvent(req: ProcessedBulkCalculationRequest)(implicit hc: HeaderCarrier)= {
    for {
      updatedRequest <- updateBulkCalculationByUploadRef(req)
      _ <- updateCalculationByBulkId(req)
    } yield {
      sendEvent(req)
      emailConnector.sendProcessedTemplatedEmail(ProcessedUploadTemplate(
        updatedRequest.email,
        updatedRequest.reference,
        updatedRequest.timestamp.toLocalDate,
        updatedRequest.userId))
      true
    }
  }

  def logTimer(startTime: Long)=  metrics.findAndCompleteTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)

  def sendEvent(req: ProcessedBulkCalculationRequest)(implicit hc: HeaderCarrier) = {
      auditConnector
        .sendEvent(BulkEvent(req))
        .map(_ => ())
        .recover {
          case e: Throwable => logger.error(s"[BulkCalculationRepository][findAndComplete] resultsEventResult: ${e.getMessage}", e)}
    }


  def getProcessedBulkCalRequestList(startTime: Long) = for {
    incompleteBulk <- findIncompleteBulk()
    _ = logTimer(startTime)
    processedBulkCalcReqOpt <- Future.sequence(incompleteBulk.map { req =>
      for {
        countedDocs <- countChildDocWithValidRequest(req._id)
        processedBulkCalcOpt <- if (countedDocs == 0) updateCalculationRequestsForProcessedBulkReq(req) else Future.successful(None)
      } yield (processedBulkCalcOpt)
    })
  } yield processedBulkCalcReqOpt.filter(_.isDefined)

  private def updateCalculationRequestsForProcessedBulkReq(req: ProcessedBulkCalculationRequest ) = {
    val childrenStartTime = System.currentTimeMillis()
    for {
      processedChildren <- findProcessedChildren(req._id)
      _ = metrics.findAndCompleteChildrenTimer(System.currentTimeMillis() - childrenStartTime, TimeUnit.MILLISECONDS)
    } yield Some(req.copy(calculationRequests = processedChildren))

  }

  def findAndComplete(): Future[Boolean] = {

    val startTime = System.currentTimeMillis()

    logger.debug("[BulkCalculationRepository][findAndComplete]: starting ")
    val incompleteBulk = processedBulkCalsReqCollection.find(Filters.and(
      Filters.equal("isParent", true),
      Filters.equal("complete", false)))
      .sort(Sorts.ascending("_id"))
      .collect()
      .toFuture().map(_.toList)
    incompleteBulk onComplete {
      case _ => metrics.findAndCompleteParentTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
    }
    val findResult: Future[List[Option[ProcessedBulkCalculationRequest]]] = incompleteBulk.flatMap {
      bulkList =>
        Future.sequence(bulkList.par.map {
          bulkRequest => {
            val childrenStartTime = System.currentTimeMillis()
            val criteria = Filters.and(
              Filters.equal("bulkId" , bulkRequest._id),
              Filters.eq("isChild" , true),
              Filters.eq("hasResponse" , false),
              Filters.eq("hasValidationErrors", false),
              Filters.eq("hasValidRequest" , true))

            processReadyCalsReqCollection.countDocuments(criteria).toFuture().flatMap { result =>
              if(result == 0) {
                val processedChildren = processReadyCalsReqCollection.find(Filters.and(
                  Filters.eq("isChild" , true),
                  Filters.eq("bulkId" , bulkRequest._id)))
                  .toFuture().map(_.toList)
                  .map { allChildren =>
                    Some(bulkRequest.copy(calculationRequests = allChildren))
                  }
                processedChildren onComplete {
                  case _ => metrics.findAndCompleteChildrenTimer(System.currentTimeMillis() - childrenStartTime, TimeUnit.MILLISECONDS)
                }
                processedChildren
              } else Future.successful(None)
            }
          }
        }.toList
        ) map { br => br.filter(z => z.isDefined)
        }
    }


    logger.debug(s"[BulkCalculationRepository][findAndComplete]: processing")

    implicit val dateTimeFormat = MongoJodaFormats.localDateTimeFormat
    val result: Future[Boolean] = findResult.flatMap { requests =>
      Future.sequence(requests.map { request =>
        logger.debug(s"Got request $request")

        val totalRequests: Int = request.get.calculationRequests.size
        val failedRequests = request.get.failedRequestCount

        val selector = Filters.eq("uploadReference", request.get.uploadReference)
        val modifier =

          Updates.combine(
            Updates.set("complete", true),
            Updates.set("total", totalRequests),
            Updates.set("failed",failedRequests),
            Updates.set("createdAt", Codecs.toBson(LocalDateTime.now())),
            Updates.set("processedDateTime", Codecs.toBson(LocalDateTime.now().toString))
          )
        val result: Future[ProcessedBulkCalculationRequest] = processedBulkCalsReqCollection.findOneAndUpdate(selector, modifier).toFuture()

        result.map {
          writeResult => logger.debug(s"[BulkCalculationRepository][findAndComplete] : { result : $writeResult }")
            // $COVERAGE-OFF$
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
            resultsEventResult.failed.foreach({
              case e: Throwable => logger.error(s"[BulkCalculationRepository][findAndComplete] resultsEventResult: ${e.getMessage}", e)
            })

            val childSelector = Filters.eq("bulkId", request.get._id)
            val childModifier = Updates.set("createdAt", Codecs.toBson(LocalDateTime.now()))
            val childResult = processedBulkCalsReqCollection
              .findOneAndUpdate(childSelector, childModifier)
              .toFuture()


            childResult.map {
              childWriteResult => logger.debug(s"[BulkCalculationRepository][findAndComplete] childResult: $childWriteResult")
            }

            emailConnector.sendProcessedTemplatedEmail(ProcessedUploadTemplate(
              request.get.email,
              request.get.reference,
              request.get.timestamp.toLocalDate,
              request.get.userId))

            // $COVERAGE-ON$
            true
        }
      }).map {
        x => x.foldLeft(true) {
          _ && _
        }
      }
    }
    result onComplete {
      case _ =>
        metrics.findAndCompleteTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
    }
    result.map { _ => true
    }.recover {case e =>
      metrics.findAndCompleteTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      logger.error(s"[BulkCalculationRepository][findAndComplete] ${e.getMessage}", e)
      false
    }
    }

  private def findIncompleteBulk(): Future[List[ProcessedBulkCalculationRequest]] = processedBulkCalsReqCollection.find(Filters.and(
    Filters.eq("isParent", true),
    Filters.eq("complete", false)))
    .sort(Sorts.ascending("_id"))
    .collect()
    .toFuture().map(_.toList)

  private def countChildDocWithValidRequest(bulkRequestId: String): Future[Long] = {
    val criteria = Filters.and(
      Filters.equal("bulkId" , bulkRequestId),
      Filters.eq("isChild" , true),
      Filters.eq("hasResponse" , false),
      Filters.eq("hasValidationErrors", false),
      Filters.eq("hasValidRequest" , true))
    processReadyCalsReqCollection.countDocuments(criteria).toFuture()
  }

  private def findProcessedChildren(bulkRequestId: String): Future[List[ProcessReadyCalculationRequest]] = {
    val filter = Filters.and(
      Filters.eq("isChild" , true),
      Filters.eq("bulkId" , bulkRequestId))
    processReadyCalsReqCollection.find(filter).toFuture().map(_.toList)
  }

  private def updateBulkCalculationByUploadRef(request: ProcessedBulkCalculationRequest): Future[ProcessedBulkCalculationRequest] = {

    val totalRequests: Int = request.calculationRequests.size
    val failedRequests = request.failedRequestCount
    val selector = Filters.eq("uploadReference", request.uploadReference)
    val modifier =
      Updates.combine(
        Updates.set("complete", true),
        Updates.set("total", totalRequests),
        Updates.set("failed",failedRequests),
        Updates.set("createdAt", LocalDateTime.now()),
        Updates.set("processedDateTime", LocalDateTime.now().toString)
      )
    processedBulkCalsReqCollection.findOneAndUpdate(selector, modifier).toFuture()
  }

  private def updateCalculationByBulkId(request: ProcessedBulkCalculationRequest): Future[BulkCalculationRequest] = {
    val childSelector = Filters.eq("bulkId", request._id)
    val childModifier = Updates.set("createdAt", LocalDateTime.now())
    collection
      .findOneAndUpdate(childSelector, childModifier)
      .toFuture()
      .map { result =>
        logger.debug(s"[BulkCalculationRepository][findAndComplete] childResult: $result")
        result
      }
  }


  override def insertBulkDocument(bulkCalculationRequest: BulkCalculationRequest): Future[Boolean] = {

    logger.info(s"[BulkCalculationRepository][insertBulkDocument][numDocuments]: ${bulkCalculationRequest.calculationRequests.size}")

    val startTime = System.currentTimeMillis()

    findDuplicateUploadReference(bulkCalculationRequest.uploadReference).flatMap {

      case true => logger.debug(s"[BulkCalculationRepository][insertBulkDocument] Duplicate request found (${bulkCalculationRequest.uploadReference})")
        Future.successful(false)
      case false => {

        val strippedBulk = ProcessedBulkCalculationRequest(new ObjectId().toString,
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

        val insertResult: Future[Option[InsertManyResult]] = processedBulkCalsReqCollection
          .insertOne(strippedBulk)
          .flatMap(_ =>
            processReadyCalsReqCollection.insertMany(bulkDocs))
          .headOption()


        insertResult onComplete {
          case _ => metrics.insertBulkDocumentTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
        }
        insertResult.map { insertManyResultOpt =>
          insertManyResultOpt.fold(false) { insertManyResult =>
            if (insertManyResult.wasAcknowledged()) {
              logger.debug(s"[BulkCalculationRepository][insertBulkDocument] $insertManyResult")
              true
            } else {
              logger.error("Error inserting document")
              false
            }
          }
        }.recover {
          case e =>logger.error(s"[BulkCalculationRepository][insertBulkDocument] failed: ${e.getMessage}")
            false
        }

      }
    }

    }


  private def findDuplicateUploadReference(uploadReference: String): Future[Boolean] = {
    bulkCalcReqCollection.find(Filters.equal("uploadReference", uploadReference))
      .toFuture().map(_.toList)
      .map { result =>
        logger.debug(s"[BulkCalculationRepository][findDuplicateUploadReference] uploadReference : $uploadReference, result: ${result.nonEmpty}")
        result.nonEmpty
      }.recover {
      case e =>  logger.error(s"[BulkCalculationRepository][findDuplicateUploadReference] ${e.getMessage} ($uploadReference)", e)
        false
    }
  }
}

trait BulkCalculationRepository {

  def metrics: ApplicationMetrics
  val emailConnector: EmailConnector
  val auditConnector: AuditConnector
  def insertResponseByReference(reference: String, lineId: Int, calculationResponse: GmpBulkCalculationResponse): Future[Boolean]

  def findByReference(reference: String, filter: CsvFilter = CsvFilter.All): Future[Option[ProcessedBulkCalculationRequest]]

  def findSummaryByReference(reference: String): Future[Option[BulkResultsSummary]]

  def findByUserId(userId: String): Future[Option[List[BulkPreviousRequest]]]

  def findRequestsToProcess(): Future[Option[List[ProcessReadyCalculationRequest]]]

  def findAndComplete(): Future[Boolean]

  def insertBulkDocument(bulkCalculationRequest: BulkCalculationRequest): Future[Boolean]
}
