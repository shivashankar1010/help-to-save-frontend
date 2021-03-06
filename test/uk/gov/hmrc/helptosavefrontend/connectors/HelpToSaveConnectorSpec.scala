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

package uk.gov.hmrc.helptosavefrontend.connectors

import java.time.LocalDate
import java.util.{Base64, UUID}

import cats.data.EitherT
import cats.instances.int._
import cats.syntax.eq._
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import play.api.libs.json._
import uk.gov.hmrc.helptosavefrontend.TestSupport
import uk.gov.hmrc.helptosavefrontend.config.WSHttp
import uk.gov.hmrc.helptosavefrontend.connectors.HelpToSaveConnectorImpl.GetEmailResponse
import uk.gov.hmrc.helptosavefrontend.models._
import uk.gov.hmrc.helptosavefrontend.models.account.{Account, Blocking}
import uk.gov.hmrc.helptosavefrontend.models.eligibility.EligibilityCheckResponse
import uk.gov.hmrc.helptosavefrontend.models.eligibility.EligibilityCheckResult.{AlreadyHasAccount, Eligible, Ineligible}
import uk.gov.hmrc.helptosavefrontend.util.Email
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.helptosavefrontend.models.TestData.UserData.validNSIUserInfo
import uk.gov.hmrc.helptosavefrontend.models.register.CreateAccountRequest
import uk.gov.hmrc.helptosavefrontend.services.HelpToSaveServiceImpl.{SubmissionSuccess, submissionFailureFormat}

// scalastyle:off magic.number
class HelpToSaveConnectorSpec extends TestSupport with GeneratorDrivenPropertyChecks {

  val mockHttp: WSHttp = mock[WSHttp]

  lazy val connector: HelpToSaveConnector = new HelpToSaveConnectorImpl(mockHttp)

  val helpToSaveUrl = "http://localhost:7001"

  val eligibilityURL =
    s"$helpToSaveUrl/help-to-save/eligibility-check"

  val enrolmentStatusURL =
    s"$helpToSaveUrl/help-to-save/enrolment-status"

  val enrolUserURL =
    s"$helpToSaveUrl/help-to-save/enrol-user"

  val setITMPFlagURL =
    s"$helpToSaveUrl/help-to-save/set-itmp-flag"

  def storeEmailURL(email: Email) =
    s"$helpToSaveUrl/help-to-save/store-email?email=$email"

  val getEmailURL =
    s"$helpToSaveUrl/help-to-save/get-email"

  val accountCreateAllowedURL =
    s"$helpToSaveUrl/help-to-save/account-create-allowed"

  val updateUserCountURL =
    s"$helpToSaveUrl/help-to-save/update-user-count"

  private val createAccountURL =
    s"$helpToSaveUrl/help-to-save/create-account"

  private val updateEmailURL =
    s"$helpToSaveUrl/help-to-save/update-email"

  def mockHttpGet[I](url: String)(result: Option[HttpResponse]): Unit =
    (mockHttp.get(_: String)(_: HeaderCarrier, _: ExecutionContext))
      .expects(url, *, *)
      .returning(result.fold(
        Future.failed[HttpResponse](new Exception("")))(Future.successful))

  def mockHttpPost[A](url: String, body: A)(result: Option[HttpResponse]): Unit =
    (mockHttp.post(_: String, _: A, _: Seq[(String, String)])(_: Writes[A], _: HeaderCarrier, _: ExecutionContext))
      .expects(url, body, Seq.empty[(String, String)], *, *, *)
      .returning(result.fold(
        Future.failed[HttpResponse](new Exception("")))(Future.successful))

  def mockHttpPut[A](url: String, body: A)(result: Option[HttpResponse]): Unit =
    (mockHttp.put(_: String, _: A, _: Seq[(String, String)])(_: Writes[A], _: HeaderCarrier, _: ExecutionContext))
      .expects(url, body, Seq.empty[(String, String)], *, *, *)
      .returning(result.fold(
        Future.failed[HttpResponse](new Exception("")))(Future.successful))

  implicit val unitFormat: Format[Unit] = new Format[Unit] {
    override def writes(o: Unit) = JsNull

    override def reads(json: JsValue) = json match {
      case JsNull ⇒ JsSuccess(())
      case _      ⇒ JsError("JSON was not null")
    }
  }

  val eligibleString = "Eligible to HtS Account"

  val ineligibleString = "Ineligible to HtS Account"

  val eligibleResponseGen: Gen[EligibilityCheckResponse] =
    for {
      result ← Gen.alphaStr
      reasonCode ← Gen.choose(6, 8)
      reason ← Gen.alphaStr
    } yield EligibilityCheckResponse(result, 1, reason, reasonCode)

  val ineligibleResponseGen: Gen[EligibilityCheckResponse] =
    for {
      result ← Gen.alphaStr
      reasonCode ← Gen.choose(2, 5)
      reason ← Gen.alphaStr
    } yield EligibilityCheckResponse(result, 2, reason, reasonCode)

