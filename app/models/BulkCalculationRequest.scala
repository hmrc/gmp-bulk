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

package models

import org.bson.types.ObjectId

import java.time.{LocalDate, LocalDateTime}
import play.api.i18n.Messages
import play.api.libs.json.*
import uk.gov.hmrc.mongo.play.json.formats.{MongoFormats, MongoJavatimeFormats}

case class CalculationRequest(
  bulkId:                  Option[String],
  lineId:                  Int,
  validCalculationRequest: Option[ValidCalculationRequest],
  validationErrors:        Option[Map[String, String]],
  calculationResponse:     Option[GmpBulkCalculationResponse],
  rawCalculationRequest:   Option[JsObject] = None
) {

  def hasErrors: Boolean = ((calculationResponse.isDefined && calculationResponse.get.globalErrorCode > 0)
    || (calculationResponse.isDefined &&
      calculationResponse.get.calculationPeriods.foldLeft(0) {
        _ + _.errorCode
      } > 0)
    || validationErrors.isDefined)
}

object CalculationRequest {
  implicit val reads: Reads[CalculationRequest] = Reads { json =>
    for {
      bulkId              <- (json \ "bulkId").validateOpt[String]
      lineId              <- (json \ "lineId").validate[Int]
      validationErrors    <- (json \ "validationErrors").validateOpt[Map[String, String]]
      calculationResponse <- (json \ "calculationResponse").validateOpt[GmpBulkCalculationResponse]
      rawRequest          <- CalculationRequestReads.readRawCalculationRequest(json, validationErrors)
      validRequest        <- CalculationRequestReads.readValidCalculationRequest(json, validationErrors)
    } yield CalculationRequest(bulkId, lineId, validRequest, validationErrors, calculationResponse, rawRequest)
  }

  implicit val writes:  OWrites[CalculationRequest] = Json.writes[CalculationRequest]
  implicit val formats: OFormat[CalculationRequest] = OFormat(reads, writes)
}

case class BulkCalculationRequest(
  _id:                 Option[String],
  uploadReference:     String,
  email:               String,
  reference:           String,
  calculationRequests: List[CalculationRequest],
  userId:              String,
  timestamp:           LocalDateTime,
  complete:            Option[Boolean],
  total:               Option[Int],
  failed:              Option[Int]
)

object BulkCalculationRequest {
  implicit val timestampReads: Reads[LocalDateTime] =
    Reads[LocalDateTime](js => js.validate[String].map[LocalDateTime](dtString => LocalDateTime.parse(dtString)))

  // $COVERAGE-OFF$
  implicit val timestampWrites: Writes[LocalDateTime] = new Writes[LocalDateTime] {
    def writes(localDateTime: LocalDateTime): JsString = JsString(localDateTime.toString)
  }
  implicit val idFormat: Format[ObjectId]                = MongoFormats.objectIdFormat
  implicit val formats:  OFormat[BulkCalculationRequest] = Json.format[BulkCalculationRequest]
}

case class ProcessReadyCalculationRequest(
  bulkId:                  String,
  lineId:                  Int,
  validCalculationRequest: Option[ValidCalculationRequest],
  validationErrors:        Option[Map[String, String]],
  calculationResponse:     Option[GmpBulkCalculationResponse],
  isChild:                 Boolean = true,
  hasResponse:             Boolean = false,
  hasValidRequest:         Boolean = true,
  hasValidationErrors:     Boolean = false,
  rawCalculationRequest:   Option[JsObject] = None
) {

  def hasErrors = ((calculationResponse.isDefined && calculationResponse.get.globalErrorCode > 0)
    || (calculationResponse.isDefined &&
      calculationResponse.get.calculationPeriods.foldLeft(0) {
        _ + _.errorCode
      } > 0)
    || validationErrors.isDefined)

  def hasNPSErrors = calculationResponse.isDefined && (calculationResponse.get.globalErrorCode > 0 || calculationResponse.get.hasErrors)

  def getGlobalErrorMessageReason()(implicit messages: Messages): Option[String] =
    calculationResponse.isDefined match {
      case true if calculationResponse.get.globalErrorCode > 0 => Some(Messages(s"${calculationResponse.get.globalErrorCode}.reason"))
      case _                                                   => None
    }
  def isDualCalOne = calculationResponse.isDefined && validCalculationRequest.flatMap(_.dualCalc.map(_ == 1)).getOrElse(false)

  def isDualCalZero = calculationResponse.isDefined && validCalculationRequest.flatMap(_.dualCalc.map(_ == 0)).getOrElse(false)

  def getGlobalErrorMessageWhat()(implicit messages: Messages): Option[String] =
    calculationResponse.isDefined match {
      case true if calculationResponse.get.globalErrorCode > 0 => Some(Messages(s"${calculationResponse.get.globalErrorCode}.what"))
      case _                                                   => None
    }
}

