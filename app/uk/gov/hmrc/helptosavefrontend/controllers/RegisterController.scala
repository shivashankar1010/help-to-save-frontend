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

package uk.gov.hmrc.helptosavefrontend.controllers

import javax.inject.Singleton

import cats.data.EitherT
import cats.instances.future._
import com.google.inject.Inject
import play.api.{Application, Configuration}
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.mvc.{Action, AnyContent, Request, Result}
import uk.gov.hmrc.helptosavefrontend.config.{FrontendAppConfig, FrontendAuthConnector}
import uk.gov.hmrc.helptosavefrontend.connectors.NSIConnector.SubmissionFailure
import uk.gov.hmrc.helptosavefrontend.connectors._
import uk.gov.hmrc.helptosavefrontend.controllers.RegisterController.EligibleInfo.{EligibleWithEmail, EligibleWithNoEmail}
import uk.gov.hmrc.helptosavefrontend.forms.{EmailValidation, GiveEmailForm, SelectEmailForm}
import uk.gov.hmrc.helptosavefrontend.metrics.Metrics
import uk.gov.hmrc.helptosavefrontend.models.HTSSession.EligibleWithUserInfo
import uk.gov.hmrc.helptosavefrontend.models._
import uk.gov.hmrc.helptosavefrontend.models.eligibility.{EligibilityCheckResponse, EligibilityReason}
import uk.gov.hmrc.helptosavefrontend.models.eligibility.EligibilityCheckResult.Eligible
import uk.gov.hmrc.helptosavefrontend.models.userinfo.{NSIUserInfo, UserInfo}
import uk.gov.hmrc.helptosavefrontend.services.HelpToSaveService
import uk.gov.hmrc.helptosavefrontend.util.Logging._
import uk.gov.hmrc.helptosavefrontend.util.{Crypto, Email, Logging, NINOLogMessageTransformer, toFuture}
import uk.gov.hmrc.helptosavefrontend.views
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future
import scala.util.{Failure, Success}

