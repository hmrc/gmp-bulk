/*
 * Copyright 2025 HM Revenue & Customs
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

package utils


import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import utils.HipErrorCodeMapper

class HipErrorCodeMapperSpec extends PlaySpec with GuiceOneAppPerSuite {

  "HipErrorCodeMapper.mapRejectionReason" should {
    "return correct int for known rejection reason" in {
      HipErrorCodeMapper.mapRejectionReason("No match for person details provided") mustBe 63119
      HipErrorCodeMapper.mapRejectionReason("GMP Calculation not possible") mustBe 63120
      HipErrorCodeMapper.mapRejectionReason("Date of birth not held") mustBe 56010
      HipErrorCodeMapper.mapRejectionReason("Payable Age Calculation request with Revaluation Date") mustBe 63146
      HipErrorCodeMapper.mapRejectionReason("Survivor calculation requested but no date of death") mustBe 63147
      HipErrorCodeMapper.mapRejectionReason("Survivor calculation requested but revaluation date less than date of death") mustBe 63148
      HipErrorCodeMapper.mapRejectionReason("Customer input termination is after FRY") mustBe 63149
      HipErrorCodeMapper.mapRejectionReason("Customer input termination date after date of death") mustBe 63151
      HipErrorCodeMapper.mapRejectionReason("SPA Calculation request with Revaluation Date") mustBe 63152
      HipErrorCodeMapper.mapRejectionReason("Date of death held") mustBe 63121
      HipErrorCodeMapper.mapRejectionReason("No recorded scheme memberships held") mustBe 56069
      HipErrorCodeMapper.mapRejectionReason("Transfer link error") mustBe 56012
      HipErrorCodeMapper.mapRejectionReason("Account is not a full live account") mustBe 48160
      HipErrorCodeMapper.mapRejectionReason("Customer input termination date is before 05/04/16") mustBe 63166
    }
    "return 0 for unknown rejection reason" in {
      HipErrorCodeMapper.mapRejectionReason("Invalid Person") mustBe 0
    }
    "return 0 for empty string" in {
      HipErrorCodeMapper.mapRejectionReason("") mustBe 0
    }
    "return 0 for null" in {
      HipErrorCodeMapper.mapRejectionReason(null) mustBe 0
    }
  }
  "HipErrorCodeMapper.mapGmpErrorCode" should {
    "return correct int for known GMP error code" in {
      HipErrorCodeMapper.mapGmpErrorCode("Input revaluation date is before the termination date held on hmrc records") mustBe 63123
      HipErrorCodeMapper.mapGmpErrorCode("Single period scheme membership does not correspond to details held") mustBe 56023
      HipErrorCodeMapper.mapGmpErrorCode("Scheme details do not correspond to those held") mustBe 56068
      HipErrorCodeMapper.mapGmpErrorCode("No pre 1997 liability held for transfer chain") mustBe 56067
      HipErrorCodeMapper.mapGmpErrorCode("Commencement of scheme earlier than 16th birthday") mustBe 56013
      HipErrorCodeMapper.mapGmpErrorCode("Termination date later than date of death") mustBe 56016
      HipErrorCodeMapper.mapGmpErrorCode("Commencement of scheme earlier than tax year containing 16th birthday") mustBe 56014
      HipErrorCodeMapper.mapGmpErrorCode("commencement of scheme later than FRY") mustBe 56015
      HipErrorCodeMapper.mapGmpErrorCode("Scheme membership terminates later than the final relevant year") mustBe 56037
      HipErrorCodeMapper.mapGmpErrorCode("Scheme membership start date must not be later than date of death") mustBe 56073
      HipErrorCodeMapper.mapGmpErrorCode("reduced rate election – cannot process") mustBe 56018
      HipErrorCodeMapper.mapGmpErrorCode("Scheme clerically terminated – cannot process") mustBe 56019
      HipErrorCodeMapper.mapGmpErrorCode("Termination date overlaps with a subsequent scheme") mustBe 56020
      HipErrorCodeMapper.mapGmpErrorCode("More than one scheme with the same ECON – no control earnings") mustBe 56021
      HipErrorCodeMapper.mapGmpErrorCode("Benefit Scheme details not provided") mustBe 56070
      HipErrorCodeMapper.mapGmpErrorCode("Scheme membership start date invalid") mustBe 48095
      HipErrorCodeMapper.mapGmpErrorCode("End date of the input scheme membership does not correspond to the end date of the matched scheme membership") mustBe 58108
      HipErrorCodeMapper.mapGmpErrorCode("Invalid MOP selected for the schemes rights") mustBe 58047
      HipErrorCodeMapper.mapGmpErrorCode("Earnings recorded for pre-switch internal transfer invalid") mustBe 58161
      HipErrorCodeMapper.mapGmpErrorCode("Total earnings for Tax Year negative") mustBe 56007
      HipErrorCodeMapper.mapGmpErrorCode("Earnings missing") mustBe 56005
      HipErrorCodeMapper.mapGmpErrorCode("Earnings in excess") mustBe 56004
      HipErrorCodeMapper.mapGmpErrorCode("Earnings erroneous") mustBe 56003
      HipErrorCodeMapper.mapGmpErrorCode("Ratio check failure held") mustBe 56006
      HipErrorCodeMapper.mapGmpErrorCode("Notional & class 1 contributions for same tax year without controlled earnings") mustBe 56008
      HipErrorCodeMapper.mapGmpErrorCode("S148 or S37a not held") mustBe 56002
      HipErrorCodeMapper.mapGmpErrorCode("Contracted out period does not include 1990/1 to 1996/7 membership") mustBe 63150
      HipErrorCodeMapper.mapGmpErrorCode("Customer input revaluation rate was limited but scheme end date > 05/04/1997") mustBe 63167

    }

    "return 0 for unknown GMP error code" in {
      HipErrorCodeMapper.mapGmpErrorCode("Unknown error code") mustBe 0
    }
    "return 0 for empty string" in {
      HipErrorCodeMapper.mapGmpErrorCode("") mustBe 0
    }
    "return 0 for null" in {
      HipErrorCodeMapper.mapGmpErrorCode(null) mustBe 0

    }
  }
}