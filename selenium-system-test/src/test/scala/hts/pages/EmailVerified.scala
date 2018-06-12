package hts.pages

import hts.browser.Browser
import hts.utils.Configuration
import org.scalatest.selenium.WebBrowser

object EmailVerified extends Page with WebBrowser{
  override val expectedURL: String = s"${Configuration.host}/help-to-save/email-verified"
  override val expectedPageHeader: Option[String] = Some("Email address verified")
  override val expectedPageTitle: Option[String] = Some("Email address verified")
}