@Singleton
class RegisterController @Inject() (val messagesApi:           MessagesApi,
                                    val helpToSaveService:     HelpToSaveService,
                                    val sessionCacheConnector: SessionCacheConnector,
                                    frontendAuthConnector:     FrontendAuthConnector,
                                    metrics:                   Metrics,
                                    app:                       Application,
                                    configuration:             Configuration
)(implicit crypto: Crypto, emailValidation: EmailValidation, transformer: NINOLogMessageTransformer)
  extends HelpToSaveAuth(frontendAuthConnector, metrics)
  with EnrolmentCheckBehaviour with SessionBehaviour with CapCheckBehaviour with I18nSupport with Logging {

  val earlyCapCheckOn: Boolean = configuration.underlying.getBoolean("enable-early-cap-check")

  def getGiveEmailPage: Action[AnyContent] =
    authorisedForHtsWithNINO { implicit request ⇒ implicit htsContext ⇒
      checkIfAlreadyEnrolled { () ⇒
        checkIfDoneEligibilityChecks{ _ ⇒
          SeeOther(routes.RegisterController.getSelectEmailPage().url)
        }{ _ ⇒
          if (earlyCapCheckOn) {
            Ok(views.html.register.give_email(GiveEmailForm.giveEmailForm))
          } else {
            checkIfAccountCreateAllowed(
              Ok(views.html.register.give_email(GiveEmailForm.giveEmailForm)))
          }
        }
      }
    }(redirectOnLoginURL = routes.RegisterController.getGiveEmailPage().url)

  def giveEmailSubmit(): Action[AnyContent] =
    authorisedForHtsWithNINO { implicit request ⇒ implicit htsContext ⇒
      checkIfAlreadyEnrolled { () ⇒
        checkIfDoneEligibilityChecks { _ ⇒
          SeeOther(routes.RegisterController.getSelectEmailPage().url)
        }{ eligible ⇒
          GiveEmailForm.giveEmailForm.bindFromRequest().fold[Future[Result]](
            withErrors ⇒ Ok(views.html.register.give_email(withErrors)),
            form ⇒
              sessionCacheConnector.put(HTSSession(Some(Right(EligibleWithUserInfo(eligible.eligible,
                                                                                   eligible.userInfo))), None, Some(form.email), None, None))
                .value.flatMap(
                  _.fold(
                    { e ⇒
                      logger.warn(s"Could not update session cache: $e", eligible.userInfo.nino)
                      internalServerError()
                    }, _ ⇒ SeeOther(routes.NewApplicantUpdateEmailAddressController.verifyEmail.url)
                  )
                ))
        }
      }
    }(redirectOnLoginURL = routes.RegisterController.giveEmailSubmit().url)

  def getSelectEmailPage: Action[AnyContent] =
    authorisedForHtsWithNINO { implicit request ⇒ implicit htsContext ⇒
      checkIfAlreadyEnrolled { () ⇒
        checkIfDoneEligibilityChecks { eligibleWithEmail ⇒
          if (earlyCapCheckOn) {
            Ok(views.html.register.select_email(eligibleWithEmail.email, SelectEmailForm.selectEmailForm))
          } else {
            checkIfAccountCreateAllowed(
              Ok(views.html.register.select_email(eligibleWithEmail.email, SelectEmailForm.selectEmailForm)))
          }
        } { _ ⇒
          SeeOther(routes.RegisterController.getGiveEmailPage().url)
        }
      }
    }(redirectOnLoginURL = routes.RegisterController.getSelectEmailPage().url)

  def selectEmailSubmit(): Action[AnyContent] =
    authorisedForHtsWithNINO { implicit request ⇒ implicit htsContext ⇒
      checkIfAlreadyEnrolled { () ⇒
        checkIfDoneEligibilityChecks{ eligibleWithEmail ⇒
          SelectEmailForm.selectEmailForm.bindFromRequest().fold(
            withErrors ⇒ Ok(views.html.register.select_email(eligibleWithEmail.email, withErrors)),
            _.newEmail.fold[Future[Result]](
              SeeOther(routes.RegisterController.confirmEmail(crypto.encrypt(eligibleWithEmail.email)).url))(
                newEmail ⇒ {
                  val session = new HTSSession(Some(Right(EligibleWithUserInfo(eligibleWithEmail.eligible, eligibleWithEmail.userInfo))), None, Some(newEmail))
                  sessionCacheConnector.put(session).fold(
                    e ⇒ internalServerError(),
                    _ ⇒ SeeOther(routes.NewApplicantUpdateEmailAddressController.verifyEmail.url)
                  )
                }
              )
          )
        } { _ ⇒
          SeeOther(routes.RegisterController.getGiveEmailPage().url)
        }
      }
    }(redirectOnLoginURL = routes.RegisterController.selectEmailSubmit().url)

  def confirmEmail(email: String): Action[AnyContent] = authorisedForHtsWithNINO { implicit request ⇒ implicit htsContext ⇒
    val nino = htsContext.nino
    checkIfAlreadyEnrolled { () ⇒
      checkIfDoneEligibilityChecks { eligibleWithEmail ⇒
        val result = for {
          e ← EitherT.fromEither[Future](decryptEmail(email))
          _ ← sessionCacheConnector.put(HTSSession(Some(Right(EligibleWithUserInfo(eligibleWithEmail.eligible, eligibleWithEmail.userInfo))), Some(e), None))
          _ ← helpToSaveService.storeConfirmedEmail(e)
        } yield ()

        result.fold[Result](
          { e ⇒
            logger.warn(s"Could not write confirmed email: $e", nino)
            internalServerError()
          }, { _ ⇒
            SeeOther(routes.RegisterController.getCreateAccountHelpToSavePage().url)
          }
        )
      }{ _ ⇒
        SeeOther(routes.RegisterController.getGiveEmailPage().url)
      }
    }
  }(redirectOnLoginURL = FrontendAppConfig.checkEligibilityUrl)

  def getCreateAccountHelpToSavePage: Action[AnyContent] = authorisedForHtsWithNINO { implicit request ⇒ implicit htsContext ⇒
    checkIfAlreadyEnrolled { () ⇒
      checkIfDoneEligibilityChecks { eligibleWithEmail ⇒
        eligibleWithEmail.confirmedEmail.fold[Future[Result]](
          SeeOther(routes.RegisterController.getSelectEmailPage().url))(
            _ ⇒
              EligibilityReason.fromEligible(eligibleWithEmail.eligible).fold{
                logger.warn(s"Could not parse eligiblity reason: ${eligibleWithEmail.eligible}", eligibleWithEmail.userInfo.nino)
                internalServerError()
              }{ reason ⇒
                Ok(views.html.register.create_account_help_to_save(reason))
              })
      }{ _ ⇒
        SeeOther(routes.RegisterController.getGiveEmailPage().url)
      }
    }
  }(redirectOnLoginURL = routes.RegisterController.getCreateAccountHelpToSavePage().url)

  def getDailyCapReachedPage: Action[AnyContent] = authorisedForHts { implicit request ⇒ implicit htsContext ⇒
    Ok(views.html.register.daily_cap_reached())
  }(redirectOnLoginURL = routes.RegisterController.getDailyCapReachedPage().url)

  def getTotalCapReachedPage: Action[AnyContent] = authorisedForHts { implicit request ⇒ implicit htsContext ⇒
    Ok(views.html.register.total_cap_reached())
  }(redirectOnLoginURL = routes.RegisterController.getTotalCapReachedPage().url)

  def getServiceUnavailablePage: Action[AnyContent] = authorisedForHts { implicit request ⇒ implicit htsContext ⇒
    Ok(views.html.register.service_unavailable())
  }(redirectOnLoginURL = routes.RegisterController.getServiceUnavailablePage().url)

  def getDetailsAreIncorrect: Action[AnyContent] = authorisedForHts { implicit request ⇒ implicit htsContext ⇒
    Ok(views.html.register.details_are_incorrect())
  }(redirectOnLoginURL = FrontendAppConfig.checkEligibilityUrl)

  def createAccountHelpToSave: Action[AnyContent] = authorisedForHtsWithNINO { implicit request ⇒ implicit htsContext ⇒
    val nino = htsContext.nino
    checkIfAlreadyEnrolled { () ⇒
      checkIfDoneEligibilityChecks { eligibleWithEmail ⇒
        eligibleWithEmail.confirmedEmail.fold[Future[Result]](
          toFuture(SeeOther(routes.RegisterController.getSelectEmailPage().url))
        ) { confirmedEmail ⇒
            val userInfo = NSIUserInfo(eligibleWithEmail.userInfo, confirmedEmail)

            helpToSaveService.createAccount(userInfo).fold[Result]({ e ⇒
              logger.warn(s"Error while trying to create account: ${submissionFailureToString(e)}", nino)
              SeeOther(routes.RegisterController.getCreateAccountErrorPage().url)
            }, { _ ⇒
              logger.info("Successfully created account", nino)

              helpToSaveService.updateUserCount().value.onFailure {
                case e ⇒ logger.warn(s"Could not update the user count, future failed: $e", nino)
              }

              helpToSaveService.enrolUser().value.onComplete {
                case Failure(e)        ⇒ logger.warn(s"Could not start process to enrol user, future failed: $e", nino)
                case Success(Left(e))  ⇒ logger.warn(s"Could not start process to enrol user: $e", nino)
                case Success(Right(_)) ⇒ logger.debug(s"Process started to enrol user", nino)
              }

              SeeOther(FrontendAppConfig.nsiManageAccountUrl)
            })
          }
      } { _ ⇒
        SeeOther(routes.RegisterController.getGiveEmailPage().url)
      }
    }
  }(redirectOnLoginURL = routes.RegisterController.createAccountHelpToSave().url)

  def getCreateAccountErrorPage: Action[AnyContent] = authorisedForHtsWithNINO { implicit request ⇒ implicit htsContext ⇒
    checkIfAlreadyEnrolled { () ⇒
      checkIfDoneEligibilityChecks { eligibleWithEmail ⇒
        eligibleWithEmail.confirmedEmail.fold[Future[Result]](
          SeeOther(routes.RegisterController.getSelectEmailPage().url))(
            _ ⇒ Ok(views.html.register.create_account_error()))
      }{ _ ⇒
        SeeOther(routes.RegisterController.getGiveEmailPage().url)
      }
    }
  }(redirectOnLoginURL = routes.RegisterController.getCreateAccountHelpToSavePage().url)

  /**
   * Checks the HTSSession data from keystore - if the is no session the user has not done the eligibility
   * checks yet this session and they are redirected to the 'apply now' page. If the session data indicates
   * that they are not eligible show the user the 'you are not eligible page'. Otherwise, perform the
   * given action if the the session data indicates that they are eligible
   */
  private def checkIfDoneEligibilityChecks(
      ifEligibleWithEmail: EligibleWithEmail ⇒ Future[Result])(
      ifEligibleWithoutEmail: EligibleWithNoEmail ⇒ Future[Result])(
      implicit
      htsContext: HtsContextWithNINO, hc: HeaderCarrier, request: Request[_]): Future[Result] =
    checkSession {
      // no session data => user has not gone through the journey this session => take them to eligibility checks
      SeeOther(routes.EligibilityCheckController.getCheckEligibility().url)
    } {
      session ⇒
        session.eligibilityCheckResult.fold[Future[Result]](
          SeeOther(routes.EligibilityCheckController.getCheckEligibility().url)
        )(_.fold(
            // user has gone through journey already this sessions and were found to be ineligible
            _ ⇒ SeeOther(routes.EligibilityCheckController.getIsNotEligible().url),
            userInfo ⇒
              // user has gone through journey already this sessions and were found to be eligible
              userInfo.userInfo.email.fold(ifEligibleWithoutEmail(EligibleWithNoEmail(userInfo.userInfo, userInfo.eligible)))(email ⇒
                ifEligibleWithEmail(EligibleWithEmail(userInfo.userInfo, email, session.confirmedEmail, userInfo.eligible))
              )
          ))
    }

  private def submissionFailureToString(failure: SubmissionFailure): String =
    s"Account creation failed. ErrorId: ${failure.errorMessageId.getOrElse("-")}, errorMessage: ${failure.errorMessage}, errorDetails: ${failure.errorDetail}"

  private def decryptEmail(encryptedEmail: String): Either[String, String] =
    crypto.decrypt(encryptedEmail) match {
      case Success(value) ⇒ Right(value)
      case Failure(e)     ⇒ Left(s"Could not decode email: ${e.getMessage}")
    }

}

object RegisterController {

  sealed trait EligibleInfo

  object EligibleInfo {

    case class EligibleWithEmail(userInfo: UserInfo, email: Email, confirmedEmail: Option[Email], eligible: Eligible) extends EligibleInfo

    case class EligibleWithNoEmail(userInfo: UserInfo, eligible: Eligible) extends EligibleInfo
  }

}
