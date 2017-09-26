/*
 * Copyright 2017 HM Revenue & Customs
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

package hts.pages

import hts.utils.Configuration
import org.openqa.selenium.WebDriver
import org.scalatest.selenium.WebBrowser.go

object Page {

  //val http: WSHttp

  def getCurrentUrl(implicit driver: WebDriver): String = driver.getCurrentUrl

  def getPageContent(implicit driver: WebDriver): String = driver.getPageSource

  //  def constructHttpRequest(httpMethod: String, uri: String, postBody: String): Future[HttpResponse] = {
  //    val path = s"${Configuration.host}/help-to-save/$uri"
  //    httpMethod match {
  //      case "GET" ⇒ http.get(path)
  //      case "POST"  ⇒ http.post(path, postBody)
  //    }
  //  }
  //
  //  def hitPage(implicit driver: WebDriver, httpMethod: String, uri: String): Future[Int] = {
  //    val request = constructHttpRequest(httpMethod, uri, "")
  //    request.map{
  //      x ⇒ x.status
  //    }
  //  }

  def url(uri: String): String = s"${Configuration.host}/help-to-save/$uri"

  def navigate(uri: String)(implicit driver: WebDriver): Unit =
    go to url(uri)

}
