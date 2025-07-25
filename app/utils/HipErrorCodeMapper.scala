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


object HipErrorCodeMapper {

  private final val DefaultErrorCode = 0

  private final val rejectionReasonMap: Map[String, Int] = Map(
    "No match for person details provided" -> 63119,
    "GMP Calculation not possible" -> 63120,
    "Date of birth not held" -> 56010,
    "Payable Age Calculation request with Revaluation Date" -> 63146,
    "Survivor calculation requested but no date of death" ->63147,
    "Survivor calculation requested but revaluation date less than date of death" ->63148,
    "Customer input termination is after FRY" -> 63149,
    "Customer input termination date after date of death" -> 63151,
    "SPA Calculation request with Revaluation Date" -> 63152,
    "Date of death held" -> 63121,
    "No recorded scheme memberships held" -> 56069,
    "Transfer link error" -> 56012,
    "Account is not a full live account" -> 48160,
    "Customer input termination date is before 05/04/16" -> 63166
  )
  private final val gmpErrorCodeMap: Map[String, Int] = Map(
    "Input revaluation date is before the termination date held on hmrc records" -> 63123,
    "Single period scheme membership does not correspond to details held" -> 56023,
    "Scheme details do not correspond to those held" -> 56068,
    "No pre 1997 liability held for transfer chain" -> 56067,
    "Commencement of scheme earlier than 16th birthday" -> 56013,
    "Termination date later than date of death" -> 56016,
    "Commencement of scheme earlier than tax year containing 16th birthday" -> 56014,
    "commencement of scheme later than FRY" -> 56015,
    "Scheme membership terminates later than the final relevant year" -> 56037,
    "Scheme membership start date must not be later than date of death" -> 56073,
    "reduced rate election – cannot process" -> 56018,
    "Scheme clerically terminated – cannot process" -> 56019,
    "Termination date overlaps with a subsequent scheme" -> 56020,
    "More than one scheme with the same ECON – no control earnings" -> 56021,
    "Benefit Scheme details not provided" -> 56070,
    "Scheme membership start date invalid" -> 48095,
    "End date of the input scheme membership does not correspond to the end date of the matched scheme membership" -> 58108,
    "Invalid MOP selected for the schemes rights" -> 58047,
    "Earnings recorded for pre-switch internal transfer invalid" -> 58161,
    "Total earnings for Tax Year negative" -> 56007,
    "Earnings missing" -> 56005,
    "Earnings in excess" -> 56004,
    "Earnings erroneous" -> 56003,
    "Ratio check failure held" -> 56006,
    "Notional & class 1 contributions for same tax year without controlled earnings" -> 56008,
    "S148 or S37a not held" -> 56002,
    "Contracted out period does not include 1990/1 to 1996/7 membership" -> 63150,
    "Customer input revaluation rate was limited but scheme end date > 05/04/1997" -> 63167

  )

  def mapRejectionReason(rejectionReason: String): Int =
    Option(rejectionReason).filter(_.nonEmpty).flatMap(rejectionReasonMap.get).getOrElse(DefaultErrorCode)

  def mapGmpErrorCode(gmpErrorCode: String): Int =
    Option(gmpErrorCode).filter(_.nonEmpty).flatMap(gmpErrorCodeMap.get).getOrElse(DefaultErrorCode)

}