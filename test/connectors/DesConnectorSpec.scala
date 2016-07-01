/*
 * Copyright 2016 HM Revenue & Customs
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

import config.ApplicationConfig
import helpers.RandomNino
import metrics.Metrics
import models.ValidCalculationRequest
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, Matchers}
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play._
import play.api.libs.json.{JsValue, Json}
import play.api.test.Helpers._
import uk.gov.hmrc.circuitbreaker.UnhealthyServiceException
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.http._
import uk.gov.hmrc.play.http.logging.SessionId

import scala.concurrent.Future

class DesConnectorSpec extends PlaySpec with OneServerPerSuite with MockitoSugar with BeforeAndAfter {

  implicit val hc = HeaderCarrier()

  val mockHttp = mock[HttpGet]

  object TestDesConnector extends DesConnector {
    override val http: HttpGet = mockHttp
    override val metrics = mock[Metrics]
  }

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


  before {
    reset(mockHttp)
    reset(TestDesConnector.metrics)
  }

  "The Nps Connector" must {

    "calculate" must {

      "return a calculation request" in {

        implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))

        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))

        val result = TestDesConnector.calculate(ValidCalculationRequest("S1234567T", nino, "Bixby", "Bill", None, Some(0), None, Some(1), None, None))
        val calcResponse = await(result)

        calcResponse.npsLgmpcalc.length must be(1)
        verify(TestDesConnector.metrics).registerSuccessfulRequest
      }

      "return an error when 500 returned" in {

        implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))

        when(mockHttp.GET[HttpResponse](Matchers.any())(Matchers.any(), Matchers.any()))
          .thenReturn(Future.successful(HttpResponse(500,Some(calcResponseJson))))

        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, None, None, None, None, None))
        intercept[Upstream5xxResponse] {
                await(result)
                verify(TestDesConnector.metrics).registerFailedRequest
              }
      }

      "return an unhealthy service exception when 503 returned more than {numberOfCallsToTriggerStateChange} times" in {

        object Test503DesConnector extends DesConnector {
          override val http: HttpGet = mockHttp
          override val metrics = mock[Metrics]
        }

        implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))

        when(mockHttp.GET[HttpResponse](Matchers.any())(Matchers.any(), Matchers.any()))
          .thenReturn(Future.successful(HttpResponse(503,Some(calcResponseJson))))

        for(x <- 1 to ApplicationConfig.numberOfCallsToTriggerStateChange){
          response503(Test503DesConnector)
        }

        intercept[UnhealthyServiceException]{
          await(Test503DesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, None, None, None, None, None)))
          verify(Test503DesConnector.metrics).registerFailedRequest
          verify(Test503DesConnector.metrics) registerStatusCode "503"
        }


      }

      def response503(Test503DesConnector:DesConnector)  = {
        intercept[Upstream5xxResponse] {
          await(Test503DesConnector.calculate(ValidCalculationRequest("S1401234Q", RandomNino.generate, "Smith", "Bill", None, None, None, None, None, None)))
          Thread.sleep(10)
        }
      }

      "return a success when 422 returned" in {

        implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))

        when(mockHttp.GET[HttpResponse](Matchers.any())(Matchers.any(), Matchers.any()))
          .thenReturn(Future.successful(HttpResponse(422,Some(calcResponseJson))))

        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None,Some(0), None, None ,None, None))
        val calcResponse = await(result)

        calcResponse.rejection_reason must be(0)

        verify(TestDesConnector.metrics).registerSuccessfulRequest
        verify(TestDesConnector.metrics) registerStatusCode "422"
      }

      "generate a DES url" in {
        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, Some(0), None, None ,None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any())

        urlCaptor.getValue must endWith(s"/scon/S/1401234/Q/nino/$nino/surname/SMI/firstname/B/calculation/?request_earnings=1&calctype=0")
      }

      "generate correct url when no revaluation" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, Some(0), None, None ,None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any())

        urlCaptor.getValue must endWith("/?request_earnings=1&calctype=0")
      }

      "truncate surname to 3 chars if length greater than 3 chars" in {
        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Smith", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("/surname/SMI/")
      }

      "not truncate surname if length less than 3 chars" in {
        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "Fr", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("surname/FR/")
      }

      "remove any whitespace from names" in {
        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "LE BON", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("surname/LEB/")

      }

      "generate correct url when names contains special char" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino, "O'Smith", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("/surname/O%27S/")
      }

      "generate correct url when nino is not all uppercase" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("S1401234Q", nino.toLowerCase, "Smith", "Bill", None, Some(0), None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any())

        urlCaptor.getValue must include(nino)
      }

      "generate correct url when scon is not all uppercase" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("s1401234q", nino, "Smith", "Bill", None, None, None, None, None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("S/1401234/Q")
      }

      "generate correct url with revalrate" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("s1401234q", nino, "Smith", "Bill", None, Some(1), None, Some(1), None, None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("revalrate")
      }

      "generate correct url with contribution and earnings" in {

        val urlCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("s1401234q", nino, "Smith", "Bill", None, Some(1), None, Some(1), Some(1), None))

        verify(mockHttp).GET[HttpResponse](urlCaptor.capture())(Matchers.any(), Matchers.any())

        urlCaptor.getValue must include("request_earnings")
      }

      "catch calculate audit failure and continue" in {
        val mockAuditConnector = mock[AuditConnector]

        object TestNpsConnector extends DesConnector {

          override val http: HttpGet = mockHttp
          override val metrics = mock[Metrics]
        }

        when(mockAuditConnector.sendEvent(Matchers.any())(Matchers.any(), Matchers.any())).thenReturn(Future.failed(new Exception()))
        when(mockHttp.GET[HttpResponse](Matchers.any())
          (Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(200,Some(calcResponseJson))))
        val result = TestDesConnector.calculate(ValidCalculationRequest("s1401234q", nino, "Smith", "Bill", None, Some(1), None, Some(1), None, None))

      }
    }

  }

  private def successfulCalcHttpResponse(responseJson: Option[JsValue]): JsValue = {
    responseJson.get
  }

}