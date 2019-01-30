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

package controllers

import connectors.{EmailConnector, ReceivedUploadTemplate}
import models._
import play.api.libs.json.Json
import play.api.mvc._
import repositories.BulkCalculationRepository
import uk.gov.hmrc.play.microservice.controller.BaseController

import scala.concurrent.ExecutionContext.Implicits.global

trait BulkController extends BaseController {

  val repository: BulkCalculationRepository
  val emailConnector: EmailConnector
  val csvGenerator: CsvGenerator = CsvGenerator

  def post(userId: String) = Action.async(parse.json(maxLength = 1024 * 10000)) {
    implicit request =>
      withJsonBody[BulkCalculationRequest] { bulkCalculationRequest =>
        repository.insertBulkDocument(bulkCalculationRequest).map {
          case true => {
            emailConnector.sendReceivedTemplatedEmail(ReceivedUploadTemplate(bulkCalculationRequest.email,bulkCalculationRequest.reference))
            Ok
          }
          case _ => Conflict //trying to insert a duplicate
        }
      }
  }

  def getPreviousRequests(userId: String) = Action.async {
    implicit request =>
      repository.findByUserId(userId).map {
        case Some(x) => {
          Ok(Json.toJson(x))
        }
      }
  }

  def getResultsSummary(userId: String, uploadReference: String) = Action.async {
    implicit request =>
      repository.findSummaryByReference(uploadReference).map {
        case Some(result) => userId match {
          case result.userId => Ok(Json.toJson(result))
          case _ => Forbidden
        }
        case _ => NotFound
      }

  }

  def getCalculationsAsCsv(userId: String, reference: String, csvFilter: CsvFilter) = Action.async {
    implicit request =>
      repository.findByReference(reference, csvFilter).map {
        case Some(result) => userId match {
          case result.userId => {
            val textToBeReturned: String = csvGenerator.generateCsv(result, Some(csvFilter))
            Ok(textToBeReturned).as("text/csv").withHeaders(("Content-Disposition", "attachment; filename=\"" + result.reference + "_" + csvFilter.getFileTypeName + ".csv\""))
          }
          case _ => Forbidden
        }
        case _ => NotFound
      }
  }

  def getContributionsAndEarningsAsCsv(userId: String, reference: String) = Action.async {
    implicit request =>
      repository.findByReference(reference).map {
        case Some(result) => userId match {
          case result.userId => {
            val textToBeReturned: String = csvGenerator.generateContributionsCsv(result)
            Ok(textToBeReturned).as("text/csv").withHeaders(("Content-Disposition", "attachment; filename=\"" + result.reference + "_contributions_and_earnings.csv\""))
          }
          case _ => Forbidden
        }
        case _ => NotFound
      }
  }

}

object BulkController extends BulkController {
  // $COVERAGE-OFF$
  override val repository = BulkCalculationRepository()
  override val emailConnector = EmailConnector
  // $COVERAGE-ON$
}
