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

import com.google.inject.Inject
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.mvc.{Request, Result}
import uk.gov.hmrc.helptosavefrontend.config.{ErrorHandler, FrontendAppConfig}
import uk.gov.hmrc.helptosavefrontend.util.Logging
import uk.gov.hmrc.play.bootstrap.controller.FrontendController

class BaseController @Inject() (implicit messagesApi: MessagesApi, appConfig: FrontendAppConfig)
  extends ErrorHandler() with FrontendController with I18nSupport with Logging {

  def internalServerError()(implicit request: Request[_]): Result =
    InternalServerError(internalServerErrorTemplate(request))
}
