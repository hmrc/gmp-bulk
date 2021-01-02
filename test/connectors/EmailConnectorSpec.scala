/*
 * Copyright 2021 HM Revenue & Customs
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

import org.joda.time.LocalDate
import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, _}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import org.scalatestplus.play.{PlaySpec}
import play.api.Environment
import uk.gov.hmrc.http.{HeaderCarrier, HttpPost, HttpResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.http.HttpClient

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class EmailConnectorSpec extends PlaySpec with GuiceOneAppPerSuite with MockitoSugar with MustMatchers with BeforeAndAfter {

  lazy val mockHttp = mock[HttpClient]
  val environment = app.injector.instanceOf[Environment]
  lazy val servicesConfig = app.injector.instanceOf[ServicesConfig]

  class TestEmailConnector extends EmailConnector(mockHttp, environment, app.configuration, servicesConfig)

  implicit lazy val hc = HeaderCarrier()

  before {
    reset(mockHttp)
  }

  "The email connector" must {

    "The send upload received templated email method" when {

      "must return a true result" in {
        val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")
        val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

        when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(HttpResponse(202)))
        val result = Await.result(new TestEmailConnector().sendReceivedTemplatedEmail(template), 5 seconds)
        result must be(true)
      }

      "must send the user's email address" in {
        val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")
        val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

        when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(HttpResponse(202)))
        val result = Await.result(new TestEmailConnector().sendReceivedTemplatedEmail(template), 5 seconds)
        result must be(true)
        requestCaptor.getValue.to must contain("joe@bloggs.com")
      }

      "must send the user's file upload reference" in {
        val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")
        val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

        when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(HttpResponse(202)))
        val result = Await.result(new TestEmailConnector().sendReceivedTemplatedEmail(template), 5 seconds)
        result must be(true)
        requestCaptor.getValue.parameters must contain("fileUploadReference" -> "upload-ref")
      }

      "must send the correct template id" in {
        val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")
        val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

        when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(HttpResponse(202)))
        val result = Await.result(new TestEmailConnector().sendReceivedTemplatedEmail(template), 5 seconds)
        result must be(true)
        requestCaptor.getValue.templateId must be("gmp_bulk_upload_received")
      }
    }

    "A failed send upload received templated email method" when {

        "must return a false result" in {
          val template = ReceivedUploadTemplate("joe@bloggs.com", "upload-ref")
          val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

          when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, any[SendTemplatedEmailRequest], any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
            .thenReturn(Future.successful(HttpResponse(400)))

          val result = Await.result(new TestEmailConnector().sendReceivedTemplatedEmail(template), 5 seconds)
          result must be(false)
        }

    }


    "The send upload processed templated email method" when {

      val date = LocalDate.now

      "must return a true result" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")
        val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

        when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(HttpResponse(202)))

        val result = Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)

        result must be(true)
      }

      "must send the user's email address" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")
        val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

        when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(HttpResponse(202)))

        val result = Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)

        requestCaptor.getValue.to must contain("joe@bloggs.com")
      }

      "must send the user's uppload reference" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")
        val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

        when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(HttpResponse(202)))

        val result = Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)

        requestCaptor.getValue.parameters must contain("fileUploadReference" -> "upload-ref")
      }

      "must send the user's upload date" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")
        val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

        when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(HttpResponse(202)))

        val result = Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)

        requestCaptor.getValue.parameters must contain("uploadDate" -> date.toString("dd MMMM yyyy"))
      }

      "must send the user's user id" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")
        val requestCaptor = ArgumentCaptor.forClass(classOf[SendTemplatedEmailRequest])

        when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, requestCaptor.capture(), any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(HttpResponse(202)))

        val result = Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)

        requestCaptor.getValue.parameters must contain("userId" -> "*****567")
      }
    }

    "A failed send upload processed templated email method" when {

      val date = LocalDate.now
      "must return a false result" in {
        val template = ProcessedUploadTemplate("joe@bloggs.com", "upload-ref", date ,"a1234567")

        when(mockHttp.POST[SendTemplatedEmailRequest, HttpResponse](anyString, any[SendTemplatedEmailRequest], any[Seq[(String, String)]])(any(), any(), any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(HttpResponse(400)))

        val result = Await.result(new TestEmailConnector().sendProcessedTemplatedEmail(template), 5 seconds)
        result must be(false)
      }
    }
  }
}
