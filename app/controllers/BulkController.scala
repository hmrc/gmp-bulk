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

package controllers

import com.google.inject.Inject
import connectors.{EmailConnector, ReceivedUploadTemplate}
import controllers.auth.AuthAction

import javax.inject.Singleton
import models._
import play.api.i18n.{Messages, MessagesImpl}
import play.api.libs.json.Json
import play.api.mvc.MessagesControllerComponents
import repositories.{BulkCalculationMongoRepository, BulkCalculationRepository}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.ExecutionContext

@Singleton
class BulkController @Inject()(authAction: AuthAction,
                               emailConnector: EmailConnector,
                               csvGenerator: CsvGenerator,
                               cc: MessagesControllerComponents,
                               bulkCalculationMongoRepository: BulkCalculationMongoRepository,
                               implicit val ec: ExecutionContext) extends BackendController(cc) {

  implicit lazy val messages: Messages = MessagesImpl(cc.langs.availables.head, messagesApi)

  lazy val repository: BulkCalculationRepository = bulkCalculationMongoRepository

  def post(userId: String) = authAction.async(parse.json(maxLength = 1024 * 10000)) {
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

  def getPreviousRequests(userId: String) = authAction.async {
    _ =>  {
      repository.findByUserId(userId).map {
        case Some(x) => {
          Ok(Json.toJson(x))
        }
        case _ => NotFound
      }
    }
  }

  def getResultsSummary(userId: String, uploadReference: String) = authAction.async {
    _ => {
      repository.findSummaryByReference(uploadReference).map {
        case Some(result) => userId match {
          case result.userId => Ok(Json.toJson(result))
          case _ => Forbidden
        }
        case _ => NotFound
      }
    }

  }

  def getCalculationsAsCsv(userId: String, reference: String, csvFilter: CsvFilter) = authAction.async {
    _ => {
      repository.findByReference(reference, csvFilter).map {
        case Some(result) => userId match {
          case result.userId => {
            val textToBeReturned: String = csvGenerator.generateCsv(result, Some(csvFilter))
            Ok(textToBeReturned)
              .as("text/csv")
              .withHeaders(("Content-Disposition", "attachment; filename=\"" + result.reference + "_" + csvFilter.getFileTypeName + ".csv\""))
          }
          case _ => Forbidden
        }
        case _ => NotFound
      }
    }
  }

  def getContributionsAndEarningsAsCsv(userId: String, reference: String) = authAction.async {
    _ => {
      repository.findByReference(reference).map {
        case Some(result) => userId match {
          case result.userId => {
            val textToBeReturned: String = csvGenerator.generateContributionsCsv(result)
            Ok(textToBeReturned)
              .as("text/csv")
              .withHeaders(("Content-Disposition", "attachment; filename=\"" + result.reference + "_contributions_and_earnings.csv\""))
          }
          case _ => Forbidden
        }
        case _ => NotFound
      }
    }
  }
}
