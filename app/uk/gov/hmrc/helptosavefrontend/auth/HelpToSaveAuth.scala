/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.helptosavefrontend.auth

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import cats.syntax.apply._
import cats.syntax.option._
import org.joda.time.LocalDate
import play.api.mvc.{Action, AnyContent, Request, Result}
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.authorise.Predicate
import uk.gov.hmrc.auth.core.retrieve._
import uk.gov.hmrc.helptosavefrontend.config.FrontendAppConfig
import uk.gov.hmrc.helptosavefrontend.controllers.BaseController
import uk.gov.hmrc.helptosavefrontend.metrics.Metrics
import uk.gov.hmrc.helptosavefrontend.metrics.Metrics.nanosToPrettyString
import uk.gov.hmrc.helptosavefrontend.models.HtsAuth.{AuthProvider, AuthWithCL200, UserInfoRetrievals}
import uk.gov.hmrc.helptosavefrontend.models.userinfo.{Address, MissingUserInfo, MissingUserInfos, UserInfo}
import uk.gov.hmrc.helptosavefrontend.models.{HtsContext, HtsContextWithNINO, HtsContextWithNINOAndFirstName, HtsContextWithNINOAndUserDetails}
import uk.gov.hmrc.helptosavefrontend.util.Logging.LoggerOps
import uk.gov.hmrc.helptosavefrontend.util.{NINO, NINOLogMessageTransformer, toFuture, toJavaDate}
import uk.gov.hmrc.play.bootstrap.config.AuthRedirects
import uk.gov.hmrc.play.bootstrap.controller.ActionWithMdc

import scala.concurrent.Future

trait HelpToSaveAuth extends AuthorisedFunctions with AuthRedirects {
  this: BaseController ⇒

  val metrics: Metrics
  val appConfig: FrontendAppConfig
  implicit val transformer: NINOLogMessageTransformer

  private type HtsAction[A <: HtsContext] = Request[AnyContent] ⇒ A ⇒ Future[Result]

  def authorisedForHtsWithNINO(action: HtsAction[HtsContextWithNINO])(redirectOnLoginURL: String): Action[AnyContent] =
    authorised(Retrievals.nino) {
      case (mayBeNino, request, time) ⇒
        withNINO(mayBeNino, time) { nino ⇒
          action(request)(HtsContextWithNINO(authorised = true, nino))
        }(request)
    }(redirectOnLoginURL)

  def authorisedForHtsWithNINOAndName(action: HtsAction[HtsContextWithNINOAndFirstName])(redirectOnLoginURL: String): Action[AnyContent] =
    authorised(Retrievals.name and Retrievals.itmpName and Retrievals.nino) {
      case (maybeName ~ maybeItmpName ~ mayBeNino, request, time) ⇒
        withNINO(mayBeNino, time) { nino ⇒
          action(request)(HtsContextWithNINOAndFirstName(authorised = true, nino, maybeItmpName.givenName.orElse(maybeName.name)))
        }(request)
    }(redirectOnLoginURL)

  def authorisedForHtsWithInfo(action: HtsAction[HtsContextWithNINOAndUserDetails])(redirectOnLoginURL: String): Action[AnyContent] =
    authorised(UserInfoRetrievals and Retrievals.nino) {
      case (name ~ email ~ dateOfBirth ~ itmpName ~ itmpDateOfBirth ~ itmpAddress ~ mayBeNino, request, time) ⇒
        withNINO(mayBeNino, time) { nino ⇒
          val userDetails = getUserInfo(nino, name, email, dateOfBirth, itmpName, itmpDateOfBirth, itmpAddress)

          userDetails.fold(
            m ⇒ logger.warn(s"User details retrieval failed, missing details [${m.missingInfo.mkString(", ")}] ${timeString(time)}", nino),
            _ ⇒ logger.debug(s"Successfully retrieved NINO and user details ${timeString(time)}", nino)
          )

          action(request)(HtsContextWithNINOAndUserDetails(authorised = true, nino, userDetails))
        }(request)
    }(redirectOnLoginURL)

  def authorisedForHts(action: HtsAction[HtsContext])(redirectOnLoginURL: String): Action[AnyContent] =
    authorised(EmptyRetrieval, AuthProvider) {
      case (_, request, _) ⇒
        action(request)(HtsContext(authorised = true))
    }(redirectOnLoginURL)

  def authorisedForHtsWithNINOAndNoCL(action: HtsAction[HtsContextWithNINO])(redirectOnLoginURL: String): Action[AnyContent] =
    authorised(Retrievals.nino, AuthProvider) {
      case (mayBeNino, request, time) ⇒
        withNINO(mayBeNino, time) { nino ⇒
          action(request)(HtsContextWithNINO(authorised = true, nino))
        }(request)
    }(redirectOnLoginURL)

