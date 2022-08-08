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
import com.mongodb.client.model.UpdateOptions
import config.ApplicationConfiguration
import connectors.{EmailConnector, ProcessedUploadTemplate}
import events.BulkEvent
import metrics.ApplicationMetrics
import models._
import org.joda.time.LocalDateTime
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.{BsonDocument, ObjectId}
import org.mongodb.scala.model.{Filters, FindOneAndUpdateOptions, IndexModel, IndexOptions, Indexes, Sorts, Updates}
import org.mongodb.scala.result.UpdateResult
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.play.json.{Codecs, CollectionFactory, PlayMongoRepository}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJodaFormats
import uk.gov.hmrc.play.audit.http.connector.AuditConnector

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
        IndexModel(Indexes.descending("lineId"), IndexOptions().name("LineIdDesc").background(true)),
        IndexModel(Indexes.ascending("isParent"), IndexOptions().name("isParent")),
        IndexModel(Indexes.ascending("isParent","complete"), IndexOptions().name("isParentAndComplete")),
        IndexModel(Indexes.ascending("isChild", "hasValidRequest", "hasResponse", "hasValidationErrors"), IndexOptions().name("childQuery")),
        IndexModel(Indexes.ascending("isChild", "bulkId"), IndexOptions().name("childBulkIndex"))
      )
    ) with BulkCalculationRepository with Logging {

  override val auditConnector: AuditConnector = ac
  val bulkCalcReqCollection: MongoCollection[BulkCalculationRequest] = CollectionFactory.collection(mongo.database, collectionName, BulkCalculationRequest.formats)
  val processedBulkCalsReqCollection: MongoCollection[ProcessedBulkCalculationRequest] = CollectionFactory.collection(mongo.database, collectionName, ProcessedBulkCalculationRequest.formats)
  val processReadyCalsReqCollection: MongoCollection[ProcessReadyCalculationRequest] = CollectionFactory.collection(mongo.database, collectionName, ProcessReadyCalculationRequest.formats)
  val bulkPreviousReqCollection: MongoCollection[BulkPreviousRequest] = CollectionFactory.collection(mongo.database, collectionName, BulkPreviousRequest.formats)


  // $COVERAGE-OFF$
  {

    val childrenEnumerator: Future[Seq[BsonDocument]] =
      mongo.database.getCollection[BsonDocument](collectionName = collectionName).find(Filters.and(
        Filters.exists(fieldName = "bulkId", exists = true),
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
              Updates.set("hasValidationErrors", hasValidationErrors)
            ),
            options = FindOneAndUpdateOptions().upsert(true)
          )
      }
    }

  }
  // $COVERAGE-ON$


  override def insertResponseByReference(bulkId: String, lineId: Int, calculationResponse: GmpBulkCalculationResponse): Future[Boolean] = {
    val startTime = System.currentTimeMillis()
    updateResponse(bulkId, lineId, calculationResponse).map {
      lastError => logTimer(startTime)
        logger.debug(s"[BulkCalculationRepository][insertResponseByReference] bulkResponse: $calculationResponse, result : $lastError ")
        true
    } recover {
      // $COVERAGE-OFF$
      case e => logger.info(s"Failed to insertResponseByReference:: ${e.getMessage}", e)
        logTimer(startTime)
        false
      // $COVERAGE-ON$
    }
  }

  private def updateResponse(bulkId: String, lineId: Int, calculationResponse: GmpBulkCalculationResponse ) = {
    val selector = Filters.and(
      Filters.equal("bulkId", bulkId),
      Filters.equal("lineId", lineId))
    val modifier = Updates.combine(
      Updates.set("calculationResponse", Codecs.toBson(calculationResponse)),
      Updates.set("hasResponse", true))
    processReadyCalsReqCollection.findOneAndUpdate(selector, modifier).toFuture()

  }

  override def findByReference(uploadReference: String, csvFilter: CsvFilter = CsvFilter.All): Future[Option[ProcessedBulkCalculationRequest]] = {
    val startTime = System.currentTimeMillis()
      findByUploadRef(uploadReference).flatMap {
      case Some(br) => logTimer(startTime)
        findByCsvFilterAndRequest(br, csvFilter)
      case _ => logTimer(startTime)
        logger.debug(s"[BulkCalculationRepository][findByReference] uploadReference: $uploadReference, result: No ProcessedBulkCalculationRequest found  ")
        Future.successful(None)
    }
  }

  private def findByUploadRef(uploadReference: String) = {
    processedBulkCalsReqCollection
      .find(Filters.equal("uploadReference", uploadReference))
      .headOption()
  }

  private def findByCsvFilterAndRequest(br: ProcessedBulkCalculationRequest, csvFilter: CsvFilter ) = {
    val query = createQuery(csvFilter, br)
    processReadyCalsReqCollection.find(query)
      .sort(Sorts.ascending("lineId"))
      .collect()
      .toFuture()
      .map { calcRequests =>
        logger.debug(s"[BulkCalculationRepository][findByCsvFilterAndRequest], request: $br ")
        Some(br.copy(calculationRequests = calcRequests.toList))
      }.recover { case e =>
      logger.error(s"[BulkCalculationRepository][findByCsvFilterAndRequest] error: ${e.getMessage}")
      None
    }
  }


  private def createQuery(csvFilter: CsvFilter, br: ProcessedBulkCalculationRequest) = csvFilter match {
    case CsvFilter.Failed => Filters.and(
      Filters.equal("bulkId", br._id),
      Filters.or(
        Filters.exists("validationErrors"),
        Filters.equal("calculationResponse.containsErrors", true)
      )
    )
    case CsvFilter.Successful => Filters.and(
      Filters.equal(fieldName = "bulkId", value = br._id),
      Filters.exists(fieldName = "validationErrors", exists = false),
      Filters.equal(fieldName = "calculationResponse.containsErrors", value = false)
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

    result.map { brs =>
      logTimer(startTime)
      logger.debug(s"[BulkCalculationRepository][findSummaryByReference] uploadReference : $uploadReference, result: $brs")
      brs.headOption
    }.recover { case e =>
      logTimer(startTime)
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
      .collect()
      .toFuture().map(_.toList)


    result.map { bulkRequest =>
      logTimer(startTime)
      logger.debug(s"[BulkCalculationRepository][findByUserId] userId : $userId, result: ${bulkRequest.size}")
      Some(bulkRequest)
    }.recover {
      case e => logTimer(startTime)
        logger.error(s"[BulkCalculationRepository][findByUserId] exception: ${e.getMessage}")
        None
    }
    }

  override def findRequestsToProcess(): Future[Option[List[ProcessReadyCalculationRequest]]] = {

    val startTime = System.currentTimeMillis()


    val result: Future[List[Future[List[ProcessReadyCalculationRequest]]]] = findIncompleteBulk().map {
      bulkList =>
        bulkList.map {
          bulkRequest => {
            findProcessReadyCalReq(bulkRequest)
          }
        }
    }

    result.flatMap {x =>
      val sequenced = Future.sequence(x).map {
        thing => Some(thing.flatten)
      }.map { res =>
        logTimer(startTime)
        res
      }
      logger.debug(s"[BulkCalculationRepository][findRequestsToProcess] SUCCESS")
      sequenced
    }
      .recover {
        case e => logger.error(s"[BulkCalculationRepository][findRequestsToProcess] failed: ${e.getMessage}")
          logTimer(startTime)
          None
      }

  }

  private def findProcessReadyCalReq(bulkRequest: ProcessedBulkCalculationRequest) = processReadyCalsReqCollection.find(
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



  override def findAndComplete() = {

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
    }.recover {
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
    implicit val dateTimeFormat = MongoJodaFormats.localDateTimeFormat
    val totalRequests: Int = request.calculationRequests.size
    val failedRequests = request.failedRequestCount
    val selector = Filters.eq("uploadReference", request.uploadReference)
    val modifier =
      Updates.combine(
        Updates.set("complete", true),
        Updates.set("total", totalRequests),
        Updates.set("failed",failedRequests),
        Updates.set("createdAt", Codecs.toBson(LocalDateTime.now())),
        Updates.set("processedDateTime", Codecs.toBson(LocalDateTime.now().toString))
      )
    processedBulkCalsReqCollection.findOneAndUpdate(selector, modifier).toFuture()
      .recover{
        case e => sys.error(s"[BulkCalculationRepository][updateBulkCalculationByUploadRef] exception: ${e.getMessage}")
      }
  }

  private def updateCalculationByBulkId(request: ProcessedBulkCalculationRequest): Future[UpdateResult] = {
    val childSelector = Filters.eq("bulkId", request._id)
    implicit val dateTimeFormat = MongoJodaFormats.localDateTimeFormat
    val childModifier = Updates.set("createdAt", Codecs.toBson(LocalDateTime.now()))
    processedBulkCalsReqCollection
      .updateMany(childSelector, childModifier)
      .toFuture()
      .map { result =>
       result
      }.recover {
      case e => sys.error(s"[BulkCalculationRepository][updateCalculationByBulkId] ${e.getMessage}")
    }
  }


  override def insertBulkDocument(bulkCalculationRequest: BulkCalculationRequest): Future[Boolean] = {

    logger.info(s"[BulkCalculationRepository][insertBulkDocument][numDocuments]: ${bulkCalculationRequest.calculationRequests.size}")

    val startTime = System.currentTimeMillis()

    findDuplicateUploadReference(bulkCalculationRequest.uploadReference).flatMap {
      case true =>
        logger.debug(s"[BulkCalculationRepository][insertBulkDocument] Duplicate request found (${bulkCalculationRequest.uploadReference})")
        Future.successful(false)
      case false =>
        insertProcessedBulkCal(bulkCalculationRequest)
          .map { insertManyResultOpt =>
            insertManyResultOpt.fold(false) { insertManyResult =>
              logTimer(startTime)
              if (insertManyResult.wasAcknowledged()) {
                logger.debug(s"[BulkCalculationRepository][insertBulkDocument] $insertManyResult")
                true
              } else {
                logger.error("Error inserting document")
                false
              }
            }
          }.recover {
          case e => logTimer(startTime)
            logger.error(s"[BulkCalculationRepository][insertBulkDocument] failed: ${e.getMessage}")
            false
        }
    }

  }

  private def createStrippedBulk(bulkCalculationRequest: BulkCalculationRequest) = ProcessedBulkCalculationRequest(new ObjectId().toString,
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

  private def createBulkDocs(bulkCalculationRequest: BulkCalculationRequest, id: String) = {
    val calculationRequests = bulkCalculationRequest.calculationRequests.map {
      request => request.copy(bulkId = Some(id))
    }

    calculationRequests map { c => ProcessReadyCalculationRequest(
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
  }

  private def insertProcessedBulkCal(bulkCalculationRequest: BulkCalculationRequest)= {
    val strippedBulk = createStrippedBulk(bulkCalculationRequest)
    val bulkDocs = createBulkDocs(bulkCalculationRequest, strippedBulk._id)
    processedBulkCalsReqCollection
      .insertOne(strippedBulk)
      .flatMap(_ =>
        processReadyCalsReqCollection.insertMany(bulkDocs))
      .headOption()
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
