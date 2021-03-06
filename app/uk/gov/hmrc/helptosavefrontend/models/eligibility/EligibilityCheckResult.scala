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

package uk.gov.hmrc.helptosavefrontend.models.eligibility

import play.api.libs.json.{Format, Json}

sealed trait EligibilityCheckResult {
  val value: EligibilityCheckResponse
}

object EligibilityCheckResult {

  case class Eligible(value: EligibilityCheckResponse) extends EligibilityCheckResult

  case class Ineligible(value: EligibilityCheckResponse) extends EligibilityCheckResult

  case class AlreadyHasAccount(value: EligibilityCheckResponse) extends EligibilityCheckResult

  object Eligible {
    implicit val format: Format[Eligible] = Json.format[Eligible]
  }

  object Ineligible {
    implicit val format: Format[Ineligible] = Json.format[Ineligible]
  }

  implicit class Ops(val result: EligibilityCheckResult) extends AnyVal {
    def fold[A](ifEligible:          EligibilityCheckResponse ⇒ A,
                ifIneligible:        EligibilityCheckResponse ⇒ A,
                ifAlreadyHasAccount: EligibilityCheckResponse ⇒ A): A = result match {
      case Eligible(reason)          ⇒ ifEligible(reason)
      case Ineligible(reason)        ⇒ ifIneligible(reason)
      case AlreadyHasAccount(reason) ⇒ ifAlreadyHasAccount(reason)
    }
  }

}

