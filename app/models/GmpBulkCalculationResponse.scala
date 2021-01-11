/*
 * Copyright 2020 HM Revenue & Customs
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

package models

import org.joda.time.LocalDate
import play.api.i18n.Messages
import play.api.i18n.Messages.Implicits._
import play.api.libs.json.Json
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

case class ContributionsAndEarnings(taxYear: Int, contEarnings: String)

object ContributionsAndEarnings {
  implicit val formats = Json.format[ContributionsAndEarnings]

  def createFromNpsLcntearn(earnings: NpsLcntearn): ContributionsAndEarnings = {
    ContributionsAndEarnings(earnings.rattd_tax_year, earnings.rattd_tax_year match {
      case x if x < 1987 => f"${earnings.contributions_earnings}%1.2f"
      case _ => {
        val formatter = java.text.NumberFormat.getIntegerInstance
        formatter.setGroupingUsed(false)
        formatter.format(earnings.contributions_earnings)
      }
    })
  }
}

case class CalculationPeriod(startDate: Option[LocalDate],
                             endDate: LocalDate,
                             gmpTotal: String,
                             post88GMPTotal: String,
                             revaluationRate: Int,
                             errorCode: Int,
                             revalued: Option[Int],
                             dualCalcPost90TrueTotal: Option[String],
                             dualCalcPost90OppositeTotal: Option[String],
                             inflationProofBeyondDod: Option[Int],
                             contsAndEarnings: Option[List[ContributionsAndEarnings]]
                            ) {

  def getPeriodErrorMessageReason()(implicit messages: Messages): Option[String] = {
    errorCode > 0 match {
      case true => Some(Messages(s"${errorCode}.reason"))
      case _ => None
    }
  }

  def getPeriodErrorMessageWhat()(implicit messages: Messages): Option[String] = {
    errorCode > 0 match {
      case true => Some(Messages(s"${errorCode}.what"))
      case _ => None
    }
  }

}

object CalculationPeriod {
  implicit val formats = Json.format[CalculationPeriod]

  def createFromNpsLgmpcalc(npsLgmpcalc: NpsLgmpcalc): CalculationPeriod = {
    CalculationPeriod(npsLgmpcalc.scheme_mem_start_date.map(new LocalDate(_)), new LocalDate(npsLgmpcalc.scheme_end_date),
      f"${npsLgmpcalc.gmp_cod_allrate_tot}%1.2f", f"${npsLgmpcalc.gmp_cod_post_eightyeight_tot}%1.2f", npsLgmpcalc.revaluation_rate, npsLgmpcalc.gmp_error_code,
      Some(npsLgmpcalc.reval_calc_switch_ind),
      npsLgmpcalc.gmp_cod_p90_ts_tot.map(value => f"$value%1.2f"),
      npsLgmpcalc.gmp_cod_p90_os_tot.map(value => f"$value%1.2f"),
      npsLgmpcalc.inflation_proof_beyond_dod,
      npsLgmpcalc.npsLcntearn.map(earnings => earnings.map(ContributionsAndEarnings.createFromNpsLcntearn(_)))
    )
  }
}


case class GmpBulkCalculationResponse(
                                       calculationPeriods: List[CalculationPeriod],
                                       globalErrorCode: Int,
                                       spaDate: Option[LocalDate],
                                       payableAgeDate: Option[LocalDate],
                                       dateOfDeath: Option[LocalDate],
                                       containsErrors: Boolean = false
                                     ) {

  def hasErrors: Boolean = calculationPeriods.foldLeft(globalErrorCode) {
    _ + _.errorCode
  } > 0

  def errorCodes: List[Int] = {
    hasErrors match {
      case false => List[Int]()
      case true =>
        var errors = calculationPeriods
          .filter(_.errorCode > 0)
          .map(_.errorCode)
        if (globalErrorCode > 0)
          errors = errors :+ globalErrorCode

        errors
    }
  }

}

object GmpBulkCalculationResponse {
  implicit val formats = Json.format[GmpBulkCalculationResponse]

  def createFromCalculationResponse(calculationResponse: CalculationResponse):
  GmpBulkCalculationResponse = {
    GmpBulkCalculationResponse(
      calculationResponse.npsLgmpcalc.map(CalculationPeriod.createFromNpsLgmpcalc(_)),
      calculationResponse.rejection_reason,
      calculationResponse.spa_date.map(new LocalDate(_)),
      calculationResponse.payable_age_date.map(new LocalDate(_)),
      calculationResponse.dod_date.map(new LocalDate(_)),
      calculationResponse.hasErrors

    )
  }
}
