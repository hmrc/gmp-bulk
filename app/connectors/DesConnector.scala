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

package connectors

import com.google.inject.Inject
import config.ApplicationConfiguration
import metrics.ApplicationMetrics
import models.{CalculationResponse, ValidCalculationRequest}
import play.api.http.Status._
import play.api.{Configuration, Logger}
import uk.gov.hmrc.circuitbreaker.{CircuitBreakerConfig, UsingCircuitBreaker}
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URL
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}

sealed trait DesGetResponse

sealed trait DesPostResponse

case object DesGetSuccessResponse extends DesGetResponse

case object DesGetHiddenRecordResponse extends DesGetResponse

case object DesGetNotFoundResponse extends DesGetResponse

case object DesGetUnexpectedResponse extends DesGetResponse

case class DesGetErrorResponse(e: Exception) extends DesGetResponse

class DesConnector @Inject()(val runModeConfiguration: Configuration,
                             http: HttpClientV2,
                             val metrics: ApplicationMetrics,
                             servicesConfig: ServicesConfig,
                             applicationConfig: ApplicationConfiguration, implicit val ec: ExecutionContext) extends UsingCircuitBreaker{

  val logger: Logger = Logger(this.getClass)

  implicit val httpReads: HttpReads[HttpResponse] = new HttpReads[HttpResponse] {
    override def read(method: String, url: String, response: HttpResponse) = response
  }

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  val serviceKey = servicesConfig.getConfString("nps.key", "")
  val serviceEnvironment = servicesConfig.getConfString("nps.environment", "")
  lazy val citizenDetailsUrl: String = servicesConfig.baseUrl("citizen-details")

  lazy val serviceURL = servicesConfig.baseUrl("nps")
  val baseURI = "pensions/individuals/gmp"
  val baseSconURI = "pensions/gmp/scon"

  val calcURI = s"$serviceURL/$baseURI"

  class BreakerException extends Exception

  def calculate(request: ValidCalculationRequest): Future[CalculationResponse] = {
    val queryParams = request.queryParams
    val queryString = queryParams.map { case (key, value) => s"$key=$value" }.mkString("&")
    val url = s"$calcURI${request.desUri}?$queryString"
    logger.info(s"[calculate] contacting DES at $url")

    val startTime = System.currentTimeMillis()
    withCircuitBreaker(http.get(new URL(url))
      .setHeader(npsHeaders:_*)
      .execute[HttpResponse]
      .map { response =>
        metrics.registerStatusCode(response.status.toString)
        metrics.desConnectionTime(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)

      response.status match {
        case OK | UNPROCESSABLE_ENTITY =>
          metrics.registerSuccessfulRequest()
          response.json.as[CalculationResponse]

        case errorStatus: Int => {
          logger.error(s"[calculate] DES URI $url returned code $errorStatus and response body: ${response.body}")
          metrics.registerFailedRequest()

          errorStatus match {
            case status if status >= 500 && status < 600 =>
              throw UpstreamErrorResponse(s"Call to Individual Pension calculation on NPS Service failed with status code ${status}", status, status)
            case TOO_MANY_REQUESTS => throw new BreakerException
            case 499 => throw new BreakerException
            case _ => throw UpstreamErrorResponse(s"An error status $errorStatus was encountered", errorStatus, errorStatus)
          }
        }
      }
    })
  }

  private def npsHeaders =Seq(
      "Gov-Uk-Originator-Id" -> servicesConfig.getConfString("nps.originator-id",""),
      "Authorization" -> s"Bearer $serviceKey",
      "Environment" -> serviceEnvironment)

  override protected def circuitBreakerConfig: CircuitBreakerConfig = {
    CircuitBreakerConfig("DesConnector",
      applicationConfig.numberOfCallsToTriggerStateChange,
      applicationConfig.unavailablePeriodDuration,
      applicationConfig.unstablePeriodDuration)
  }

  override protected def breakOnException(t: Throwable): Boolean = {
    t match {
      // $COVERAGE-OFF$
      case _: BreakerException => true
      case _: BadGatewayException => true
      case _: GatewayTimeoutException => true
      case _ => false
      // $COVERAGE-ON$
    }
  }

  def getPersonDetails(nino: String): Future[DesGetResponse] = {

    val desHeaders = Seq(
      "Gov-Uk-Originator-Id" -> servicesConfig.getConfString("des.originator-id",""),
      "Authorization" -> s"Bearer $serviceKey",
      "Environment" -> serviceEnvironment
    )

    val startTime = System.currentTimeMillis()
    val url = s"$citizenDetailsUrl/citizen-details/$nino/etag"

    logger.debug(s"[getPersonDetails] Contacting DES at $url")

    http.get(url"$url")
      .setHeader(desHeaders:_*)
      .execute[HttpResponse]
      .map { response =>
        metrics.mciConnectionTimer(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)

        response.status match {
          case LOCKED =>
            metrics.mciLockResult()
            DesGetHiddenRecordResponse
          case NOT_FOUND => DesGetNotFoundResponse
          case OK => DesGetSuccessResponse
          case INTERNAL_SERVER_ERROR => DesGetUnexpectedResponse
          case _ => DesGetUnexpectedResponse
        }

    } recover {
      case _: NotFoundException => DesGetNotFoundResponse
      case e: Exception =>
        logger.error(s"[getPersonDetails] Exception thrown getting individual record from DES: $e")
        metrics.mciErrorResult()
        DesGetErrorResponse(e)
    }
  }

}
