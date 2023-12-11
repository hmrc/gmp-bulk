/*
 * Copyright 2023 HM Revenue & Customs
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
import play.api.Logging
import play.api.http.Status.{INTERNAL_SERVER_ERROR, LOCKED, NOT_FOUND, OK, TOO_MANY_REQUESTS}
import uk.gov.hmrc.circuitbreaker.{CircuitBreakerConfig, UsingCircuitBreaker}
import uk.gov.hmrc.http.{BadGatewayException, GatewayTimeoutException, HeaderCarrier, HttpClient, HttpReads, HttpResponse, NotFoundException, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}


class IFConnector @Inject()(
                             http: HttpClient,
                             servicesConfig: ServicesConfig,
                             val metrics: ApplicationMetrics,
                             applicationConfig: ApplicationConfiguration
                           )(implicit ec: ExecutionContext) extends Logging with UsingCircuitBreaker {

  val serviceKey = servicesConfig.getConfString("ifs.key", "")
  val serviceEnvironment = servicesConfig.getConfString("ifs.environment", "")
  lazy val citizenDetailsUrl: String = servicesConfig.baseUrl("citizen-details")

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  implicit val httpReads: HttpReads[HttpResponse] = new HttpReads[HttpResponse] {
    override def read(method: String, url: String, response: HttpResponse) = response
  }


  lazy val serviceURL = servicesConfig.baseUrl("nps")
  val baseURI = "pensions/individuals/gmp"
  val calcURI = s"$serviceURL/$baseURI"

  class BreakerException extends Exception

  override protected def circuitBreakerConfig: CircuitBreakerConfig = {
    CircuitBreakerConfig("IFConnector",
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

  private def ifHeaders = Seq(
    "Gov-Uk-Originator-Id" -> servicesConfig.getConfString("nps.originator-id",""),
    "Authorization" -> s"Bearer $serviceKey",
    "Environment" -> serviceEnvironment
  )

  def calculate(request: ValidCalculationRequest): Future[CalculationResponse]= {
    val url = calcURI + request.ifUri
    val logPrefix = "[IFConnector][calculate]"
    logger.info(s"$logPrefix contacting IF at $url")

    val startTime = System.currentTimeMillis()

    withCircuitBreaker(http.GET[HttpResponse](url, request.queryParams, headers = ifHeaders)
      (hc = hc, rds = httpReads, ec = ec).map { response =>

      metrics.ifRegisterStatusCode(response.status.toString)
      metrics.ifConnectionTime(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)

      response.status match {
        case OK =>
          metrics.ifRegisterSuccessfulRequest()
          response.json.as[CalculationResponse]

        case errorStatus: Int => {
          logger.error(s"$logPrefix IF URI $url returned code $errorStatus and response body: ${response.body}")
          metrics.ifRegisterFailedRequest()

          errorStatus match {
            case status if status >= 500 && status < 600 =>
              throw UpstreamErrorResponse(s"$logPrefix Call to Individual Pension calculation on NPS Service failed with status code ${status}", status, status)
            case TOO_MANY_REQUESTS | 499 => throw new BreakerException
            case _ => throw UpstreamErrorResponse(s"$logPrefix An error status $errorStatus was encountered", errorStatus, errorStatus)
          }
        }
      }
    })(hc=hc)
  }

  def getPersonDetails(nino: String): Future[DesGetResponse] = {

    val desHeaders = Seq(
      "Gov-Uk-Originator-Id" -> servicesConfig.getConfString("des.originator-id",""),
      "Authorization" -> s"Bearer ${servicesConfig.getConfString("nps.key", "")}",
      "Environment" -> servicesConfig.getConfString("nps.environment", "")
    )

    val startTime = System.currentTimeMillis()
    val url = s"$citizenDetailsUrl/citizen-details/$nino/etag"

    logger.debug(s"[getPersonDetails] Contacting DES at $url")

    http.GET[HttpResponse](url, headers = desHeaders)(implicitly[HttpReads[HttpResponse]], hc, ec = ec) map { response =>
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
      case e: NotFoundException => DesGetNotFoundResponse
      case e: Exception =>
        logger.error(s"[getPersonDetails] Exception thrown getting individual record from DES: $e")
        metrics.mciErrorResult()
        DesGetErrorResponse(e)
    }
  }
}
