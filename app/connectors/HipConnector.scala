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

package connectors

import java.time.{Instant, ZoneOffset}
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.google.inject.{Inject, Singleton}
import config.{AppConfig, ApplicationConfiguration, Constants}
import metrics.ApplicationMetrics
import models.{HipCalculationFailuresResponse, HipCalculationRequest, HipCalculationResponse}

import play.api.Logging
import play.api.http.Status
import play.api.http.Status.{BAD_GATEWAY, BAD_REQUEST, FORBIDDEN, INTERNAL_SERVER_ERROR, NOT_FOUND, OK, TOO_MANY_REQUESTS}
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import uk.gov.hmrc.circuitbreaker.{CircuitBreakerConfig, UsingCircuitBreaker}
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.{BadGatewayException, GatewayTimeoutException, HeaderCarrier, HttpReads, HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.audit.AuditExtensions._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{DataEvent, EventTypes}
import utils.LoggingUtils

import java.time.format.DateTimeFormatter
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class HipConnector @Inject()(
  appConfig: AppConfig,
  metrics: ApplicationMetrics,
  http: HttpClientV2,
  auditConnector: AuditConnector,
  applicationConfig: ApplicationConfiguration
)(implicit ec: ExecutionContext) extends Logging with UsingCircuitBreaker {

  private val hipBaseUrl: String = appConfig.hipUrl
  private val calcURI = s"$hipBaseUrl/ni/gmp/calculation"
  private val MaxBodyLengthForLogging = 300
  class BreakerException extends Exception

  private def buildHeadersV1: Seq[(String, String)] =
    Seq(
      Constants.OriginatorIdKey -> appConfig.originatorIdValue,
      "correlationId" -> getCorrelationId,
      "Authorization" -> s"Basic ${appConfig.hipAuthorisationToken}",
      appConfig.hipEnvironmentHeader,
      "X-Originating-System" -> Constants.XOriginatingSystemHeader,
      "X-Receipt-Date" -> DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)),
      "X-Transmitting-System" -> Constants.XTransmittingSystemHeader
    )

  private def getCorrelationId: String = UUID.randomUUID().toString

  override protected def circuitBreakerConfig: CircuitBreakerConfig = {
    CircuitBreakerConfig(
      "HipConnector",
      applicationConfig.numberOfCallsToTriggerStateChange,
      applicationConfig.unavailablePeriodDuration,
      applicationConfig.unstablePeriodDuration
    )
  }

  

  override protected def breakOnException(t: Throwable): Boolean = {
    t match {
      case _: BreakerException => true
      case _: BadGatewayException => true
      case _: GatewayTimeoutException => true
      case e: UpstreamErrorResponse if e.statusCode >= 500 && e.statusCode < 600 => true
      case _ => false
    }
  }

  // Returns Right(success) for 200 and Left(failures) for 422
  def calculateOutcome(userId: String, request: HipCalculationRequest)(implicit hc: HeaderCarrier): Future[Either[HipCalculationFailuresResponse, HipCalculationResponse]] = {
    doAudit(
      "gmpCalculation",
      userId,
      request.schemeContractedOutNumber,
      Some(request.nationalInsuranceNumber),
      Some(request.surname),
      Some(request.firstForename)
    )

    val startTime = System.currentTimeMillis()
    val headers = buildHeadersV1

    withCircuitBreaker(
      http.post(url"$calcURI")
        .setHeader(headers*)
        .withBody(Json.toJson(request))
        .execute[HttpResponse]
        .map[Either[HipCalculationFailuresResponse, HipCalculationResponse]] { response =>
          val took = System.currentTimeMillis() - startTime
          metrics.hipConnectionTime(took, TimeUnit.MILLISECONDS)
          metrics.hipRegisterStatusCode(response.status.toString)

          val cid = response.headers.get("correlationId").flatMap(_.headOption).getOrElse("n/a")
          logger.debug(s"[HipConnector][calculate] Response status: ${response.status}, correlationId: $cid")

          response.status match {
            case OK =>
              metrics.hipRegisterSuccessfulRequest()
              Right(parseHipCalculationSuccess(response))
            case Status.UNPROCESSABLE_ENTITY =>
              metrics.hipRegisterSuccessfulRequest()
              Left(parseHipFailures(response))
            case _ =>
              metrics.hipRegisterFailedRequest()
              throw toUpstreamError(response)
          }
        }
    )
  }

  private def parseHipCalculationSuccess(response: HttpResponse): HipCalculationResponse = {
    logger.debug(s"[HipConnector][calculate] Response body: ${LoggingUtils.redactCalculationData(response.body)}")

    scala.util.Try(Json.parse(response.body)).toOption match {
      case Some(js) =>
        js.validate[HipCalculationResponse] match {
          case JsSuccess(value, _) =>
            logger.debug(s"[HipConnector] Successfully parsed HIP calculation response")
            value
          case JsError(errors) =>
            val errorFields = errors.map(_._1.toString).mkString(", ")
            val detailedMsg = s"HIP returned invalid JSON (status: ${response.status}). Failed to parse fields: $errorFields"
            logger.error(s"[HipConnector][calculate] $detailedMsg")
            metrics.hipRegisterFailedRequest()
            throw UpstreamErrorResponse(detailedMsg, BAD_GATEWAY, BAD_GATEWAY)
        }
      case None =>
        val contentType = response.headers.get("Content-Type").flatMap(_.headOption).getOrElse("unknown")
        val correlationId = response.headers.get("correlationId").flatMap(_.headOption).getOrElse("n/a")
        val bodyLen = Option(response.body).map(_.length).getOrElse(0)
        val detailedMsg = s"HIP returned non-JSON body. Status: ${response.status}, correlationId: $correlationId, Content-Type: $contentType, Body length: $bodyLen (omitted)"
        logger.error(s"[HipConnector][calculate] $detailedMsg")
        metrics.hipRegisterFailedRequest()
        throw UpstreamErrorResponse(detailedMsg, BAD_GATEWAY, BAD_GATEWAY)
    }
  }

  private def parseHipFailures(response: HttpResponse): HipCalculationFailuresResponse = {
    logger.debug(s"[HipConnector][calculate][422] Response body: ${LoggingUtils.redactCalculationData(response.body)}")
    scala.util.Try(Json.parse(response.body)).toOption match {
      case Some(js) =>
        js.validate[HipCalculationFailuresResponse] match {
          case JsSuccess(value, _) => value
          case JsError(errors) =>
            val errorFields = errors.map(_._1.toString).mkString(", ")
            val detailedMsg = s"HIP 422 returned invalid JSON. Failed to parse fields: $errorFields"
            logger.error(s"[HipConnector][calculate] $detailedMsg")
            throw UpstreamErrorResponse(detailedMsg, BAD_GATEWAY, BAD_GATEWAY)
        }
      case None =>
        val contentType = response.headers.get("Content-Type").flatMap(_.headOption).getOrElse("unknown")
        val correlationId = response.headers.get("correlationId").flatMap(_.headOption).getOrElse("n/a")
        val bodyLen = Option(response.body).map(_.length).getOrElse(0)
        val detailedMsg = s"HIP returned non-JSON body with 422. correlationId: $correlationId, Content-Type: $contentType, Body length: $bodyLen (omitted)"
        logger.error(s"[HipConnector][calculate] $detailedMsg")
        throw UpstreamErrorResponse(detailedMsg, BAD_GATEWAY, BAD_GATEWAY)
    }
  }

  private def toUpstreamError(response: HttpResponse): UpstreamErrorResponse = {
    val status = response.status
    val (message, reportAs) = status match {
      case BAD_REQUEST => ("Bad Request", BAD_REQUEST)
      case FORBIDDEN   => ("Forbidden", FORBIDDEN)
      case NOT_FOUND   => ("Not Found", NOT_FOUND)
      case TOO_MANY_REQUESTS => 
        logger.warn(s"[HipConnector] Rate limited by HIP service")
        throw new BreakerException
      case status if status >= 500 && status < 600 => 
        logger.error(s"[HipConnector] HIP service error: ${response.body.take(500)}")
        (s"Unexpected error (Status: $status)", INTERNAL_SERVER_ERROR)
      case s => (s"Client error (Status: $s)", s)
    }

    val errorResponse = UpstreamErrorResponse(
      message = s"HIP connector calculate failed: $message",
      statusCode = status,
      reportAs = reportAs,
      headers = response.headers
    )

    // For server errors, throw BreakerException to trigger circuit breaker
    if (status >= 500 && status < 600) {
      logger.error(s"[HipConnector] Failing fast with circuit breaker due to HTTP $status")
      throw new BreakerException
    }

    errorResponse
  }

  private def doAudit(
    auditTag: String,
    userId: String,
    scon: String,
    nino: Option[String],
    surname: Option[String],
    firstForename: Option[String]
  )(implicit hc: HeaderCarrier): Unit = {
    val correlationId = hc.requestId.map(_.value).orElse(hc.sessionId.map(_.value)).getOrElse("unknown")
    val auditDetails: Map[String, String] = Map(
      "userId" -> userId,
      "scon" -> scon,
      "nino" -> nino.getOrElse(""),
      "firstName" -> firstForename.getOrElse(""),
      "surname" -> surname.getOrElse(""),
      "correlationId" -> correlationId
    )

    auditConnector.sendEvent(
      DataEvent(
        auditSource = "gmp-bulk",
        auditType = EventTypes.Succeeded,
        tags = hc.toAuditTags(auditTag, "N/A"),
        detail = hc.toAuditDetails() ++ auditDetails
      )
    ).failed.foreach {
      case e: Throwable => logger.warn("[HipConnector][doAudit] Audit failed", e)
    }
  }
}
