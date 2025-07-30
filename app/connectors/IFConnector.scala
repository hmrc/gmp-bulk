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
import play.api.Logging
import play.api.http.Status.{OK, TOO_MANY_REQUESTS, UNPROCESSABLE_ENTITY}
import uk.gov.hmrc.circuitbreaker.{CircuitBreakerConfig, UsingCircuitBreaker}
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URL
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}


class IFConnector @Inject()(
                             http: HttpClientV2,
                             servicesConfig: ServicesConfig,
                             val metrics: ApplicationMetrics,
                             applicationConfig: ApplicationConfiguration,
                             implicit val ec: ExecutionContext
                           ) extends Logging with UsingCircuitBreaker {

  val serviceKey = servicesConfig.getConfString("ifs.key", "")
  val serviceEnvironment = servicesConfig.getConfString("ifs.environment", "")

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

  private def ifsHeaders = Seq(
    "Gov-Uk-Originator-Id" -> servicesConfig.getConfString("nps.originator-id",""),
    "Authorization" -> s"Bearer $serviceKey",
    "Environment" -> serviceEnvironment
  )

  def calculate(request: ValidCalculationRequest): Future[CalculationResponse]= {
    val queryParams = request.queryParams
    val queryString = queryParams.map { case (key, value) => s"$key=$value" }.mkString("&")
    val url = s"$calcURI${request.ifUri}?$queryString"
    val logPrefix = "[IFConnector][calculate]"
    logger.info(s"$logPrefix contacting IF at $url")

    val startTime = System.currentTimeMillis()

    http.get(new URL(url))
      .setHeader(ifsHeaders:_*)
      .execute[HttpResponse]
      .map { response =>

      metrics.ifRegisterStatusCode(response.status.toString)
      metrics.ifConnectionTime(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)

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
    }
  }
}
