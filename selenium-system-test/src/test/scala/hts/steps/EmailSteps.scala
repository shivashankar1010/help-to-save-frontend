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

package hts.steps

import java.util.UUID

import hts.browser.Browser
import hts.pages._
import hts.utils.ScenarioContext

class EmailSteps extends Steps {

  And("^they select their GG email and proceed$") {
    Browser.checkCurrentPageIs(SelectEmailPage)
    SelectEmailPage.selectGGEmail()
  }

  When("^they start to create an account$") {
    EligiblePage.clickConfirmAndContinue()
  }

  Then("^they are asked to check their email for a verification email$") {
    Browser.checkCurrentPageIs(VerifyYourEmailPage)
  }

  Given("^they've chosen to enter a new email address during the application process$") {
    AuthorityWizardPage.authenticateEligibleUser(EligiblePage.expectedURL, ScenarioContext.currentNINO())
    EligiblePage.clickConfirmAndContinue()
    SelectEmailPage.setAndVerifyNewEmail("newemail@mail.com")
  }

  When("^they request a re-send of the verification email$") {
    VerifyYourEmailPage.resendVerificationEmail()
  }

  When("^they want to change their email again$") {
    VerifyYourEmailPage.changeEmail()
    SelectEmailPage.setAndVerifyNewEmail("secondnewemail@mail.com")
  }

  Then("^they are asked to enter an email address$") {
    Browser.checkCurrentPageIs(EnterEmailPage)
  }

  Then("^they see the final Create Account page$") {
    Browser.checkCurrentPageIs(CreateAccountPage)
  }

  When("^they enter a new email address$") {
    Browser.checkCurrentPageIs(SelectEmailPage)
    SelectEmailPage.setAndVerifyNewEmail("newemail@gmail.com")
  }

  Then("^they see the verify your email page$") {
    Browser.checkCurrentPageIs(VerifyYourEmailPage)
  }

  Given("^an applicant has NOT already had their new email address verified$") { () =>
    randomEmail = generateEmail()
    println("randomly generated email: " + randomEmail + "\n")
  }

  Given("^they submit their new email address for Help to Save$") { () =>
    AuthorityWizardPage.authenticateEligibleUser(EligiblePage.expectedURL, ScenarioContext.generateEligibleNINO())
    EligiblePage.clickConfirmAndContinue()
    Browser.checkCurrentPageIs(SelectEmailPage)
    SelectEmailPage.setAndVerifyNewEmail(randomEmail)
  }

  When("^they click on the email verification link$") { () =>
    var token = ""
    driver.navigate().to(s"https://test-www.tax.service.gov.uk/email-verification/verify?token=$token")
  }

  Then("^they see that their email address has been successfully verified$") { () =>
    Browser.checkCurrentPageIs(EmailVerified)
    Browser.isTextOnPage(randomEmail)
  }

  def generateEmail(): String = {
    s"${UUID.randomUUID().toString}@mail.com"
  }

  var randomEmail = ""
}
