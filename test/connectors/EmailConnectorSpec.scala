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

import java.time.LocalDate
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfter, _}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Environment
import play.api.libs.json. Json
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URL
import java.time.format.DateTimeFormatter
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.{Await, Future}

class EmailConnectorSpec extends HttpClientV2Helper with GuiceOneAppPerSuite with BeforeAndAfter {

  val environment = app.injector.instanceOf[Environment]
  lazy val servicesConfig = app.injector.instanceOf[ServicesConfig]

  class TestEmailConnector extends EmailConnector(mockHttp, app.configuration, servicesConfig)

  implicit lazy val hc: HeaderCarrier = HeaderCarrier()

  before {
    reset(mockHttp)
    when(mockHttp.post(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
  }

  "The email connector" must {

    "The send upload received templated email method" when {

      "must return a true result" in {
        val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")
        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(202, "")))
        val result = Await.result(new TestEmailConnector().sendReceivedTemplatedEmail(template), 5 seconds)
        result must be(true)
      }

      "must send the user's email address" in {
        val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")
        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(202, "")))
        val result = Await.result(new TestEmailConnector().sendReceivedTemplatedEmail(template), 5 seconds)
        result must be(true)
        val capturedRequest: SendTemplatedEmailRequest = Json.parse(jsonCaptor.getValue.toString).as[SendTemplatedEmailRequest]
        capturedRequest.to must contain("joe@bloggs.com")
      }

      "must send the user's file upload reference" in {
        val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")
        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(202, "")))
        val result = Await.result(new TestEmailConnector().sendReceivedTemplatedEmail(template), 5 seconds)
        result must be(true)
        val capturedRequest: SendTemplatedEmailRequest = Json.parse(jsonCaptor.getValue.toString).as[SendTemplatedEmailRequest]
        capturedRequest.parameters must contain("fileUploadReference" -> "upload-ref")
      }

      "must send the correct template id" in {
        val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")
        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(202, "")))
        val result = Await.result(new TestEmailConnector().sendReceivedTemplatedEmail(template), 5 seconds)
        result must be(true)
        val capturedRequest: SendTemplatedEmailRequest = Json.parse(jsonCaptor.getValue.toString).as[SendTemplatedEmailRequest]
        capturedRequest.templateId must be("gmp_bulk_upload_received")
      }
    }

    "A failed send upload received templated email method" when {

        "must return a false result" in {
          val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")

          requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(400, "")))

          val result = Await.result(new TestEmailConnector().sendReceivedTemplatedEmail(template), 5 seconds)
          result must be(false)
        }

    }


    "The send upload processed templated email method" when {

      val date = LocalDate.now

      "must return a true result" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")

        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(202, "")))

        val result = Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)

        result must be(true)
      }

      "must send the user's email address" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")

        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(202, "")))

        Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)

        val capturedRequest: SendTemplatedEmailRequest = Json.parse(jsonCaptor.getValue.toString).as[SendTemplatedEmailRequest]
        capturedRequest.to must contain("joe@bloggs.com")
      }

      "must send the user's uppload reference" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")

        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(202, "")))

        Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)

        val capturedRequest: SendTemplatedEmailRequest = Json.parse(jsonCaptor.getValue.toString).as[SendTemplatedEmailRequest]
        capturedRequest.parameters must contain("fileUploadReference" -> "upload-ref")
      }

      "must send the user's upload date" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")

        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(202, "")))

        Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)

        val capturedRequest: SendTemplatedEmailRequest = Json.parse(jsonCaptor.getValue.toString).as[SendTemplatedEmailRequest]
        capturedRequest.parameters must contain("uploadDate" -> date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy")))
      }

      "must send the user's user id" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")

        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(202, "")))

        Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)

        val capturedRequest: SendTemplatedEmailRequest = Json.parse(jsonCaptor.getValue.toString).as[SendTemplatedEmailRequest]
        capturedRequest.parameters must contain("userId" -> "*****567")
      }
    }

    "A failed send upload processed templated email method" when {

      val date = LocalDate.now
      "must return a false result" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")

        requestBuilderExecute[HttpResponse](Future.successful(HttpResponse(400, "")))

        val result = Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)
        result must be(false)
      }
    }
  }
}
