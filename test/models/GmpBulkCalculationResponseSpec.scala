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

import helpers.RandomNino
import java.time.LocalDate
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.BeforeAndAfter
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import org.scalatestplus.play.PlaySpec
import play.api.i18n.{Messages, MessagesImpl}
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModule}
import play.api.libs.json.Json
import play.api.test.Helpers.stubMessagesControllerComponents
import play.api.{Application, Mode}

class GmpBulkCalculationResponseSpec extends PlaySpec with GuiceOneAppPerSuite with MockitoSugar with BeforeAndAfter {

  val cc = stubMessagesControllerComponents()
  implicit val messages: MessagesImpl = MessagesImpl(cc.langs.availables.head, cc.messagesApi)
  def additionalConfiguration: Map[String, String] = Map( "logger.application" -> "ERROR",
    "logger.play" -> "ERROR",
    "logger.root" -> "ERROR",
    "org.apache.logging" -> "ERROR",
    "com.codahale" -> "ERROR")
  private val bindModules: Seq[GuiceableModule] = Seq()

  implicit override lazy val app: Application = new GuiceApplicationBuilder()
    .configure(additionalConfiguration)
    .bindings(bindModules:_*).in(Mode.Test)
    .build()

  val nino = RandomNino.generate

  "createFromNpsLgmpcalc" must {
    "correctly format currency amounts" in {

      val serverResponse = Json.parse(
        s"""{
              "nino": "$nino",
              "rejection_reason": 0,
              "spa_date" : "2012-01-01",
              "payable_age_date": "2012-01-01",
              "dod_date":"2016-01-01",
              "npsScon": {
              "contracted_out_prefix": "S",
              "ascn_scon": 1301234,
              "modulus_19_suffix": "T"
              },
              "npsLgmpcalc": [
              {
              "scheme_mem_start_date": "1978-04-06",
              "scheme_end_date": "2004-04-05",
              "revaluation_rate": 1,
              "gmp_cod_post_eightyeight_tot": 1.2,
              "gmp_cod_allrate_tot": 1,
              "gmp_error_code": 0,
              "reval_calc_switch_ind": 0,
              "npsLcntearn" : [
              {
                "rattd_tax_year": 1986,
                "contributions_earnings": 239.8
              },
              {
                "rattd_tax_year": 1987,
                "contributions_earnings": 1560
              }
              ]
              }
              ]
              }""").as[CalculationResponse]

      val gmpResponse = GmpBulkCalculationResponse.createFromCalculationResponse(serverResponse)

      gmpResponse.calculationPeriods.head.post88GMPTotal must be("1.20")
      gmpResponse.calculationPeriods.head.gmpTotal must be("1.00")
      gmpResponse.calculationPeriods.head.contsAndEarnings.get.head.contEarnings must be("239.80")
      gmpResponse.calculationPeriods.head.contsAndEarnings.get.tail.head.contEarnings must be("1560")
      gmpResponse.dateOfDeath must be(Some(LocalDate.parse("2016-01-01")))
    }


    "has errors" must {
      "return true when global error" in {
        val response = GmpBulkCalculationResponse(Nil, 56010, None, None, None)
        response.hasErrors must be(true)
      }

      "return false when no cop errorsr" in {
        val response = GmpBulkCalculationResponse(
          List(CalculationPeriod(Some(LocalDate.of(2015, 11, 10)), LocalDate.of(2015, 11, 10), "1.11", "2.22", 1, 0, Some(1), None, None, None, None),
               CalculationPeriod(Some(LocalDate.of(2015, 11, 10)), LocalDate.of(2015, 11, 10), "1.11", "2.22", 1, 0, Some(1), None, None, None, None)), 0, None, None, None)
        response.hasErrors must be(false)
      }

      "return true when one cop error" in {
        val response = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.of(2015, 11, 10)), LocalDate.of(2015, 11, 10), "1.11", "2.22", 1, 0, Some(1), None, None, None, None),
               CalculationPeriod(Some(LocalDate.of(2015, 11, 10)), LocalDate.of(2015, 11, 10), "1.11", "2.22", 1, 6666, None, None, None, None, None)), 0, None, None, None)
        response.hasErrors must be(true)
      }

      "return true when multi cop error" in {
        val response = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.of(2015, 11, 10)), LocalDate.of(2015, 11, 10), "0.00", "0.00", 0, 56023, None, None, None, None, None),
               CalculationPeriod(Some(LocalDate.of(2010, 11, 10)), LocalDate.of(2011, 11, 10), "0.00", "0.00", 0, 56007, None, None, None, None, None)), 0, None, None, None)
        response.hasErrors must be(true)
      }
    }

    "errorCodes" must {
      "return an empty list when no error codes" in {
        val response = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.of(2012, 1, 1)), LocalDate.of(2015, 1, 1), "1.11", "2.22", 1, 0, Some(1), None, None, None, None)), 0, None, None, None)
        response.errorCodes.size must be(0)
        response.calculationPeriods.head.getPeriodErrorMessageReason() must be(None)
        response.calculationPeriods.head.getPeriodErrorMessageWhat() must be(None)
      }

      "return a list of error codes with period error code" in {
        val response = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.of(2015, 11, 10)),LocalDate.of(2015, 11, 10), "0.00", "0.00", 0, 63151, None, None, None, None, None)), 0, None, None, None)
        response.errorCodes.size must be(1)
        response.errorCodes.head must be(63151)
        response.calculationPeriods.head.getPeriodErrorMessageReason() must be(Some(Messages("63151.reason")))
        response.calculationPeriods.head.getPeriodErrorMessageWhat() must be(Some(Messages("63151.what")))
      }

      "return a list of error codes with period error codes" in {
        val response = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.of(2015, 11, 10)),LocalDate.of(2015, 11, 10), "0.00", "0.00", 0, 56023, None, None, None, None, None),
               CalculationPeriod(Some(LocalDate.of(2010, 11, 10)),LocalDate.of(2011, 11, 10), "0.00", "0.00", 0, 56007, None, None, None, None, None),
               CalculationPeriod(Some(LocalDate.of(2010, 11, 10)),LocalDate.of(2011, 11, 10), "0.00", "0.00", 0, 0, None, None, None, None, None)), 0, None, None, None)
        response.errorCodes.size must be(2)
        response.errorCodes must be(List(56023, 56007))
      }

      "return a list of error codes with period error codes and global error code" in {
        val response = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.of(2015, 11, 10)),LocalDate.of(2015, 11, 10), "0.00", "0.00", 0, 56023, None, None, None, None, None),
               CalculationPeriod(Some(LocalDate.of(2010, 11, 10)),LocalDate.of(2011, 11, 10), "0.00", "0.00", 0, 56007, None, None, None, None, None),
               CalculationPeriod(Some(LocalDate.of(2010, 11, 10)),LocalDate.of(2011, 11, 10), "0.00", "0.00", 0, 0, None, None, None, None, None)), 48160, None, None, None)
        response.errorCodes.size must be(3)
        response.errorCodes must be(List(56023, 56007, 48160))
      }
    }

  }

  "GmpBulkCalculationResponse.createFromHipCalculationResponse" should {

    "Correctly transform a full HIP response with all fields" in{

      val hipJson = Json.parse(
        s"""{
          "nationalInsuranceNumber": "AA000001A",
          "schemeContractedOutNumberDetails": "S2123456B",
          "rejectionReason": "No match for person details provided",
          "payableAgeDate": "2022-06-27",
          "statePensionAgeDate": "2022-06-27",
          "dateOfDeath": "2022-06-27",
          "GuaranteedMinimumPensionDetailsList": [
          {
          "schemeMembershipStartDate": "2022-06-27",
          "schemeMembershipEndDate": "2022-06-27",
          "revaluationRate": "FIXED",
          "post1988GMPContractedOutDeductionsValue": 10.56,
          "gmpContractedOutDeductionsAllRateValue": 10.56,
          "gmpErrorCode": "Input revaluation date is before the termination date held on hmrc records",
          "revaluationCalculationSwitchIndicator": true,
          "post1990GMPContractedOutTrueSexTotal": 10.56,
          "post1990GMPContractedOutOppositeSexTotal": 10.56,
          "inflationProofBeyondDateofDeath": true,
          "contributionsAndEarningsDetailsList": [{
                                                 "taxYear": 2022,
                                                 "contributionOrEarningsAmount": 10.56
                                                 }]
          }
          ]
          }""").as[HipCalculationResponse]


      val gmpResponse = GmpBulkCalculationResponse.createFromHipCalculationResponse(hipJson)

      gmpResponse.spaDate mustBe (Some(LocalDate.parse("2022-06-27")))
      gmpResponse.payableAgeDate mustBe (Some(LocalDate.parse("2022-06-27")))
      gmpResponse.dateOfDeath mustBe (Some(LocalDate.parse("2022-06-27")))
      gmpResponse.calculationPeriods must have size 1
      val period = gmpResponse.calculationPeriods.head
      period.startDate mustBe (Some(LocalDate.parse("2022-06-27")))
      period.endDate mustBe (LocalDate.parse("2022-06-27"))
      period.revaluationRate mustBe 2 // FIXED mapped to 2
      period.gmpTotal mustBe "10.56"
      period.post88GMPTotal mustBe "10.56"
      period.revalued must be (Some(1))
      period.dualCalcPost90TrueTotal mustBe Some("10.56")
      period.dualCalcPost90OppositeTotal mustBe Some("10.56")
      period.inflationProofBeyondDod mustBe Some(1)
      period.contsAndEarnings.isDefined mustBe true
      val earnings = period.contsAndEarnings.get.head
      earnings.taxYear mustBe 2022
      earnings.contEarnings mustBe "11" // Note: For >=1987, no decimals and rounding to nearest integer
      gmpResponse.globalErrorCode mustBe 63119
      gmpResponse.containsErrors mustBe true
      gmpResponse.hasErrors mustBe true
      gmpResponse.errorCodes must contain (gmpResponse.globalErrorCode)
      gmpResponse.errorCodes must contain (period.errorCode)


    }

    "handle an empty GuaranteedMinimumPensionDetailsList gracefully" in {
      val hipJson =
        """
          {
            "nationalInsuranceNumber": "",
            "schemeContractedOutNumberDetails": "",
            "rejectionReason": "Some error",
            "GuaranteedMinimumPensionDetailsList": []
          }
        """
      val hipResponse = Json.parse(hipJson).as[HipCalculationResponse]
      val result = GmpBulkCalculationResponse.createFromHipCalculationResponse(hipResponse)

      result.calculationPeriods mustBe empty
    }

    "handle an empty contributionsAndEarningsDetailsList gracefully" in{
      val hipJson =
        """{
          "nationalInsuranceNumber": "AA000001A",
          "schemeContractedOutNumberDetails": "S2123456B",
          "rejectionReason": "No match for person details provided",
          "payableAgeDate": "2022-06-27",
          "statePensionAgeDate": "2022-06-27",
          "dateOfDeath": "2022-06-27",
          "GuaranteedMinimumPensionDetailsList": [
          {
          "schemeMembershipStartDate": "2022-06-27",
          "schemeMembershipEndDate": "2022-06-27",
          "revaluationRate": "(NONE)",
          "post1988GMPContractedOutDeductionsValue": 10.56,
          "gmpContractedOutDeductionsAllRateValue": 10.56,
          "gmpErrorCode": "Input revaluation date is before the termination date held on hmrc records",
          "revaluationCalculationSwitchIndicator": true,
          "post1990GMPContractedOutTrueSexTotal": 10.56,
          "post1990GMPContractedOutOppositeSexTotal": 10.56,
          "inflationProofBeyondDateofDeath": true,
          "contributionsAndEarningsDetailsList": []
          }
          ]
          }
          """

      val hipResponse =Json.parse(hipJson).as[HipCalculationResponse]

      val gmpResponse = GmpBulkCalculationResponse.createFromHipCalculationResponse(hipResponse)

      gmpResponse.calculationPeriods.head.contsAndEarnings.head mustBe empty

    }

    "handle an empty GuaranteedMinimumPensionDetailsList and rejectionReason gracefully" in {
      val hipJson =
        """
          {
            "nationalInsuranceNumber": "",
            "schemeContractedOutNumberDetails": "",
            "rejectionReason": "",
            "GuaranteedMinimumPensionDetailsList": []
          }
        """
      val hipResponse = Json.parse(hipJson).as[HipCalculationResponse]
      val result = GmpBulkCalculationResponse.createFromHipCalculationResponse(hipResponse)

      result.globalErrorCode mustBe 0
    }
  }

  "createFromHipGmpDetails " should {
    "Correctly map revaluationRate string enum to expected int values" in {

      val details = GuaranteedMinimumPensionDetails(
        schemeMembershipStartDate = Some("2020-01-01"),
        schemeMembershipEndDate = "2024-01-01",
        gmpContractedOutDeductionsAllRateValue = BigDecimal("123.45"),
        post1988GMPContractedOutDeductionsValue = BigDecimal("67.89"),
        revaluationRate = "FIXED",
        gmpErrorCode = "Input revaluation date is before the termination date held on hmrc records",
        revaluationCalculationSwitchIndicator = true,
        post1990GMPContractedOutTrueSexTotal = Some(BigDecimal("45.67")),
        post1990GMPContractedOutOppositeSexTotal = Some(BigDecimal("23.45")),
        inflationProofBeyondDateofDeath = false,
        contributionsAndEarningsDetailsList = None
      )

      val result = CalculationPeriod.createFromHipGmpDetails(details)
      result.revaluationRate mustBe 2

    }

    "default to 0 if revaluationRate is unknown" in {

      val details = GuaranteedMinimumPensionDetails(
        schemeMembershipStartDate = Some("2020-01-01"),
        schemeMembershipEndDate = "2024-01-01",
        gmpContractedOutDeductionsAllRateValue = BigDecimal("123.45"),
        post1988GMPContractedOutDeductionsValue = BigDecimal("67.89"),
        revaluationRate = "UNKNOWN",
        gmpErrorCode = "Input revaluation date is before the termination date held on hmrc records",
        revaluationCalculationSwitchIndicator = true,
        post1990GMPContractedOutTrueSexTotal = Some(BigDecimal("45.67")),
        post1990GMPContractedOutOppositeSexTotal = Some(BigDecimal("23.45")),
        inflationProofBeyondDateofDeath = false,
        contributionsAndEarningsDetailsList = None
      )

      val result = CalculationPeriod.createFromHipGmpDetails(details)
      result.revaluationRate mustBe 0

    }

    "Correctly map revaluationRate (NONE) to expected 0 int value" in {

      val details = GuaranteedMinimumPensionDetails(
        schemeMembershipStartDate = Some("2020-01-01"),
        schemeMembershipEndDate = "2024-01-01",
        gmpContractedOutDeductionsAllRateValue = BigDecimal("123.45"),
        post1988GMPContractedOutDeductionsValue = BigDecimal("67.89"),
        revaluationRate = "(NONE)",
        gmpErrorCode = "Input revaluation date is before the termination date held on hmrc records",
        revaluationCalculationSwitchIndicator = true,
        post1990GMPContractedOutTrueSexTotal = Some(BigDecimal("45.67")),
        post1990GMPContractedOutOppositeSexTotal = Some(BigDecimal("23.45")),
        inflationProofBeyondDateofDeath = false,
        contributionsAndEarningsDetailsList = None
      )

      val result = CalculationPeriod.createFromHipGmpDetails(details)
      result.revaluationRate mustBe 0

    }
    "Correctly map revaluationRate S148 to expected 1 int value" in {

      val details = GuaranteedMinimumPensionDetails(
        schemeMembershipStartDate = Some("2020-01-01"),
        schemeMembershipEndDate = "2024-01-01",
        gmpContractedOutDeductionsAllRateValue = BigDecimal("123.45"),
        post1988GMPContractedOutDeductionsValue = BigDecimal("67.89"),
        revaluationRate = "S148",
        gmpErrorCode = "Input revaluation date is before the termination date held on hmrc records",
        revaluationCalculationSwitchIndicator = true,
        post1990GMPContractedOutTrueSexTotal = Some(BigDecimal("45.67")),
        post1990GMPContractedOutOppositeSexTotal = Some(BigDecimal("23.45")),
        inflationProofBeyondDateofDeath = false,
        contributionsAndEarningsDetailsList = None
      )

      val result = CalculationPeriod.createFromHipGmpDetails(details)
      result.revaluationRate mustBe 1

    }
    "Correctly map revaluationRate LIMITED to expected 3 int value" in {

      val details = GuaranteedMinimumPensionDetails(
        schemeMembershipStartDate = Some("2020-01-01"),
        schemeMembershipEndDate = "2024-01-01",
        gmpContractedOutDeductionsAllRateValue = BigDecimal("123.45"),
        post1988GMPContractedOutDeductionsValue = BigDecimal("67.89"),
        revaluationRate = "LIMITED",
        gmpErrorCode = "Input revaluation date is before the termination date held on hmrc records",
        revaluationCalculationSwitchIndicator = true,
        post1990GMPContractedOutTrueSexTotal = Some(BigDecimal("45.67")),
        post1990GMPContractedOutOppositeSexTotal = Some(BigDecimal("23.45")),
        inflationProofBeyondDateofDeath = false,
        contributionsAndEarningsDetailsList = None
      )

      val result = CalculationPeriod.createFromHipGmpDetails(details)
      result.revaluationRate mustBe 3

    }
  }

}