  def unprotected(action: HtsAction[HtsContext]): Action[AnyContent] =
    Action.async { implicit request ⇒
      authorised() {
        action(request)(HtsContext(authorised = true))
      }.recoverWith {
        case _ ⇒
          action(request)(HtsContext(authorised = false))
      }
    }

  private def authorised[A](retrieval: Retrieval[A],
                            predicate: Predicate    = AuthWithCL200
  )(toResult: (A, Request[AnyContent], Long) ⇒ Future[Result])(redirectOnLoginURL: ⇒ String): Action[AnyContent] =
    ActionWithMdc.async { implicit request ⇒
      val timer = metrics.authTimer.time()

      authorised(predicate).retrieve(retrieval) { a ⇒
        val time = timer.stop()
        toResult(a, request, time)
      }.recover {
        val time = timer.stop()
        handleFailure(redirectOnLoginURL, time)
      }
    }

  private def withNINO[A](mayBeNino: Option[String], nanos: Long)(action: NINO ⇒ Future[Result])(implicit request: Request[_]): Future[Result] =
    mayBeNino.fold {
      logger.warn(s"NINO retrieval failed ${timeString(nanos)}")
      toFuture(internalServerError())
    }(action)

  // need this type to be able to use the apply syntax on ValidatedNel and mapN
  private type ValidOrMissingUserInfo[A] = ValidatedNel[MissingUserInfo, A]

  private def getUserInfo(nino:        String,
                          name:        Name,
                          email:       Option[String],
                          dob:         Option[LocalDate],
                          itmpName:    ItmpName,
                          itmpDob:     Option[LocalDate],
                          itmpAddress: ItmpAddress): Either[MissingUserInfos, UserInfo] = {

    val givenNameValidation: ValidOrMissingUserInfo[String] =
      itmpName.givenName.orElse(name.name).filter(_.nonEmpty)
        .toValidNel(MissingUserInfo.GivenName)

    val surnameValidation: ValidOrMissingUserInfo[String] =
      itmpName.familyName.orElse(name.lastName).filter(_.nonEmpty)
        .toValidNel(MissingUserInfo.Surname)

    val dateOfBirthValidation: ValidOrMissingUserInfo[LocalDate] =
      itmpDob.orElse(dob)
        .toValidNel(MissingUserInfo.DateOfBirth)

    val addressValidation: ValidOrMissingUserInfo[ItmpAddress] = {
      val lineCount = List(itmpAddress.line1, itmpAddress.line2, itmpAddress.line3, itmpAddress.line4, itmpAddress.line5)
        .map(_.map(_.trim).filter(_.nonEmpty)).collect { case Some(_) ⇒ () }.length
      if (lineCount < 2 || !itmpAddress.postCode.exists(_.trim.nonEmpty)) {
        Invalid(NonEmptyList.of(MissingUserInfo.Contact))
      } else {
        Valid(itmpAddress)
      }
    }

    val validation: ValidOrMissingUserInfo[UserInfo] =
      (givenNameValidation, surnameValidation, dateOfBirthValidation, addressValidation).mapN {
        case (givenName, surname, jodaDob, address) ⇒
          UserInfo(givenName, surname, nino, toJavaDate(jodaDob), email.filter(_.nonEmpty), Address(address))
      }

    validation
      .leftMap(m ⇒ MissingUserInfos(m.toList.toSet, nino))
      .toEither
  }

  def handleFailure(redirectOnLoginURL: String, time: Long)(implicit request: Request[_]): PartialFunction[Throwable, Result] = {
    case _: NoActiveSession ⇒
      toGGLogin(redirectOnLoginURL)

    case _: InsufficientConfidenceLevel | _: InsufficientEnrolments ⇒
      SeeOther(appConfig.ivUrl(redirectOnLoginURL))

    case ex: AuthorisationException ⇒
      logger.warn(s"could not authenticate user due to: $ex ${timeString(time)}")
      internalServerError()
  }

  override def toGGLogin(redirectOnLoginURL: String): Result =
    Redirect(ggLoginUrl, Map(
      "continue" -> Seq(redirectOnLoginURL),
      "accountType" -> Seq("individual"),
      "origin" -> Seq(appConfig.appName)
    ))

  private def timeString(nanos: Long): String = s"(round-trip time: ${nanosToPrettyString(nanos)})"

  def internalServerError()(implicit request: Request[_]): Result
}
