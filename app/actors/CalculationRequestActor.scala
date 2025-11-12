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

package actors

import com.google.inject.Inject
import config.{AppConfig, ApplicationConfiguration}
import connectors.{DesConnector, DesGetHiddenRecordResponse, HipConnector, IFConnector}
import metrics.ApplicationMetrics
import models.{CalculationResponse, GmpBulkCalculationResponse, HipCalculationFailuresResponse, HipCalculationRequest, HipCalculationResponse, ProcessReadyCalculationRequest, ValidCalculationRequest}
import org.apache.pekko.actor._
import play.api.Logging
import play.api.http.Status._
import repositories.BulkCalculationMongoRepository
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait CalculationRequestActorComponent {
  val desConnector: DesConnector
  val ifConnector: IFConnector
  val hipConnector: HipConnector
  val repository: BulkCalculationMongoRepository
  val metrics: ApplicationMetrics
  val applicationConfig: ApplicationConfiguration
  val appConfig: AppConfig
  implicit val ec: ExecutionContext
}

class CalculationRequestActor extends Actor with ActorUtils with Logging {

  self: CalculationRequestActorComponent =>

  override def receive: Receive = {
    case request: ProcessReadyCalculationRequest =>
      val origSender = sender()
      val startTime = System.currentTimeMillis()

      val backend: String =
        if (appConfig.isIfsEnabled) "IF"
        else if (appConfig.isHipEnabled) "HIP"
        else "DES"

      val processingFuture = desConnector.getPersonDetails(request.validCalculationRequest.get.nino).flatMap {
        case DesGetHiddenRecordResponse =>
          // Handle hidden record case
          Future.successful(GmpBulkCalculationResponse(List(), LOCKED, None, None, None, containsErrors = true))

        case _ =>
          // 2. Choose and call the appropriate backend for calculation
          callBackend(request.validCalculationRequest.get).map {
            // 3a. Handle successful calculation from DES/IF
            case x: CalculationResponse =>
              GmpBulkCalculationResponse.createFromCalculationResponse(x)
            // 3b. Handle successful calculation from HIP
            case Right(hipResponse: HipCalculationResponse) =>
              GmpBulkCalculationResponse.createFromHipCalculationResponse(hipResponse)
            // 3c. Handle business validation failure from HIP
            case Left(hipFailures: HipCalculationFailuresResponse) =>
              GmpBulkCalculationResponse.createFromHipFailuresResponse(hipFailures)
          }
      }

      processingFuture.onComplete {
        // 4. Centralized completion handling (success and failure)
        case Success(gmpResponse) =>
          insertAndReply(request.bulkId, request.lineId, gmpResponse, startTime, origSender)

        case Failure(e: UpstreamErrorResponse) =>
          val cid = e.headers.get("correlationId").flatMap(_.headOption).getOrElse("n/a")
          logger.error(s"[CalculationRequestActor] Upstream failure (cid: $cid). Status: ${e.reportAs}, Error: $e")
          backend match {
            case "HIP" =>
              // Always insert an error response for HIP upstream failures (per spec expectations)
              val resp = createErrorResponse(e.reportAs)
              insertAndReply(request.bulkId, request.lineId, resp, startTime, origSender)
            case "IF" =>
              e.reportAs match {
                case BAD_REQUEST =>
                  insertAndReply(request.bulkId, request.lineId, createErrorResponse(BAD_REQUEST), startTime, origSender)
                case SERVICE_UNAVAILABLE | INTERNAL_SERVER_ERROR =>
                  origSender ! org.apache.pekko.actor.Status.Failure(e)
                case _ =>
                  origSender ! org.apache.pekko.actor.Status.Failure(e)
              }
            case _ /* DES */ =>
              e.reportAs match {
                case BAD_REQUEST =>
                  insertAndReply(request.bulkId, request.lineId, createErrorResponse(BAD_REQUEST), startTime, origSender)
                case INTERNAL_SERVER_ERROR =>
                  origSender ! org.apache.pekko.actor.Status.Failure(e)
                case _ =>
                  origSender ! org.apache.pekko.actor.Status.Failure(e)
              }
          }

        case Failure(be: connectors.HipConnector#BreakerException) =>
          logger.error(s"[CalculationRequestActor] HIP circuit breaker open. Error: $be")
          val resp = createErrorResponse(SERVICE_UNAVAILABLE)
          insertAndReply(request.bulkId, request.lineId, resp, startTime, origSender)

        // Fallback for circuit breaker to guard against type mismatch
        case Failure(e) if e.getClass.getName.endsWith("HipConnector$BreakerException") =>
          logger.error(s"[CalculationRequestActor] HIP circuit breaker open (fallback match). Error: $e")
          val resp = createErrorResponse(SERVICE_UNAVAILABLE)
          insertAndReply(request.bulkId, request.lineId, resp, startTime, origSender)

        case Failure(e) =>
          logger.error(s"[CalculationRequestActor] Unexpected error during processing: $e", e)
          origSender ! org.apache.pekko.actor.Status.Failure(e)
      }
    case STOP =>
      logger.info(s"[CalculationRequestActor] stop message received from ${sender()}")
      sender() ! STOP

    case e =>
      logger.warn(s"[CalculationRequestActor] received unknown message: $e")
      sender() ! org.apache.pekko.actor.Status.Failure(new IllegalArgumentException("Unsupported message type"))
  }

  private def callBackend(request: ValidCalculationRequest): Future[Any] = {
    Try {
      if (appConfig.isIfsEnabled) {
        ifConnector.calculate(request)
      } else if (appConfig.isHipEnabled) {
        val hipRequest = HipCalculationRequest.from(request)
        hipConnector.calculateOutcome(userId = "system", hipRequest)(HeaderCarrier())
      } else {
        desConnector.calculate(request)
      }
    } match {
      case Success(future) => future
      case Failure(e) => Future.failed(e) // Promote synchronous error to a failed Future
    }
  }

  private def createErrorResponse(status: Int): GmpBulkCalculationResponse = {
    GmpBulkCalculationResponse(List(), status, None, None, None, containsErrors = true)
  }

  private def insertAndReply(bulkId: String, lineId: Int, response: GmpBulkCalculationResponse, startTime: Long, recipient: ActorRef): Unit = {
    repository.insertResponseByReference(bulkId, lineId, response).map { result =>
      metrics.processRequest(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
      logger.debug(s"[CalculationRequestActor] Inserted response for bulkId: $bulkId, lineId: $lineId. Result: $result")
      recipient ! result

    }.recover {
      case e =>
        logger.error(s"[CalculationRequestActor] Failed to insert response for bulkId: $bulkId, lineId: $lineId. Error: $e", e)
        recipient ! org.apache.pekko.actor.Status.Failure(e) // Notify supervisor of DB failure
    }
  }
}

class DefaultCalculationRequestActor @Inject()(override val repository : BulkCalculationMongoRepository,
                                               override val desConnector : DesConnector,
                                               override val ifConnector: IFConnector,
                                               override val hipConnector: HipConnector,
                                               override val metrics : ApplicationMetrics,
                                               override val applicationConfig: ApplicationConfiguration,
                                               override val appConfig: AppConfig,
                                               override val ec: ExecutionContext
                                              ) extends CalculationRequestActor with CalculationRequestActorComponent {
}