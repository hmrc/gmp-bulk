package controllers.auth

import com.google.inject.{ImplementedBy, Inject}
import config.WSHttp
import play.api.Configuration
import play.api.mvc.{ActionBuilder, ActionFunction, Request, Result}
import uk.gov.hmrc.auth.core.{AuthorisedFunctions, ConfidenceLevel, NoActiveSession, PlayAuthConnector}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.HeaderCarrierConverter
import play.api.http.Status.UNAUTHORIZED
import play.api.mvc.Results._
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



class MicroserviceAuthConnector @Inject()(val http: WSHttp, configuration: Configuration) extends PlayAuthConnector {

  val host = configuration.getString("microservice.services.auth.host").get
  val port = configuration.getString("microservice.services.auth.port").get

  override val serviceUrl: String = s"http://$host:$port"

}