  "The HelpToSaveConnectorImpl" when {

    val nino = "nino"

    "getting eligibility status" should {

      behave like testCommon(
        mockHttpGet(eligibilityURL),
        () ⇒ connector.getEligibility(),
        EligibilityCheckResponse("eligible!", 1, "???", 6)
      )

      "return an EligibilityResult if the call comes back with a 200 status with a positive result " +
        "and a valid reason" in {
          forAll(eligibleResponseGen) { response ⇒
            val reason = Eligible(response)

            mockHttpGet(eligibilityURL)(Some(HttpResponse(200, responseJson = Some(Json.toJson(response)))))

            val result = connector.getEligibility()
            await(result.value) shouldBe Right(reason)
          }
        }

      "return an EligibilityResult if the call comes back with a 200 status with a negative result " +
        "and a valid reason" in {
          forAll(ineligibleResponseGen) { response ⇒
            val reason = Ineligible(response)

            mockHttpGet(eligibilityURL)(Some(HttpResponse(200, responseJson = Some(Json.toJson(response)))))

            val result = connector.getEligibility()
            await(result.value) shouldBe Right(reason)
          }
        }

      "return an EligibilityResult if the call comes back with a 200 status with a result " +
        "indicating an account has already been opened" in {
          val reasonString = "already has account"

          val response = EligibilityCheckResponse("HtS account already exists", 3, reasonString, 1)

          mockHttpGet(eligibilityURL)(Some(HttpResponse(200, responseJson = Some(Json.toJson(response)))))

          val result = connector.getEligibility()
          await(result.value) shouldBe Right(AlreadyHasAccount(response))
        }

      "return an error" when {

          def testError(resultCode: Int): Unit = {
            mockHttpGet(eligibilityURL)(
              Some(HttpResponse(200, responseJson = Some(Json.toJson(EligibilityCheckResponse("", resultCode, "", 1))))))

            val result = connector.getEligibility()
            await(result.value).isLeft shouldBe true
          }

        "the call comes back with a 200 and result code 4" in {
          testError(4)
        }

        "the call comes back with a 200 and an unknown result code" in {
          forAll { (resultCode: Int) ⇒
            whenever(!(1 to 3).contains(resultCode)) {
              testError(resultCode)
            }
          }
        }

      }
    }

    "getting enrolment status" must {

      implicit val enrolmentStatusWrites: Writes[EnrolmentStatus] = new Writes[EnrolmentStatus] {

        case class EnrolledJSON(enrolled: Boolean = true, itmpHtSFlag: Boolean)

        case class NotEnrolledJSON(enrolled: Boolean = false)

        implicit val enrolledWrites: Writes[EnrolledJSON] = Json.writes[EnrolledJSON]
        implicit val notEnrolledFormat: Writes[NotEnrolledJSON] = Json.writes[NotEnrolledJSON]

        override def writes(o: EnrolmentStatus) = o match {
          case EnrolmentStatus.Enrolled(itmpHtSFlag) ⇒ Json.toJson(EnrolledJSON(itmpHtSFlag = itmpHtSFlag))
          case EnrolmentStatus.NotEnrolled           ⇒ Json.toJson(NotEnrolledJSON())
        }

      }

      behave like testCommon(
        mockHttpGet(enrolmentStatusURL),
        () ⇒ connector.getUserEnrolmentStatus(),
        EnrolmentStatus.NotEnrolled
      )

      "return a Right if the call comes back with HTTP status 200 with " +
        "valid JSON in the body" in {
          mockHttpGet(enrolmentStatusURL)(Some(HttpResponse(
            200,
            Some(Json.parse(
              """
              |{
              |  "enrolled"    : true,
              |  "itmpHtSFlag" : true
              |}
            """.stripMargin))
          )))

          await(connector.getUserEnrolmentStatus().value) shouldBe Right(
            EnrolmentStatus.Enrolled(itmpHtSFlag = true))

          mockHttpGet(enrolmentStatusURL)(Some(HttpResponse(
            200,
            Some(Json.parse(
              """
              |{
              |  "enrolled"    : true,
              |  "itmpHtSFlag" : false
              |}
            """.stripMargin))
          )))

          await(connector.getUserEnrolmentStatus().value) shouldBe Right(
            EnrolmentStatus.Enrolled(itmpHtSFlag = false))

          mockHttpGet(enrolmentStatusURL)(Some(HttpResponse(
            200,
            Some(Json.parse(
              """
              |{
              |  "enrolled" : false,
              |  "itmpHtSFlag" : false
              |}
            """.stripMargin))
          )))

          await(connector.getUserEnrolmentStatus().value) shouldBe Right(EnrolmentStatus.NotEnrolled)
        }

    }

    "setting the ITMP flag" must {

      behave like testCommon(
        mockHttpGet(setITMPFlagURL),
        () ⇒ connector.setITMPFlagAndUpdateMongo(),
        (),
        testInvalidJSON = false
      )

      "return a Right if the call comes back with HTTP status 200 with " +
        "valid JSON in the body" in {
          mockHttpGet(setITMPFlagURL)(Some(HttpResponse(200)))

          await(connector.setITMPFlagAndUpdateMongo().value) shouldBe Right(())
        }
    }

    "storing emails" must {

      val email = "email"

      val encodedEmail = new String(Base64.getEncoder.encode(email.getBytes()))
      behave like testCommon(
        mockHttpGet(storeEmailURL(encodedEmail)),
        () ⇒ connector.storeEmail(email),
        (),
        testInvalidJSON = false
      )

      "return a Right if the call comes back with HTTP status 200 with " +
        "valid JSON in the body" in {
          mockHttpGet(storeEmailURL(encodedEmail))(Some(HttpResponse(200)))

          await(connector.storeEmail(email).value) shouldBe Right(())
        }
    }

    "getting emails" must {

      val nino = "nino"

      val email = "email"

      behave like testCommon(
        mockHttpGet(getEmailURL),
        () ⇒ connector.getEmail(),
        GetEmailResponse(None),
        false
      )

      "return a Right if the call comes back with HTTP status 200 and " +
        "valid JSON in the body" in {
          mockHttpGet(getEmailURL)(Some(HttpResponse(200, Some(Json.toJson(GetEmailResponse(Some(email)))))))
          await(connector.getEmail().value) shouldBe Right(Some(email))

          mockHttpGet(getEmailURL)(Some(HttpResponse(200, Some(Json.toJson(GetEmailResponse(None))))))
          await(connector.getEmail().value) shouldBe Right(None)
        }
    }

    "getting account-creation-allowed response" must {
      behave like testCommon(
        mockHttpGet(accountCreateAllowedURL),
        () ⇒ connector.isAccountCreationAllowed(),
        Json.toJson(true),
        true
      )

      "return a Right if the call comes with HTTP 200 and valid response in the body" in {
        mockHttpGet(accountCreateAllowedURL)(Some(HttpResponse(200, Some(Json.toJson(UserCapResponse())))))
        await(connector.isAccountCreationAllowed().value) shouldBe Right(UserCapResponse())
      }
    }

    "getting Account" must {

      val correlationId = UUID.randomUUID()
      val url = s"$helpToSaveUrl/help-to-save/$nino/account?correlationId=$correlationId&systemId=help-to-save-frontend"
      val account = Account(false, Blocking(false), 123.45, 0, 0, 0, LocalDate.parse("1900-01-01"), List(), None, None)

      behave like testCommon(
        mockHttpGet(url),
        () ⇒ connector.getAccount(nino, correlationId),
        account,
        false
      )

      "be able to handle 200 responses with valid Account json" in {
        mockHttpGet(url)(Some(HttpResponse(200, Some(Json.toJson(account)))))
        await(connector.getAccount(nino, correlationId).value) shouldBe Right(account)
      }

    }

    "creating account" must {

      "return http response as it is to the caller" in {
        val request = CreateAccountRequest(validNSIUserInfo, 7)
        val response = HttpResponse(201, Some(Json.toJson(SubmissionSuccess(false))))
        mockHttpPost(createAccountURL, request)(Some(response))
        await(connector.createAccount(request)) shouldBe response
      }
    }

    "update email" must {

      "return http response as it is to the caller" in {
        val response = HttpResponse(200, Some(Json.toJson(())))
        mockHttpPut(updateEmailURL, validNSIUserInfo)(Some(response))
        await(connector.updateEmail(validNSIUserInfo)) shouldBe response
      }
    }

  }

  private def testCommon[E, A, B](mockHttp:        ⇒ Option[HttpResponse] ⇒ Unit,
                                  result:          () ⇒ EitherT[Future, E, A],
                                  validBody:       B,
                                  testInvalidJSON: Boolean                       = true)(implicit writes: Writes[B]) = { // scalstyle:ignore method.length
    "make a request to the help-to-save backend" in {
      mockHttp(Some(HttpResponse(200)))
      await(result())
    }

    "return an error" when {

      if (testInvalidJSON) {
        "the call comes back with a 200 and an unknown JSON format" in {
          mockHttp(
            Some(HttpResponse(200, responseJson = Some(Json.parse(
              """
                |{
                |  "foo": "bar"
                |}
              """.
                stripMargin
            )))))

          await(result().value).isLeft shouldBe
            true
        }
      }

      "the call comes back with any other status other than 200" in {
        forAll { status: Int ⇒
          whenever(status =!= 200) {
            // check we get an error even though there was valid JSON in the response
            mockHttp(Some(HttpResponse(status, Some(Json.toJson(validBody)))))
            await(result().value).isLeft shouldBe true

          }
        }
      }

      "the future fails" in {
        mockHttp(None)
        await(result().value).isLeft shouldBe true
      }
    }
  }

}
