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

import config.WSHttp
import org.joda.time.LocalDate
import play.api.Logger
import play.api.libs.json.Json
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpPost}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class ReceivedUploadTemplate(email: String, uploadReference: String)
case class ProcessedUploadTemplate(email: String, uploadReference: String, uploadDate: LocalDate, userId: String )

case class SendTemplatedEmailRequest(to: List[String], templateId: String, parameters: Map[String, String])

object SendTemplatedEmailRequest {
  implicit val format = Json.format[SendTemplatedEmailRequest]
}

trait EmailConnector extends ServicesConfig{

  val http: HttpPost = WSHttp

  def sendReceivedTemplatedEmail(template: ReceivedUploadTemplate)(implicit hc: HeaderCarrier): Future[Boolean] = {

    val request = SendTemplatedEmailRequest(List(template.email), "gmp_bulk_upload_received", Map("fileUploadReference" -> template.uploadReference))

    sendEmail(request)

  }

  def sendProcessedTemplatedEmail(template: ProcessedUploadTemplate)(implicit hc: HeaderCarrier): Future[Boolean] = {

    val request = SendTemplatedEmailRequest(List(template.email), "gmp_bulk_upload_processed",
      Map("fileUploadReference" -> template.uploadReference, "uploadDate" -> template.uploadDate.toString("dd MMMM yyyy"), "userId" -> (("*" * 5) + template.userId.takeRight(3))))

    sendEmail(request)
  }

  private def sendEmail(request: SendTemplatedEmailRequest)(implicit hc: HeaderCarrier): Future[Boolean] = {

    val url = s"${baseUrl("email")}/send-templated-email"

    http.POST(url, request, Seq(("Content-Type", "application/json"))) map { response =>
      response.status match {
        case 202 => Logger.debug(s"[EmailConnector][sent] : ${response.body}"); true
        case _ => Logger.debug(s"[EmailConnector][not sent] : ${response.status}"); false
      }
    }
  }
}

object EmailConnector extends EmailConnector

