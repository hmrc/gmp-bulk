/*
 * Copyright 2019 HM Revenue & Customs
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

import java.util.UUID

import com.kenshoo.play.metrics.Metrics
import config.ApplicationConfig
import helpers.RandomNino
import metrics.ApplicationMetrics
import models.ValidCalculationRequest
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, Matchers}
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play._
import play.api.Environment
import play.api.libs.json.Json
import play.api.test.Helpers._
import uk.gov.hmrc.circuitbreaker.{CircuitBreakerConfig, UnhealthyServiceException}
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.logging.SessionId
import uk.gov.hmrc.play.audit.http.connector.AuditConnector

import scala.concurrent.Future

class DesConnectorSpec extends PlaySpec with OneServerPerSuite with MockitoSugar with BeforeAndAfter {

  implicit val hc = HeaderCarrier()

  val mockHttp = mock[HttpGet]
  val metrics = mock[ApplicationMetrics]
  val environment = app.injector.instanceOf[Environment]

  object TestDesConnector extends DesConnector(environment, app.configuration, mockHttp, metrics)

  val nino = RandomNino.generate

  val calcResponseJson = Json.parse(
    s"""{
           "nino":"$nino",
           "rejection_reason":0,
           "npsScon":{
              "contracted_out_prefix":"S",
              "ascn_scon":1301234,
              "modulus_19_suffix":"T"
           },
           "npsLgmpcalc":[
              {
                 "scheme_mem_start_date":"1978-04-06",
                 "scheme_end_date":"200-04-05",
                 "revaluation_rate":1,
                 "gmp_cod_post_eightyeight_tot":1.23,
                 "gmp_cod_allrate_tot":7.88,
                 "gmp_error_code":0,
                "reval_calc_switch_ind": 0
              }
           ]
        }""")

  val citizenDetailsJson = Json.parse(
            """{
                  "etag" : "115"
                }
            """.stripMargin)

  before {
    reset(mockHttp)
    reset(TestDesConnector.metrics)
  }

  "The Nps Connector" must {

    "calculate" must {

      "return a calculation request" in {

        implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))

        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))

        val result = TestDesConnector.calculate(ValidCalculationRequest("S1234567T", nino, "Bixby", "Bill", None, Some(0), None, Some(1), None, None))
        val calcResponse = await(result)

        calcResponse.npsLgmpcalc.length must be(1)
        verify(TestDesConnector.metrics).registerSuccessfulRequest
      }

      "return an error when 500 returned" in {

        implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))

        when(mockHttp.GET[HttpResponse](Matchers.any())(Matchers.any(), Matchers.any(), Matchers.any()))
          .thenReturn(Future.successful(HttpResponse(500, Some(calcResponseJson))))

        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, None, None, None, None, None))
        intercept[TestDesConnector.BreakerException] {
          await(result)
          verify(TestDesConnector.metrics).registerFailedRequest
        }
      }

      "return an error when 400 returned" in {

        implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))

        when(mockHttp.GET[HttpResponse](Matchers.any())(Matchers.any(), Matchers.any(), Matchers.any()))
          .thenReturn(Future.successful(HttpResponse(400, None)))

        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, None, None, None, None, None))

        intercept[Upstream4xxResponse] {
          await(result)
          verify(TestDesConnector.metrics).registerFailedRequest
        }
      }

      val errorCodes = List(TOO_MANY_REQUESTS, 499, BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT, INTERNAL_SERVER_ERROR)
      for (errorCode <- errorCodes) {
        s"return an unhealthy service exception when $errorCode returned more than {numberOfCallsToTriggerStateChange} times" in {

          object TestErrorDesConnector extends DesConnector(environment, app.configuration, mockHttp, metrics) {
            override def circuitBreakerConfig = {
              CircuitBreakerConfig("DesConnector", 5, 300, 300)
            }
          }

          implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))
          val request = ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)

          when(mockHttp.GET[HttpResponse](Matchers.anyString)(Matchers.any(), Matchers.any(), Matchers.any()))
            .thenReturn(Future.successful(HttpResponse(errorCode, Some(calcResponseJson))))

          for (x <- 1 to ApplicationConfig.numberOfCallsToTriggerStateChange) {
            intercept[TestErrorDesConnector.BreakerException] {
              await(TestErrorDesConnector.calculate(request))
              Thread.sleep(2)
            }
          }

          intercept[UnhealthyServiceException] {
            await(TestErrorDesConnector.calculate(request))
            verify(TestErrorDesConnector.metrics).registerFailedRequest
            verify(TestErrorDesConnector.metrics) registerStatusCode errorCode.toString
          }
        }
      }

      "return a success when 422 returned" in {

        implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))

        when(mockHttp.GET[HttpResponse](Matchers.any())(Matchers.any(), Matchers.any(), Matchers.any()))
          .thenReturn(Future.successful(HttpResponse(422, Some(calcResponseJson))))

        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, Some(0), None, None, None, None))
        val calcResponse = await(result)

        calcResponse.rejection_reason must be(0)

        verify(TestDesConnector.metrics).registerSuccessfulRequest
        verify(TestDesConnector.metrics) registerStatusCode "422"
      }

      "generate a DES url" in {
        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any(), Matchers.any())

        urlCaptor.getValue must endWith(s"/scon/S/1401234/Q/nino/$nino/surname/SMI/firstname/B/calculation/?request_earnings=1&calctype=0")
      }

      "generate correct url when no revaluation" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any(), Matchers.any())

        urlCaptor.getValue must endWith("/?request_earnings=1&calctype=0")
      }

      "truncate surname to 3 chars if length greater than 3 chars" in {
        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("/surname/SMI/")
      }

      "not truncate surname if length less than 3 chars" in {
        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Fr", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("surname/FR/")
      }

      "remove any whitespace from names" in {
        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "LE BON", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("surname/LEB/")

      }

      "generate correct url when names contains special char" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "O'Smith", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("/surname/O%27S/")
      }

      "generate correct url when nino is not all uppercase" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino.toLowerCase, "Smith", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any(), Matchers.any())

        urlCaptor.getValue must include(nino)
      }

      "generate correct url when scon is not all uppercase" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("s1401234q", nino, "Smith", "Bill", None, None, None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("S/1401234/Q")
      }

      "generate correct url with revalrate" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("s1401234q", nino, "Smith", "Bill", None, Some(1), None, Some(1), None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("revalrate")
      }

      "generate correct url with contribution and earnings" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("s1401234q", nino, "Smith", "Bill", None, Some(1), None, Some(1), Some(1), None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("request_earnings")
      }

      "catch calculate audit failure and continue" in {
        val mockAuditConnector = mock[AuditConnector]

        object TestNpsConnector extends DesConnector(environment, app.configuration, mockHttp, metrics)

        when(mockAuditConnector.sendEvent(Matchers.any())(Matchers.any(), Matchers.any())).thenReturn(Future.failed(new Exception()))
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("s1401234q", nino, "Smith", "Bill", None, Some(1), None, Some(1), None, None))

      }
    }

    "Calling getPersonDetails" should {

      val nino = "AB123456C"

      "return a DesHiddenRecordResponse when manualCorrespondenceInd=true" in {

        val response = HttpResponse(423, Some(citizenDetailsJson))

        when(mockHttp.GET[HttpResponse](Matchers.any())(any(), any(), any())) thenReturn {
          Future.successful(response)
        }

        val pd = TestDesConnector.getPersonDetails(nino)
        await(pd) must be(DesGetHiddenRecordResponse)
        verify(TestDesConnector.metrics, times(1)).mciLockResult()
      }

      "return DesGetSuccessResponse when manualCorrespondenceInd=false" in {

        val response = HttpResponse(200, Some(citizenDetailsJson))
        when(mockHttp.GET[HttpResponse](Matchers.any())(any(), any(), any())) thenReturn {
          Future.successful(response)
        }

        val pd = TestDesConnector.getPersonDetails(nino)
        await(pd) must be(DesGetSuccessResponse)
      }

      "return a DesNotFoundResponse when HOD returns 404" in {

        when(mockHttp.GET[HttpResponse](Matchers.any())(any(), any(), any())) thenReturn {
          Future.failed(new NotFoundException("Not found"))
        }

        val pd = TestDesConnector.getPersonDetails(nino)
        await(pd) must be(DesGetNotFoundResponse)
      }

      "return a DesErrorResponse if any other issues" in {
        val ex = new Exception("Exception")
        val r = HttpResponse(200,  Some(citizenDetailsJson))
        when(mockHttp.GET[HttpResponse](Matchers.any())(any(), any(), any())) thenReturn {
          Future.failed(ex)
        }

        val pd = TestDesConnector.getPersonDetails(nino)
        await(pd) must be(DesGetErrorResponse(ex))

      }

      "return a success response if the MCI flag does not appear in the response" in {
        val json = Json.parse("{}")
        val response = HttpResponse(200, Some(json))

        when(mockHttp.GET[HttpResponse](anyString)(any(), any(), any())) thenReturn Future.successful(response)

        val result = TestDesConnector.getPersonDetails(nino)
        await(result) must be(DesGetSuccessResponse)
      }

      "return an Unexpected Response with Internal Server response or DES is down" in {
        val response = HttpResponse(500, Some(citizenDetailsJson))

        when(mockHttp.GET[HttpResponse](anyString)(any(), any(), any())) thenReturn Future.successful(response)

        val result = TestDesConnector.getPersonDetails(nino)
        await(result) must be(DesGetUnexpectedResponse)
      }

    }
  }

}
