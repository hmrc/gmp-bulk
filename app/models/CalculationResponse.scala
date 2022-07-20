/*
 * Copyright 2022 HM Revenue & Customs
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

import play.api.libs.json.Json

case class Scon(
                 contracted_out_prefix: String,
                 ascn_scon: Int,
                 modulus_19_suffix: String)

object Scon {
  implicit val formats = Json.format[Scon]
}

case class NpsLcntearn(
                      rattd_tax_year: Int,
                      contributions_earnings: BigDecimal
                        )

object NpsLcntearn{
  implicit val formats = Json.format[NpsLcntearn]
}

case class NpsLgmpcalc(
                        scheme_mem_start_date: Option[String],
                        scheme_end_date: String,
                        revaluation_rate: Int,
                        gmp_cod_post_eightyeight_tot: BigDecimal,
                        gmp_cod_allrate_tot: BigDecimal,
                        gmp_error_code: Int,
                        reval_calc_switch_ind: Int = 0,
                        gmp_cod_p90_ts_tot: Option[BigDecimal],
                        gmp_cod_p90_os_tot: Option[BigDecimal],
                        inflation_proof_beyond_dod: Option[Int],
                        npsLcntearn: Option[List[NpsLcntearn]]
                        )

object NpsLgmpcalc {
  implicit val formats = Json.format[NpsLgmpcalc]
}

case class CalculationResponse(
                                nino: String,
                                rejection_reason: Int,
                                spa_date: Option[String],
                                payable_age_date: Option[String],
                                dod_date: Option[String],
                                npsScon: Scon,
                                npsLgmpcalc: List[NpsLgmpcalc]
                                ){

  def hasErrors: Boolean = npsLgmpcalc.foldLeft(rejection_reason) {
    _ + _.gmp_error_code
  } > 0
}

object CalculationResponse {
  implicit val formats = Json.format[CalculationResponse]
}
