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

import config.{AppConfig, ApplicationConfiguration}
import metrics.ApplicationMetrics
import models.{EnumCalcRequestType, EnumRevaluationRate, HipCalculationRequest, HipCalculationResponse}
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfter, RecoverMethods}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.libs.json.Json
import play.api.test.Helpers.{BAD_REQUEST, FORBIDDEN, NOT_FOUND, OK}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, UpstreamErrorResponse}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.DataEvent
import utils.WireMockHelper

import scala.concurrent.{ExecutionContext, Future}

class HipConnectorSpec extends HttpClientV2Helper with GuiceOneServerPerSuite with WireMockHelper with BeforeAndAfter {
  implicit lazy val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val mockApplicationConfiguration: ApplicationConfiguration = mock[ApplicationConfiguration]
  val mockAppConfig: AppConfig = mock[AppConfig]
  val mockMetrics: ApplicationMetrics = mock[ApplicationMetrics]
  val mockAudit: AuditConnector = mock[AuditConnector]

  // AppConfig mocks
  when(mockAppConfig.hipUrl).thenReturn("http://localhost:9999")
  when(mockAppConfig.hipAuthorisationToken).thenReturn("dGVzdC1hdXRo") // base64 encoded token
  when(mockAppConfig.originatorIdKey).thenReturn("gov-uk-originator-id")
  when(mockAppConfig.originatorIdValue).thenReturn("HMRC-GMP")
  when(mockAppConfig.hipEnvironmentHeader).thenReturn("Environment" -> "local")
  when(mockAppConfig.isHipEnabled).thenReturn(true)

  class SUT extends HipConnector(
    appConfig = mockAppConfig,
    metrics = mockMetrics,
    http = mockHttp,
    auditConnector = mockAudit,
    applicationConfig = mockApplicationConfiguration
  ) {}
  "HipConnector" should {
    implicit val hc = HeaderCarrier()
    // Avoid NPE from doAudit by stubbing a successful audit result
    when(
      mockAudit.sendEvent(
        org.mockito.ArgumentMatchers.any[DataEvent]
      )(org.mockito.ArgumentMatchers.any[HeaderCarrier], org.mockito.ArgumentMatchers.any[scala.concurrent.ExecutionContext])
    ).thenReturn(scala.concurrent.Future.successful(AuditResult.Success))
    "for calculateOutcome" should {
      "return Right for status 200" in new SUT {
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        val successResponse = HipCalculationResponse("", "S2123456B", None, Some(""), Some(""), List.empty)
        val httpResponse = HttpResponse(OK, Json.toJson(successResponse).toString())
        requestBuilderExecute(Future.successful(httpResponse))
        calculateOutcome("system", request).map{
          case Right(result) => result.schemeContractedOutNumberDetails mustBe "S2123456B"
          case _ => fail("Expected Right for 200")
        }
      }
      "return Left for status 422" in new SUT {
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        val failuresJson = Json.obj("failures" -> Json.arr(Json.obj("reason" -> "No Match", "code" -> "63119")))
        val httpResponse = HttpResponse(422, failuresJson.toString())
        requestBuilderExecute(Future.successful(httpResponse))
        calculateOutcome("system", request).map{
          case Left(f) => f.failures.head.code mustBe 63119
          case _ => fail("Expected Left for 422")
        }
      }
      "throw UpstreamErrorResponse for 400/403/404" in new SUT{
        val request = HipCalculationRequest("S2123456B", "", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        val statuses = Seq(BAD_REQUEST, FORBIDDEN, NOT_FOUND)
        statuses.foreach { st =>
          val httpResponse = HttpResponse(st, Json.obj().toString())
          requestBuilderExecute(Future.successful(httpResponse))
          RecoverMethods.recoverToExceptionIf[UpstreamErrorResponse] {
            calculateOutcome("system", request)
          }.map { ex => ex.statusCode mustBe st }
        }
      }
      "fail the future if HTTP call fails" in new SUT{
        val request = HipCalculationRequest("S2123456B", "", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        requestBuilderExecute(Future.failed(new RuntimeException("Connection error")))
        RecoverMethods.recoverToExceptionIf[RuntimeException] {
          calculateOutcome("system", request)
        }.map {
          ex => ex.getMessage must include("Connection error")
        }
      }
      "throw BreakerException for error status code 500 (triggers circuit breaker)" in new SUT {
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        val httpResponse = HttpResponse(500, Json.obj().toString())
        requestBuilderExecute(Future.successful(httpResponse))
        RecoverMethods.recoverToExceptionIf[Exception] {
          calculateOutcome("system", request)
        }.map { exception =>
          exception.getClass.getSimpleName mustBe "BreakerException"
        }
      }
      "throw UpstreamErrorResponse when JSON validation fails for 200" in new SUT{
        val invalidJson = Json.obj("unexpectedField" -> "unexpectedValue")
        val httpResponse = HttpResponse(OK, invalidJson.toString())
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        requestBuilderExecute(Future.successful(httpResponse))
        RecoverMethods.recoverToExceptionIf[UpstreamErrorResponse] {
          calculateOutcome("system", request)
        }.map{ exception =>
          exception.statusCode mustBe 502
        }
      }

      "throw UpstreamErrorResponse when body is non-JSON for 200" in new SUT {
        val httpResponse = HttpResponse(OK, "not-json-body")
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        requestBuilderExecute(Future.successful(httpResponse))
        RecoverMethods.recoverToExceptionIf[UpstreamErrorResponse] {
          calculateOutcome("system", request)
        }.map { exception =>
          exception.statusCode mustBe 502
        }
      }

      "throw UpstreamErrorResponse when 422 JSON is invalid for HipCalculationFailuresResponse" in new SUT {
        val bad422 = Json.obj("bad" -> "shape")
        val httpResponse = HttpResponse(422, bad422.toString())
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        requestBuilderExecute(Future.successful(httpResponse))
        RecoverMethods.recoverToExceptionIf[UpstreamErrorResponse] {
          calculateOutcome("system", request)
        }.map { exception =>
          exception.statusCode mustBe 502
        }
      }

      "throw UpstreamErrorResponse when 422 body is non-JSON" in new SUT {
        val httpResponse = HttpResponse(422, "plain-text")
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        requestBuilderExecute(Future.successful(httpResponse))
        RecoverMethods.recoverToExceptionIf[UpstreamErrorResponse] {
          calculateOutcome("system", request)
        }.map { exception =>
          exception.statusCode mustBe 502
        }
      }

      "throw BreakerException for 429 Too Many Requests" in new SUT {
        val httpResponse = HttpResponse(429, Json.obj().toString())
        val request = HipCalculationRequest("", "S2123456B", "", "", Some(""),
          Some(EnumRevaluationRate.NONE), Some(EnumCalcRequestType.SPA), "", "", true, true)
        requestBuilderExecute(Future.successful(httpResponse))
        RecoverMethods.recoverToExceptionIf[Exception] {
          calculateOutcome("system", request)
        }.map { exception =>
          exception.getClass.getSimpleName mustBe "BreakerException"
        }
      }
    }
  }
}
