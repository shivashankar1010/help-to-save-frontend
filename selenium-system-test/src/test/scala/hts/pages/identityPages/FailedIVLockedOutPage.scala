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

package hts.pages.identityPages

import hts.browser.Browser
import hts.pages.Page
import hts.utils.Configuration
import org.openqa.selenium.WebDriver

object FailedIVLockedOutPage extends IVPage {

  override val expectedURL: String = s"${Configuration.host}/help-to-save/failed-iv-locked-out"

  override val expectedPageHeader: Option[String] = Some("We were not able to verify your identity")

  override val expectedPageTitle: Option[String] = Some("We were not able to verify your identity")

  override def executeIVResultPageAction()(implicit driver: WebDriver): Unit = Browser.clickLinkTextOnceClickable("Exit to About Help to Save")
}
