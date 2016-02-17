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

package uk.gov.hmrc.nisp.auth

import play.api.Logger
import play.api.mvc.Results.Redirect
import play.api.mvc._
import uk.gov.hmrc.nisp.controllers.routes
import uk.gov.hmrc.play.frontend.auth.{Verify, AuthContext, AuthenticationProvider, UserCredentials}
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.play.http.{HeaderCarrier, SessionKeys}

import scala.concurrent.Future

object VerifyProvider extends Verify {

  private def redirectAfterLoginUrl(implicit request: Request[_]) =
    AuthUrlConfig.postSignInRedirectUrl.getOrElse(routes.AccountController.show().url)

  override def redirectToLogin(implicit request: Request[_]): Future[FailureResult] = {
    Future.successful(Redirect(login).withSession(sessionWithRedirect(request.session, Some(redirectAfterLoginUrl))))
  }

  private def sessionWithRedirect(session: Session, redirectTo: Option[String]) =
    Session(session.data ++ redirectTo.map(SessionKeys.redirect -> _).toMap ++ Map(SessionKeys.loginOrigin -> "YSP"))

  override def handleSessionTimeout(implicit request: Request[_]): Future[FailureResult] = {
    Future.successful(Redirect(routes.AccountController.timeout().url))
  }

  override def login: String = AuthUrlConfig.idaSignIn
}
