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

package models

import org.joda.time.LocalDateTime
import play.api.i18n.Messages
import play.api.libs.json._
import reactivemongo.bson.{BSONDateTime, BSONDocument, BSONDocumentReader, BSONDocumentWriter}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

case class ValidCalculationRequest(scon: String,
                                   nino: String,
                                   surname: String,
                                   firstForename: String,
                                   memberReference: Option[String],
                                   calctype: Option[Int],
                                   revaluationDate: Option[String] = None,
                                   revaluationRate: Option[Int] = None,
                                   dualCalc: Option[Int] = None,
                                   terminationDate: Option[String] = None,
                                   memberIsInScheme: Option[Boolean] = None
                                  )

object ValidCalculationRequest {
  implicit val formats = Json.format[ValidCalculationRequest]

}


case class CalculationRequest(bulkId: Option[String],
                              lineId: Int,
                              validCalculationRequest: Option[ValidCalculationRequest],
                              validationErrors: Option[Map[String, String]],
                              calculationResponse: Option[GmpBulkCalculationResponse],
                              var isChild: Option[Boolean],
                              var hasResponse: Option[Boolean],
                              var hasValidationErrors: Option[Boolean],
                              var hasValidRequest: Option[Boolean]) {

  isChild = Some(true)
  hasResponse = Some(false)
  hasValidationErrors = Some(validationErrors.isDefined)
  hasValidRequest = Some(validCalculationRequest.isDefined)

  def hasErrors = ((calculationResponse.isDefined && calculationResponse.get.globalErrorCode > 0)
    || (calculationResponse.isDefined &&
    calculationResponse.get.calculationPeriods.foldLeft(0) {
      _ + _.errorCode
    } > 0)
    || validationErrors.isDefined)

  def hasNPSErrors = calculationResponse.isDefined && (calculationResponse.get.globalErrorCode > 0 || calculationResponse.get.hasErrors)

  def getGlobalErrorMessageReason: Option[String] = {
    calculationResponse.isDefined match {
      case true if calculationResponse.get.globalErrorCode > 0 => Some(Messages(s"${calculationResponse.get.globalErrorCode}.reason"))
      case _ => None
    }
  }

  def getGlobalErrorMessageWhat: Option[String] = {
    calculationResponse.isDefined match {
      case true if calculationResponse.get.globalErrorCode > 0 => Some(Messages(s"${calculationResponse.get.globalErrorCode}.what"))
      case _ => None
    }
  }
}

object CalculationRequest {
  implicit val formats = Json.format[CalculationRequest]
}

case class BulkCalculationRequest(_id: Option[String],
                                  uploadReference: String,
                                  email: String,
                                  reference: String,
                                  calculationRequests: List[CalculationRequest],
                                  userId: String,
                                  timestamp: LocalDateTime,
                                  complete: Option[Boolean],
                                  total: Option[Int],
                                  failed: Option[Int])

object BulkCalculationRequest {
  implicit val timestampReads = Reads[LocalDateTime](js =>
    js.validate[String].map[LocalDateTime](dtString =>
      LocalDateTime.parse(dtString)
    )
  )

  implicit val timestampWrites = new Writes[LocalDateTime] {
    def writes(localDateTime: LocalDateTime) = JsString(localDateTime.toString)
  }

  implicit val formats = Json.format[BulkCalculationRequest]
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val idFormat = ReactiveMongoFormats.objectIdFormats

  implicit object BulkRequestWriter extends BSONDocumentWriter[BulkCalculationRequest] {
    override def write(t: BulkCalculationRequest): BSONDocument = BSONDocument(
      "_id" -> t._id,
      "uploadReference" -> t.uploadReference
    )
  }

  implicit object BulkRequestReader extends BSONDocumentReader[BulkCalculationRequest] {
    override def read(bson: BSONDocument): BulkCalculationRequest = {
      BulkCalculationRequest(
        bson.getAs[String]("_id"),
        bson.getAs[String]("uploadReference").get,
        bson.getAs[String]("email").get,
        bson.getAs[String]("reference").get,
        List(),
        bson.getAs[String]("userId").get,
        bson.getAs[BSONDateTime]("timestamp").map(dt => new LocalDateTime(dt.value)).get,
        bson.getAs[Boolean]("complete"),
        bson.getAs[Int]("total"),
        bson.getAs[Int]("failed")
      )
    }
  }

}

case class ProcessedBulkCalculationRequest(_id: String,
                                           uploadReference: String,
                                           email: String,
                                           reference: String,
                                           calculationRequests: List[CalculationRequest],
                                           userId: String,
                                           timestamp: LocalDateTime,
                                           complete: Boolean,
                                           total: Int = 0,
                                           failed: Int = 0,
                                           isParent: Boolean = true) {
  def failedRequestCount: Int = {
    calculationRequests.count(x => x.validationErrors.isDefined || (x.calculationResponse.isDefined && x.calculationResponse.get.hasErrors))
  }
}

object ProcessedBulkCalculationRequest {
  implicit val timestampReads = Reads[LocalDateTime](js =>
    js.validate[String].map[LocalDateTime](dtString =>
      LocalDateTime.parse(dtString)
    )
  )

  implicit val timestampWrites = new Writes[LocalDateTime] {
    def writes(localDateTime: LocalDateTime) = JsString(localDateTime.toString)
  }

  implicit val formats = Json.format[ProcessedBulkCalculationRequest]
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val idFormat = ReactiveMongoFormats.objectIdFormats
}

case class ProcessReadyCalculationRequest(bulkId: String,
                                          lineId: Int,
                                          validCalculationRequest: ValidCalculationRequest,
                                          isChild: Boolean = true,
                                          hasResponse: Boolean = false,
                                          hasErrors: Boolean = false)

object ProcessReadyCalculationRequest {
  // $COVERAGE-OFF$
  implicit val formats = Json.format[ProcessReadyCalculationRequest]
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val idFormat = ReactiveMongoFormats.objectIdFormats
  // $COVERAGE-ON$
}