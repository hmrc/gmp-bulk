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

package actors

import java.util.concurrent.TimeUnit

import akka.actor._
import com.google.inject.Inject
import connectors.{DesConnector, DesGetHiddenRecordResponse}
import metrics.ApplicationMetrics
import models.{CalculationResponse, GmpBulkCalculationResponse, ProcessReadyCalculationRequest}
import play.api.http.Status
import play.api.Logger
import repositories.BulkCalculationMongoRepository
import uk.gov.hmrc.http.{HeaderCarrier, Upstream4xxResponse}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

trait CalculationRequestActorComponent {
  val desConnector: DesConnector
  val repository: BulkCalculationMongoRepository
  val metrics: ApplicationMetrics
}

class CalculationRequestActor extends Actor with ActorUtils {

  self: CalculationRequestActorComponent =>

  override def receive: Receive = {
    case request: ProcessReadyCalculationRequest => {

      val origSender = sender
      val startTime = System.currentTimeMillis()

      desConnector.getPersonDetails(request.validCalculationRequest.get.nino)(HeaderCarrier()) map {
        case DesGetHiddenRecordResponse =>

          repository.insertResponseByReference(request.bulkId, request.lineId,
            GmpBulkCalculationResponse(List(), 423, None, None, None, containsErrors = true)).map { result =>

            origSender ! result

          }

        case x => {

          val tryCallingDes = Try {
            desConnector.calculate(request.validCalculationRequest.get)
          }

          tryCallingDes match {
            case Success(successfulCall) => {
              successfulCall.map {
                case x: CalculationResponse => {
                  repository.insertResponseByReference(request.bulkId, request.lineId, GmpBulkCalculationResponse.createFromCalculationResponse(x)).map {

                    result => {
                      // $COVERAGE-OFF$
                      metrics.processRequest(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
                      Logger.debug(s"[CalculationRequestActor] InsertResponse : $result")
                      // $COVERAGE-ON$
                      origSender ! result
                    }
                  }
                }
              }.recover {

                case e: Upstream4xxResponse if e.reportAs == Status.BAD_REQUEST => {

                  // $COVERAGE-OFF$
                  Logger.error(s"[CalculationRequestActor] Inserting Failure response failed with error: $e")
                  // $COVERAGE-ON$

                  // Record the response as a failure, which will help out with cyclic processing of messages
                  repository.insertResponseByReference(request.bulkId, request.lineId,
                    GmpBulkCalculationResponse(List(), 400, None, None, None, containsErrors = true)).map { result =>

                    origSender ! result

                  }
                }

                case e =>
                  // $COVERAGE-OFF$
                  Logger.error(s"[CalculationRequestActor] Inserting Failure response failed with error :$e")
                  origSender ! akka.actor.Status.Failure(e)
                // $COVERAGE-ON$
              }
            }

            case Failure(f) => {
              // $COVERAGE-OFF$
              Logger.error(s"[CalculationRequestActor] Calling DES failed with error: ${ f.getMessage }")
              // $COVERAGE-ON$

              origSender ! akka.actor.Status.Failure(f)
            }

          }

        }
      } recover {
        case e =>
          // $COVERAGE-OFF$
          Logger.error(s"[CalculationRequestActor] Calling getPersonDetails failed with error: ${ e.getMessage }")
          // $COVERAGE-ON$
      }


    }

    case STOP => {
      // $COVERAGE-OFF$
      Logger.debug(s"[CalculationRequestActor] stop message")
      Logger.debug("sender: " + sender.getClass)
      // $COVERAGE-ON$
      sender ! STOP
    }


    case e => {
      // $COVERAGE-OFF$
      Logger.debug(s"[CalculationRequestActor] Invalid Message : { message : $e}")
      Logger.debug("sender: " + sender.getClass)
      // $COVERAGE-ON$
      sender ! akka.actor.Status.Failure(new RuntimeException(s"invalid message: $e"))
    }

  }
}


class DefaultCalculationRequestActor @Inject()(override val repository : BulkCalculationMongoRepository,
                                               override val desConnector : DesConnector,
                                               override val metrics : ApplicationMetrics
                                              ) extends CalculationRequestActor with CalculationRequestActorComponent