object ProcessReadyCalculationRequest {
  // $COVERAGE-OFF$
  implicit val dateFormat: Format[LocalDate] = MongoJavatimeFormats.localDateFormat
  implicit val idFormat:   Format[ObjectId]  = MongoFormats.objectIdFormat

  implicit val reads: Reads[ProcessReadyCalculationRequest] = Reads { json =>
    for {
      bulkId              <- (json \ "bulkId").validate[String]
      lineId              <- (json \ "lineId").validate[Int]
      validationErrors    <- (json \ "validationErrors").validateOpt[Map[String, String]]
      calculationResponse <- (json \ "calculationResponse").validateOpt[GmpBulkCalculationResponse]
      isChild             <- (json \ "isChild").validateOpt[Boolean].map(_.getOrElse(true))
      hasResponse         <- (json \ "hasResponse").validateOpt[Boolean].map(_.getOrElse(false))
      hasValidRequest     <- (json \ "hasValidRequest").validateOpt[Boolean].map(_.getOrElse(true))
      hasValidationErrors <- (json \ "hasValidationErrors").validateOpt[Boolean].map(_.getOrElse(false))
      rawRequest          <- CalculationRequestReads.readRawCalculationRequest(json, validationErrors)
      validRequest        <- CalculationRequestReads.readValidCalculationRequest(json, validationErrors)
    } yield ProcessReadyCalculationRequest(
      bulkId,
      lineId,
      validRequest,
      validationErrors,
      calculationResponse,
      isChild,
      hasResponse,
      hasValidRequest,
      hasValidationErrors,
      rawRequest
    )
  }

  implicit val writes:  OWrites[ProcessReadyCalculationRequest] = Json.writes[ProcessReadyCalculationRequest]
  implicit val formats: OFormat[ProcessReadyCalculationRequest] = OFormat(reads, writes)
  // $COVERAGE-ON$
}

private object CalculationRequestReads {
  def readRawCalculationRequest(
    json:             JsValue,
    validationErrors: Option[Map[String, String]]
  ): JsResult[Option[JsObject]] =
    if validationErrors.exists(_.nonEmpty) then {
      (json \ "rawCalculationRequest").validateOpt[JsObject].flatMap {
        case existing @ Some(_) => JsSuccess(existing)
        case None               =>
          (json \ "validCalculationRequest") match {
            case JsDefined(JsNull) | _: JsUndefined => JsSuccess(None)
            case JsDefined(value: JsObject)         => JsSuccess(Some(value))
            case JsDefined(_)                       => JsSuccess(None)
          }
      }
    } else {
      JsSuccess(None)
    }

  def readValidCalculationRequest(
    json:             JsValue,
    validationErrors: Option[Map[String, String]]
  ): JsResult[Option[ValidCalculationRequest]] =
    (json \ "validCalculationRequest") match {
      case JsDefined(JsNull) | _: JsUndefined => JsSuccess(None)
      case JsDefined(value)                   =>
        value.validate[ValidCalculationRequest] match {
          case JsSuccess(validRequest, _) => JsSuccess(Some(validRequest))
          case _: JsError if validationErrors.exists(_.nonEmpty) =>
            JsSuccess(None)
          case errors: JsError => errors
        }
    }
}

case class ProcessedBulkCalculationRequest(
  _id:                 String,
  uploadReference:     String,
  email:               String,
  reference:           String,
  calculationRequests: List[ProcessReadyCalculationRequest],
  userId:              String,
  timestamp:           LocalDateTime,
  complete:            Boolean,
  total:               Int = 0,
  failed:              Int = 0,
  isParent:            Boolean = true
) {
  def failedRequestCount: Int =
    calculationRequests.count(x => x.validationErrors.isDefined || (x.calculationResponse.isDefined && x.calculationResponse.get.hasErrors))
}

object ProcessedBulkCalculationRequest {
  implicit val timestampReads: Reads[LocalDateTime] =
    Reads[LocalDateTime](js => js.validate[String].map[LocalDateTime](dtString => LocalDateTime.parse(dtString)))

  implicit val timestampWrites: Writes[LocalDateTime] = new Writes[LocalDateTime] {
    def writes(localDateTime: LocalDateTime) = JsString(localDateTime.toString)
  }

  implicit val idFormat: Format[ObjectId]                         = MongoFormats.objectIdFormat
  implicit val formats:  OFormat[ProcessedBulkCalculationRequest] = Json.format[ProcessedBulkCalculationRequest]
}
