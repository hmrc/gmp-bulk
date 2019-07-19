package controllers.auth

import play.api.mvc.{Request, Result}

import scala.concurrent.Future

object FakeAuthAction extends AuthAction {

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {

    block(request)
  }
}
