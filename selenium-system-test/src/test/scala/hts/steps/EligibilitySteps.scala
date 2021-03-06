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

import hts.browser.Browser
import hts.pages._
import hts.utils.ScenarioContext

class EligibilitySteps extends Steps {

  Given("^a user is in receipt of WTC$") {
    val _ = ScenarioContext.generateEligibleNINO()
  }

  When("^they apply for Help to Save$") {
    AuthorityWizardPage.authenticateEligibleUserOnAnyDevice(EligiblePage.expectedURL, ScenarioContext.currentNINO())
  }

  Then("^they see that they are eligible for Help to Save$") {
    Browser.checkCurrentPageIs(EligiblePage)
  }

  When("^they confirm their details and continue to create an account$") {
    EligiblePage.clickConfirmAndContinue()
  }

  Given("^DES is down$") {
    val _ = ScenarioContext.generateHTTPErrorCodeNINO(500)
  }

  Then("^they see a technical error page$") {
    Browser.checkCurrentPageIs(TechnicalErrorPage)
  }

  When("^they then click on still think you're eligible link$") {
    NotEligibleReason3Page.thinkYouAreEligible()
  }

  Then("^they see appeals and tax tribunal page$") {
    Browser.checkCurrentPageIs(ThinkYouAreEligiblePage)
  }

  Given("^a user has NINO (.*)$") { (nino: String) ⇒
    ScenarioContext.defineNINO(nino)
  }

  Then("^they see that they are NOT eligible for Help to Save with reason code (.+)$") { (reason: Int) ⇒
    reason match {
      case 3 ⇒
        Browser.checkCurrentPageIs(NotEligibleReason3Page)
        val notEligibleTextItems = NotEligibleReason3Page.notEligibleText
        notEligibleTextItems.foreach(text ⇒ Browser.isTextOnPage(text) shouldBe Right(())
        )
      case 5 ⇒
        Browser.checkCurrentPageIs(NotEligibleReason5Page)
        val notEligibleTextItems = NotEligibleReason5Page.notEligibleText
        notEligibleTextItems.foreach(text ⇒ Browser.isTextOnPage(text) shouldBe Right(())
        )
      case 4 | 9 ⇒
        Browser.checkCurrentPageIs(NotEligibleReason4And9Page)
        val notEligibleTextItems = NotEligibleReason4And9Page.notEligibleText
        notEligibleTextItems.foreach(text ⇒ Browser.isTextOnPage(text) shouldBe Right(())
        )
    }
  }
}
