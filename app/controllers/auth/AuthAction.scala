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

package controllers.auth

import com.google.inject.{ImplementedBy, Inject}
import play.api.Mode.Mode
import play.api.http.Status.UNAUTHORIZED
import play.api.mvc.Results._
import play.api.mvc.{ActionBuilder, ActionFunction, Request, Result}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.auth.core.{AuthorisedFunctions, ConfidenceLevel, NoActiveSession, PlayAuthConnector}
import uk.gov.hmrc.http.{HeaderCarrier, HttpPost}
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.play.config.ServicesConfig

import scala.concurrent.{ExecutionContext, Future}

class AuthActionImpl @Inject()(override val authConnector: MicroserviceAuthConnector)
                              (implicit ec: ExecutionContext) extends AuthAction with AuthorisedFunctions {

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)

    authorised(ConfidenceLevel.L50) {
      block(request)
    }recover {
      case ex: NoActiveSession =>
        Status(UNAUTHORIZED)
    }
  }
}

@ImplementedBy(classOf[AuthActionImpl])
trait AuthAction extends ActionBuilder[Request] with ActionFunction[Request, Request]


class MicroserviceAuthConnector @Inject()( val http: HttpPost,
                                           val runModeConfiguration: Configuration,
                                           environment: Environment
                                         ) extends PlayAuthConnector with ServicesConfig {
  override val serviceUrl: String = baseUrl("auth")
  override protected def mode: Mode = environment.mode
}