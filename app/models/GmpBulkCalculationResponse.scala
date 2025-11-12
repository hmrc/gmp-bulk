/*
 * Copyright 2024 HM Revenue & Customs
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

import java.time.LocalDate
import play.api.i18n.Messages
import play.api.libs.json.{Json, OFormat}
import utils.HipErrorCodeMapper

case class ContributionsAndEarnings(taxYear: Int, contEarnings: String)
object ContributionsAndEarnings {
  implicit val formats: OFormat[ContributionsAndEarnings] = Json.format[ContributionsAndEarnings]
  private val contributionsAndEarningsBefore1987 = 1987

  def createFromNpsLcntearn(earnings: NpsLcntearn): ContributionsAndEarnings = {
    ContributionsAndEarnings(earnings.rattd_tax_year, earnings.rattd_tax_year match {
      case x if x < contributionsAndEarningsBefore1987 => f"${earnings.contributions_earnings}%1.2f"
      case _ => {
        val formatter = java.text.NumberFormat.getIntegerInstance
        formatter.setGroupingUsed(false)
        formatter.format(earnings.contributions_earnings)
      }
    })
  }

  //HIP Transformation
  def createFromHipDetails(details: ContributionsAndEarningsDetails): ContributionsAndEarnings = {
    ContributionsAndEarnings(details.taxYear, details.taxYear match {
      case x if x < contributionsAndEarningsBefore1987 => f"${details.contributionOrEarningsAmount}%1.2f"
      case _ => {
        val formatter = java.text.NumberFormat.getIntegerInstance
        formatter.format(details.contributionOrEarningsAmount)
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
    if (errorCode > 0) {
      Some(Messages(s"${errorCode}.reason"))
    } else {
      None
    }
  }

  def getPeriodErrorMessageWhat()(implicit messages: Messages): Option[String] = {
    if (errorCode > 0) {
      Some(Messages(s"${errorCode}.what"))
    } else {
      None
    }
  }

}

object CalculationPeriod {
  implicit val formats: OFormat[CalculationPeriod] = Json.format[CalculationPeriod]

  //DES TRANSFORMATION
  def createFromNpsLgmpcalc(npsLgmpcalc: NpsLgmpcalc): CalculationPeriod = {
    CalculationPeriod(npsLgmpcalc.scheme_mem_start_date.map(LocalDate.parse(_)), LocalDate.parse(npsLgmpcalc.scheme_end_date),
      f"${npsLgmpcalc.gmp_cod_allrate_tot}%1.2f", f"${npsLgmpcalc.gmp_cod_post_eightyeight_tot}%1.2f", npsLgmpcalc.revaluation_rate, npsLgmpcalc.gmp_error_code,
      Some(npsLgmpcalc.reval_calc_switch_ind),
      npsLgmpcalc.gmp_cod_p90_ts_tot.map(value => f"$value%1.2f"),
      npsLgmpcalc.gmp_cod_p90_os_tot.map(value => f"$value%1.2f"),
      npsLgmpcalc.inflation_proof_beyond_dod,
      npsLgmpcalc.npsLcntearn.map(earnings => earnings.map(ContributionsAndEarnings.createFromNpsLcntearn(_)))
    )
  }

  def mapRevaluationRate(rate: String): Int = rate match {
    case "S148"   => 1
    case "FIXED"  => 2
    case "LIMITED"=> 3
    case _        => 0 // Default to 0 for unknown values
  }

  //HIP Transformation
  def createFromHipGmpDetails(details: GuaranteedMinimumPensionDetails): CalculationPeriod = {
    CalculationPeriod(
      startDate = details.schemeMembershipStartDate.map(LocalDate.parse(_)),
      endDate = LocalDate.parse(details.schemeMembershipEndDate),
      gmpTotal = f"${details.gmpContractedOutDeductionsAllRateValue}%1.2f",
      post88GMPTotal = f"${details.post1988GMPContractedOutDeductionsValue}%1.2f",
      revaluationRate =  mapRevaluationRate(details.revaluationRate),//HIP sends revaluationRate as string,mapping to int.
      errorCode = HipErrorCodeMapper.mapGmpErrorCode(details.gmpErrorCode),
      revalued = Some(if (details.revaluationCalculationSwitchIndicator) 1 else 0),
      dualCalcPost90TrueTotal = details.post1990GMPContractedOutTrueSexTotal.map(value => f"$value%1.2f"),
      dualCalcPost90OppositeTotal = details.post1990GMPContractedOutOppositeSexTotal.map(value => f"$value%1.2f"),
      inflationProofBeyondDod = Some(if (details.inflationProofBeyondDateofDeath) 1 else 0),
      contsAndEarnings = details.contributionsAndEarningsDetailsList.map(details => details.map(ContributionsAndEarnings.createFromHipDetails))
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
    if (hasErrors) {
      var errors = calculationPeriods
        .filter(_.errorCode > 0)
        .map(_.errorCode)
      if (globalErrorCode > 0) {
        errors = errors :+ globalErrorCode
      }

      errors
    } else {
      List[Int]()
    }
  }

}

object GmpBulkCalculationResponse {
  implicit val formats: OFormat[GmpBulkCalculationResponse] = Json.format[GmpBulkCalculationResponse]

  def createFromCalculationResponse(calculationResponse: CalculationResponse):
  GmpBulkCalculationResponse = {
    GmpBulkCalculationResponse(
      calculationResponse.npsLgmpcalc.map(CalculationPeriod.createFromNpsLgmpcalc),
      calculationResponse.rejection_reason,
      calculationResponse.spa_date.map(LocalDate.parse(_)),
      calculationResponse.payable_age_date.map(LocalDate.parse(_)),
      calculationResponse.dod_date.map(LocalDate.parse(_)),
      calculationResponse.hasErrors

    )
  }

  def createFromHipCalculationResponse(hipResponse: HipCalculationResponse):
  GmpBulkCalculationResponse = {
    val calcPeriod = hipResponse.GuaranteedMinimumPensionDetailsList.map(CalculationPeriod.createFromHipGmpDetails)
    GmpBulkCalculationResponse(
      calculationPeriods = calcPeriod,
      globalErrorCode = 0,
      spaDate = hipResponse.statePensionAgeDate.map(LocalDate.parse(_)),
      payableAgeDate= hipResponse.payableAgeDate.map(LocalDate.parse(_)),
      dateOfDeath= hipResponse.dateOfDeath.map(LocalDate.parse(_)),
      containsErrors = calcPeriod.exists(_.errorCode > 0)

    )
  }

  def createFromHipFailuresResponse(failures: HipCalculationFailuresResponse): GmpBulkCalculationResponse = {
    val global = failures.failures.headOption.map(_.code).getOrElse(422)
    GmpBulkCalculationResponse(
      calculationPeriods = List.empty,
      globalErrorCode = global,
      spaDate = None,
      payableAgeDate = None,
      dateOfDeath = None,
      containsErrors = true
    )
  }

}
