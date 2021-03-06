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

package uk.gov.hmrc.mobilemessages.controllers.action

import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.api.controllers.{ErrorAcceptHeaderInvalid, ErrorUnauthorizedLowCL, HeaderValidator}
import uk.gov.hmrc.mobilemessages.connector.{AccountWithLowCL, AuthConnector, Authority, NinoNotFoundOnAccount}
import uk.gov.hmrc.mobilemessages.controllers.{ErrorUnauthorizedNoNino, ForbiddenAccess}
import uk.gov.hmrc.play.auth.microservice.connectors.ConfidenceLevel
import uk.gov.hmrc.play.http._
import uk.gov.hmrc.play.http.hooks.HttpHook

import scala.concurrent.Future


final case class AuthenticatedRequest[A](authority: Option[Authority], request: Request[A]) extends WrappedRequest(request)

trait AccountAccessControl extends ActionBuilder[AuthenticatedRequest] with Results {

  import scala.concurrent.ExecutionContext.Implicits.global

  val authConnector: AuthConnector

  def invokeBlock[A](request: Request[A], block: AuthenticatedRequest[A] => Future[Result]) = {
    implicit val hc = HeaderCarrier.fromHeadersAndSession(request.headers, None)

    authConnector.grantAccess().flatMap {
      authority => {
        block(AuthenticatedRequest(Some(authority),request))
      }
    }.recover {
      case ex:uk.gov.hmrc.play.http.Upstream4xxResponse => Unauthorized(Json.toJson(ErrorUnauthorizedNoNino))

      case ex:ForbiddenException =>
        Logger.info("Unauthorized! ForbiddenException caught and returning 403 status!")
        Forbidden(Json.toJson(ForbiddenAccess))

      case ex:NinoNotFoundOnAccount =>
        Logger.info("Unauthorized! NINO not found on account!")
        Unauthorized(Json.toJson(ErrorUnauthorizedNoNino))

      case ex:AccountWithLowCL =>
        Logger.info("Unauthorized! Account with low CL!")
        Unauthorized(Json.toJson(ErrorUnauthorizedLowCL))
    }
  }

}

trait AccountAccessControlWithHeaderCheck extends HeaderValidator {
  val checkAccess=true
  val accessControl:AccountAccessControl

  override def validateAccept(rules: Option[String] => Boolean) = new ActionBuilder[AuthenticatedRequest] {

    def invokeBlock[A](request: Request[A], block: AuthenticatedRequest[A] => Future[Result]) = {
      if (rules(request.headers.get("Accept"))) {
        if (checkAccess) accessControl.invokeBlock(request, block)
        else block(AuthenticatedRequest(None,request))
      }
      else Future.successful(Status(ErrorAcceptHeaderInvalid.httpStatusCode)(Json.toJson(ErrorAcceptHeaderInvalid)))
    }
  }
}

object Auth {
  val authConnector: AuthConnector = AuthConnector
}

object AccountAccessControl extends AccountAccessControl {
  val authConnector: AuthConnector = Auth.authConnector
}

object AccountAccessControlWithHeaderCheck extends AccountAccessControlWithHeaderCheck {
  val accessControl: AccountAccessControl = AccountAccessControl
}

object AccountAccessControlSandbox extends AccountAccessControl {
    val authConnector: AuthConnector = new AuthConnector {
      override val serviceUrl: String = "NO SERVICE"

      override def serviceConfidenceLevel: ConfidenceLevel = ConfidenceLevel.L0

      override def http: HttpGet = new HttpGet {
        override protected def doGet(url: String)(implicit hc: HeaderCarrier): Future[HttpResponse] = Future.failed(new IllegalArgumentException("Sandbox mode!"))
        override val hooks: Seq[HttpHook] = NoneRequired
      }
    }
}

object AccountAccessControlCheckAccessOff extends AccountAccessControlWithHeaderCheck {
  override val checkAccess=false

  val accessControl: AccountAccessControl = AccountAccessControlSandbox
}

