/*
 * Copyright 2021 HM Revenue & Customs
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

package controllers

import helpers.RandomNino
import models._
import org.joda.time.{LocalDate, LocalDateTime}
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import org.scalatestplus.play.PlaySpec
import play.api.i18n.{Messages, MessagesImpl}
import uk.gov.hmrc.mongo.Awaiting
import uk.gov.hmrc.play.bootstrap.tools.Stubs.stubMessagesControllerComponents
import play.api.i18n.Messages.Implicits._

class CsvGeneratorSpec extends PlaySpec with GuiceOneAppPerSuite with Awaiting with MockitoSugar {

  val cc = stubMessagesControllerComponents()
  implicit val messages = MessagesImpl(cc.langs.availables.head, cc.messagesApi)
  object TestCsvGenerator extends CsvGenerator

  final val CSV_HEADER_ROWS = 2
  final val PERIOD_1_REVAL_COLUMN_INDEX = 21
  final val DEFAULT_DATE_FORMAT = "dd/MM/yyyy"
  
  val nino = RandomNino.generate
  val date = LocalDate.now().toString(DEFAULT_DATE_FORMAT)

  val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)
  val singlePeriodGmpBulkCalculationResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None)), 0, None, None, None)

  val multiplePeriodGmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
    CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
    CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
    CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None)), 0, None, None, None)

  val calculationResponseWithError = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None)), 56010, None, None, None)
  val calculationResponseWithNpsError = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 56018, None, None, None, None, None)), 0, None, None, None)

  val calculationRequestNoResponse = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, None))
  val calculationRequestsSingle = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(singlePeriodGmpBulkCalculationResponse)))

  val calculationRequestsMultiple = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(multiplePeriodGmpBulkCalculationResponse)),
    ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(singlePeriodGmpBulkCalculationResponse)))

  val calculationRequestWithError = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(calculationResponseWithError)))
  val calculationRequestWithNpsError = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(calculationResponseWithNpsError)))

  val bulkCalculationRequestNoReponse = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequestNoResponse, "userId", LocalDateTime.now(), true, 1, 0)
  val bulkCalculationRequestSingle = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequestsSingle, "userId", LocalDateTime.now(), true, 1, 0)
  val bulkCalculationRequestMultiple = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequestsMultiple, "userId", LocalDateTime.now(), true, 1, 0)
  val bulkCalculationRequestWithError = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequestWithError, "userId", LocalDateTime.now(), true, 1, 0)
  val bulkCalculationRequestWithNpsError = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequestWithNpsError, "userId", LocalDateTime.now(), true, 1, 0)

  def firstCsvDataLine(result: String): Array[String] = {
    val dataLine = result split "\n" drop CSV_HEADER_ROWS
    dataLine(0).split(",", -1) map { _.trim }
  }

  "CsvGenerator" must {

    val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)
    val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None)), 0, None, None, None)
    val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))

    val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

    "return bulk result as a csv string" in {

      val periodColumns = "gmp.period 1 gmp.period.start_date,gmp.period 1 gmp.period.end_date,gmp.period 1 gmp.period.total,gmp.period 1 gmp.period.post_88,gmp.period 1 gmp.period.post_90_true,gmp.period 1 gmp.period.post_90_opp,gmp.period 1 gmp.period.reval_rate,gmp.period 1 gmp.period.error,gmp.period 1 gmp.period.what,gmp.bulk.csv.globalerror.headers"

      val date = LocalDate.now().toString(DEFAULT_DATE_FORMAT)

      val csvRows = s"""gmp.success,S2730000B,$nino,John,Smith,ref1,gmp.calc_type.leaving,,$date,,gmp.generic.no,3.12,1.23,,,$date,$date,3.12,1.23,,,,,,,"""
      val columnHeaders = s"gmp.status,${messages("gmp.bulk.csv.headers")},${messages("gmp.bulk.totals.headers")},$periodColumns"
      val headerCount = columnHeaders.split(",").size
      val guidanceText = s"${messages("gmp.bulk.csv.guidance")},,,,,,,,,,,,"

      val lines = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.All)) split "\n"

      lines.head must be(guidanceText)
      lines.drop(1).head must be(columnHeaders)
      lines.drop(2).head must be(csvRows)
    }

    "not include dual calc values if dual calcs are not requested" in {

      val validCalculationRequest = ValidCalculationRequest("S2730000B", "BH000007A", "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

      val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "0", "0", 1, 0, None, Some("983.5"), Some("374.45"), None, None)
      ), 0, None, None, None)

      val calcRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
      val request = ProcessedBulkCalculationRequest("1", "ref-1", "test@test.com", "ref-1", calcRequests, "user1", LocalDateTime.now(), true, 1, 0)

      val result = TestCsvGenerator.generateCsv(request, Some(CsvFilter.All))

      result mustNot include("983.5")
      result mustNot include("092384")
    }

    "getCalculationsAsCsv when revaluation with different rates" must {

      val validCalculationRequest = ValidCalculationRequest("S2730000B", "BH000007A", "Smith", "John", Some("ref1"), Some(1), None, Some(0), None, None)

      val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.parse("2040-8-21")), LocalDate.parse("2035-8-21"), "3.12", "1.23", 1, 0, None, None, None, None, None),
        CalculationPeriod(Some(LocalDate.parse("2000-8-21")), LocalDate.parse("2005-8-21"), "3.12", "1.23", 2, 0, None, None, None, None, None),
        CalculationPeriod(Some(LocalDate.parse("1999-8-21")), LocalDate.parse("2000-8-21"), "3.12", "1.23", 3, 0, None, None, None, None, None),
        CalculationPeriod(Some(LocalDate.parse("1999-8-21")), LocalDate.parse("2000-8-21"), "3.12", "1.23", 1, 0, None, None, None, None, None)
      ), 0, None, None, None)

      val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))

      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      "return bulk result as a csv string" in {
        val periodColumns = "gmp.period 1 gmp.period.start_date,gmp.period 1 gmp.period.end_date,gmp.period 1 gmp.period.total,gmp.period 1 gmp.period.post_88,gmp.period 1 gmp.period.post_90_true,gmp.period 1 gmp.period.post_90_opp,gmp.period 1 gmp.period.reval_rate,gmp.period 1 gmp.period.error,gmp.period 1 gmp.period.what,gmp.period 2 gmp.period.start_date,gmp.period 2 gmp.period.end_date,gmp.period 2 gmp.period.total,gmp.period 2 gmp.period.post_88,gmp.period 2 gmp.period.post_90_true,gmp.period 2 gmp.period.post_90_opp,gmp.period 2 gmp.period.reval_rate,gmp.period 2 gmp.period.error,gmp.period 2 gmp.period.what,gmp.period 3 gmp.period.start_date,gmp.period 3 gmp.period.end_date,gmp.period 3 gmp.period.total,gmp.period 3 gmp.period.post_88,gmp.period 3 gmp.period.post_90_true,gmp.period 3 gmp.period.post_90_opp,gmp.period 3 gmp.period.reval_rate,gmp.period 3 gmp.period.error,gmp.period 3 gmp.period.what"

        val csvRows = """gmp.success,S2730000B,BH000007A,John,Smith,ref1,gmp.calc_type.specific_date,,21/08/2035,HMRC,gmp.generic.no,12.48,4.92,,,21/08/2040,21/08/2035,3.12,1.23,,,s148,,,21/08/2000,21/08/2005,3.12,1.23,,,Fixed,,,21/08/1999,21/08/2000,3.12,1.23,,,Limited,,,21/08/1999,21/08/2000,3.12,1.23,,,s148,,,,"""
        val guidanceText = s"${Messages("gmp.bulk.csv.guidance")},,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,"
        val columnHeaders = Messages("gmp.bulk.csv.headers") + "," + Messages("gmp.bulk.totals.headers") + "," + periodColumns

        val result = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.All))

        result must include(guidanceText)
        result must include(columnHeaders)
        result must include(csvRows)
      }
    }

    "set the correct calculation type" in {

      val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest.copy(calctype = Some(1))), None, Some(gmpBulkCalculationResponse)),
        ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest.copy(calctype = Some(2))), None, Some(gmpBulkCalculationResponse)),
        ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest.copy(calctype = Some(3))), None, Some(gmpBulkCalculationResponse)),
        ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest.copy(calctype = Some(4))), None, Some(gmpBulkCalculationResponse)))

      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      val result = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.All))

      result must include(Messages("gmp.calc_type.specific_date"))
      result must include(Messages("gmp.calc_type.survivor"))
      result must include(Messages("gmp.calc_type.spa"))
      result must include(Messages("gmp.calc_type.payable_age"))
    }

    "return no period headers when no periods exist in response" in {
      val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List.empty, 0, None, None, None)

      val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest.copy(calctype = Some(1))), None, Some(gmpBulkCalculationResponse)))
      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      val periodColumns = "Period 1 (start date),Period 1 (end date),Period 1 (total GMP),Period 1 (post 1988),Period 1 (post 1990 - true gender)," +
        "Period 1 (post 1990 - opposite gender),Period 1 (revaluation rate),Period 1 Error,Period 1 What to do"

      val result = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.All))

      result must not include(periodColumns)
    }

    "includes the error message" in {

      val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List.empty, 0, None, None, None)
      val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), Some("2010-01-31"), None, None, Some("2010-01-31"))

      val calculationRequests = List(
        ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)),
        ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), Some(Map(RequestFieldKey.SCON.toString -> "This row has an error")), None)
      )

      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      val result = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.All))

      result must include("S2730000B")
      result must include("This row has an error")
      result must include(Messages("gmp.status"))
      result must include(Messages("gmp.success"))
      result must include("31/01/2010")

    }

    "include period data" in {

      val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "4.12", "5.23", 0, 0, None, None, None, None, None)), 0, None, None, None)

      val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)
      val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      val result = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.All))

      result must include(LocalDate.now().toString(DEFAULT_DATE_FORMAT))
      result must include("3.12")
      result must include("4.12")
    }

    "include period data with a dual calc" in {

      val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, Some("6.78"), Some("4.56"), None, None),
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "4.12", "5.23", 0, 0, None, Some("1.45"), Some("8.90"), None, None)), 0, None, None, None)

      val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, Some(1), None)
      val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      val result = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.All))

      result must include(LocalDate.now().toString(DEFAULT_DATE_FORMAT))
      result must include("3.12")
      result must include("4.12")
    }

    "include period data with no start date" in {

      val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
        CalculationPeriod(None, LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None)), 0, None, None, None)

      val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)
      val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      val lines = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.All)) split "\n"
      val cells = lines.drop(2).head split ","

      cells.slice(15, 18) mustBe Seq("", LocalDate.now().toString(DEFAULT_DATE_FORMAT), "3.12")

    }

    "report a failed calculation if there is a period error" in {

      val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "4.12", "5.23", 0, 2, None, None, None, None, None)), 0, None, None, None)

      val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)
      val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      val lines = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.All)) split "\n"

      lines.drop(2).head must startWith(Messages("gmp.error"))
    }

    "only include successful calculations when requested" in {

      val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List.empty, 1, None, None, None)
      val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

      val periodsResponse = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "4.12", "5.23", 0, 56002, None, None, None, None, None)), 0, None, None, None, true)

      val calculationRequests = List(
        ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)),
        ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(periodsResponse)),
        ProcessReadyCalculationRequest("1", 1, None, None, None)
      )

      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      val result = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.Successful))

      result must not include ("This row has an error")
      result must not include (Messages("gmp.success"))
      result must not include (Messages("gmp.status"))
      result must not include (Messages("gmp.period.error"))
    }

    "only include failed calculations when requested" in {

      val partiallyFailedResponse = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "3.12", "1.23", 0, 0, None, None, None, None, None),
        CalculationPeriod(Some(LocalDate.now()), LocalDate.now(), "4.12", "5.23", 0, 56002, None, None, None, None, None)), 0, None, None, None, true)

      val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

      val calculationRequests = List(

        ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest.copy(scon = "S2730001B")), None, Some(partiallyFailedResponse)),
        ProcessReadyCalculationRequest("1", 2, Some(validCalculationRequest), Some(Map(RequestFieldKey.SCON.toString -> "This scon has an error",
          RequestFieldKey.NINO.toString -> "This nino has an error",
          RequestFieldKey.SURNAME.toString -> "This surname has an error",
          RequestFieldKey.FORENAME.toString -> "This forename has an error",
          RequestFieldKey.MEMBER_REFERENCE.toString -> "This mem_ref has an error",
          RequestFieldKey.CALC_TYPE.toString -> "This calc_type has an error",
          RequestFieldKey.DATE_OF_LEAVING.toString -> "This DOL has an error",
          RequestFieldKey.GMP_DATE.toString -> "This gmp_date has an error",
          RequestFieldKey.REVALUATION_RATE.toString -> "This rate has an error",
          RequestFieldKey.OPPOSITE_GENDER.toString -> "This dual_calc has an error")), None)
      )

      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      val result = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.Failed))

      result must include("This scon has an error")
      result must include("This nino has an error")
      result must include("This surname has an error")
      result must include("This forename has an error")
      result must include("This mem_ref has an error")
      result must include("This calc_type has an error")
      result must include("This DOL has an error")
      result must include("This gmp_date has an error")
      result must include("This rate has an error")
      result must include("This dual_calc has an error")
      result must not include Messages("gmp.success")
      result must not include Messages("gmp.status")
      result must include(Messages("56002.what"))
      result must not include "Total GMP"

    }

    "contain the field validation errors when present" in {

      val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)
      val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), Some(Map(RequestFieldKey.SURNAME.toString -> "Please enter a valid surname")), None))
      val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

      val result = TestCsvGenerator.generateCsv(bulkCalculationRequest, Some(CsvFilter.Failed))

      result must include("Please enter a valid surname")
    }

    "contain the line error when present in the correct column with all results" in {

      val errors = Map(RequestFieldKey.LINE_ERROR_TOO_FEW.toString -> "The line has an error")
      val requests = List(ProcessReadyCalculationRequest("1", 1, None, Some(errors), None))
      val bulkRequest = ProcessedBulkCalculationRequest("1", "ref1", "mail@mail.com", "reference1", requests, "userId", LocalDateTime.now(), true, 0, 1)

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.All))

      result must include("The line has an error,gmp.error.line.too_few")
    }

    "contain the line error when present in the correct column with failed results" in {

      val errors = Map(RequestFieldKey.LINE_ERROR_TOO_MANY.toString -> "The line has an error")
      val requests = List(ProcessReadyCalculationRequest("1", 1, None, Some(errors), None))
      val bulkRequest = ProcessedBulkCalculationRequest("1", "ref1", "mail@mail.com", "reference1", requests, "userId", LocalDateTime.now(), true, 0, 1)

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.Failed))

      result must include("The line has an error,gmp.error.line.too_many")
    }

    "contain the correct line error when there are no calculations" in {
      val errors = Map(RequestFieldKey.LINE_ERROR_EMPTY.toString -> "The line is empty")
      val requests = List(ProcessReadyCalculationRequest("1", 1, None, Some(errors), None))
      val bulkRequest = ProcessedBulkCalculationRequest("1", "ref1", "mail@mail.com", "ref1", requests, "userId", LocalDateTime.now(), true, 0, 1)

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.Failed))

      result must include("The line is empty")
    }

    "insert an empty reval rate when member in scheme, and calc type is Survivor" in {

      val bulkRequest = mock[ProcessedBulkCalculationRequest]
      val calcRequest = mock[ProcessReadyCalculationRequest]

      val response = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.now()), LocalDate.parse("2016-01-01"), "4.12", "5.23", 1, 0, None, None, None, None, None)),
        0, None, None, None)

      val validCalc = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(3), None, None, None, Some("2016-05-24"), Some(true))

      when(calcRequest.validCalculationRequest) thenReturn Some(validCalc)
      when(calcRequest.calculationResponse) thenReturn Some(response)
      when(bulkRequest.calculationRequests) thenReturn List(calcRequest)
      when(bulkRequest.userId) thenReturn "userId"

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.All))

      firstCsvDataLine(result)(PERIOD_1_REVAL_COLUMN_INDEX) mustBe ""
    }

    "insert an empty reval rate when member in scheme, and calc type is SPA" in {

      val bulkRequest = mock[ProcessedBulkCalculationRequest]
      val calcRequest = mock[ProcessReadyCalculationRequest]

      val response = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.now()), LocalDate.parse("2016-01-01"), "4.12", "5.23", 1, 0, None, None, None, None, None)),
        0, None, None, None)

      val validCalc = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(4), None, None, None, Some("2016-05-24"), Some(true))

      when(calcRequest.validCalculationRequest) thenReturn Some(validCalc)
      when(calcRequest.calculationResponse) thenReturn Some(response)
      when(bulkRequest.calculationRequests) thenReturn List(calcRequest)
      when(bulkRequest.userId) thenReturn "userId"

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.All))

      firstCsvDataLine(result)(PERIOD_1_REVAL_COLUMN_INDEX) mustBe ""
    }

    "insert an empty reval rate when member in scheme, and calc type is PA" in {

      val bulkRequest = mock[ProcessedBulkCalculationRequest]
      val calcRequest = mock[ProcessReadyCalculationRequest]

      val response = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.now()), LocalDate.parse("2016-01-01"), "4.12", "5.23", 1, 0, None, None, None, None, None)),
        0, None, None, None)

      val validCalc = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(2), None, None, None, Some("2016-05-24"), Some(true))

      when(calcRequest.validCalculationRequest) thenReturn Some(validCalc)
      when(calcRequest.calculationResponse) thenReturn Some(response)
      when(bulkRequest.calculationRequests) thenReturn List(calcRequest)
      when(bulkRequest.userId) thenReturn "userId"

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.All))

      firstCsvDataLine(result)(PERIOD_1_REVAL_COLUMN_INDEX) mustBe ""
    }

    "insert an empty reval rate into the period when the rate is HMRC" in {

      val bulkRequest = mock[ProcessedBulkCalculationRequest]
      val calcRequest = mock[ProcessReadyCalculationRequest]

      val response = GmpBulkCalculationResponse(List(
        CalculationPeriod(Some(LocalDate.now()), LocalDate.parse("2016-01-01"), "0", "0", 0, 2, None, None, None, None, None)),
        0, None, None, None)

      val validCalc = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(3), Some("2018-05-10"), None, None, Some("2016-08-24"), None)

      when(calcRequest.validCalculationRequest) thenReturn Some(validCalc)
      when(calcRequest.calculationResponse) thenReturn Some(response)
      when(bulkRequest.calculationRequests) thenReturn List(calcRequest)
      when(bulkRequest.userId) thenReturn "userId"

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.All))

      firstCsvDataLine(result)(PERIOD_1_REVAL_COLUMN_INDEX) mustBe ""

    }

    "return correct number of trailing commas for single period line when successful calculations requested" in {

      val expectedResult = s"S2730000B,${nino},John,Smith,ref1,gmp.calc_type.leaving,,${date},,gmp.generic.no,3.12,1.23,,,${date},${date},3.12,1.23,,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestSingle, Some(CsvFilter.Successful))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "return correct number of trailing commas for multiple period line when successful calculations requested" in {

      val expectedResult = s"S2730000B,${nino},John,Smith,ref1,gmp.calc_type.leaving,,${date},,gmp.generic.no,9.36,3.69,,,${date},${date},3.12,1.23,,,,${date},${date},3.12,1.23,,,,${date},${date},3.12,1.23,,," +
        "\n" + s"S2730000B,${nino},John,Smith,ref1,gmp.calc_type.leaving,,${date},,gmp.generic.no,3.12,1.23,,,${date},${date},3.12,1.23,,,,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestMultiple, Some(CsvFilter.Successful))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "return correct number of trailing commas for single period line when all calculations requested" in {

      val expectedResult = s"gmp.success,S2730000B,${nino},John,Smith,ref1,gmp.calc_type.leaving,,${date},,gmp.generic.no,3.12,1.23,,,${date},${date},3.12,1.23,,,,,,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestSingle, Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "return correct number of trailing commas for multiple period line when all calculations requested" in {

      val expectedResult = s"gmp.success,S2730000B,${nino},John,Smith,ref1,gmp.calc_type.leaving,,${date},,gmp.generic.no,9.36,3.69,,,${date},${date},3.12,1.23,,,,,,${date},${date},3.12,1.23,,,,,,${date},${date},3.12,1.23,,,,,,," +
        "\n" + s"gmp.success,S2730000B,${nino},John,Smith,ref1,gmp.calc_type.leaving,,${date},,gmp.generic.no,3.12,1.23,,,${date},${date},3.12,1.23,,,,,,,,,,,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestMultiple, Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "add no commas when no calculation response" in {

      val expectedResult = s"S2730000B,${nino},John,Smith,ref1,gmp.calc_type.leaving,,,,gmp.generic.no,0,0,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestNoReponse, Some(CsvFilter.Successful))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)

    }

    "add 8 commas when calculation error exists" in {

      val expectedResult = s"gmp.error,S2730000B,${nino},John,Smith,ref1,gmp.calc_type.leaving,,${date},,gmp.generic.no,3.12,1.23,,,${date},${date},3.12,1.23,,,,,,56010.reason,56010.what"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestWithError, Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "add 6 commas when nps error exists" in {

      val expectedResult = s"gmp.error,S2730000B,${nino},John,Smith,ref1,gmp.calc_type.leaving,,${date},,gmp.generic.no,3.12,1.23,,,${date},${date},3.12,1.23,,,,56018.reason,56018.what,,"
      val result = TestCsvGenerator.generateCsv(bulkCalculationRequestWithNpsError, Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "show no reval rate for calctype 1 and member still in scheme" in {
      val expectedResult = s"gmp.success,S2730000B,${nino},John,Smith,ref1,gmp.calc_type.specific_date,${date},${date},,gmp.generic.no,3.12,1.23,,,07/03/1983,${date},3.12,1.23,,,,,,,"
      val validCalcRequest = ValidCalculationRequest("S2730000B", s"${nino}", "Smith", "John", Some("ref1"), Some(1), Some(LocalDate.now().toString()), None, Some(0), Some(LocalDate.now().toString()), Some(true))
      val calcResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.parse("1983-03-07")), LocalDate.now(), "3.12", "1.23", 1, 0, Some(1), Some("0.00"), Some("0.00"), Some(0), None)), 0, None, None, None)
      val listCalcRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalcRequest), None, Some(calcResponse)))
      val bulkRequest = bulkCalculationRequestSingle.copy(calculationRequests = listCalcRequests)

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "show no reval rate for calctype 1 and member not still in scheme but first period still open" in {
      val expectedResult = s"gmp.success,S2730000B,${nino},John,Smith,ref1,gmp.calc_type.specific_date,${date},${date},,gmp.generic.no,3.12,1.23,,,07/03/1983,${date},3.12,1.23,,,,,,,"
      val validCalcRequest = ValidCalculationRequest("S2730000B", s"${nino}", "Smith", "John", Some("ref1"), Some(1), Some(LocalDate.now.toString()), None, Some(0), Some(LocalDate.now().toString()), Some(false))
      val calcResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.parse("1983-03-07")), LocalDate.now, "3.12", "1.23", 1, 0, Some(1), Some("0.00"), Some("0.00"), Some(0), None)), 0, None, None, None)
      val listCalcRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalcRequest), None, Some(calcResponse)))
      val bulkRequest = bulkCalculationRequestSingle.copy(calculationRequests = listCalcRequests)

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "not show reval rate for calctype 1 and member not still in scheme and period closed" in {
      val yesterdaysDate = LocalDate.now.minusDays(1)
      val expectedResult = s"gmp.success,S2730000B,${nino},John,Smith,ref1,gmp.calc_type.specific_date,${date},${yesterdaysDate.toString(DEFAULT_DATE_FORMAT)},,gmp.generic.no,3.12,1.23,,,07/03/1983,${yesterdaysDate.toString(DEFAULT_DATE_FORMAT)},3.12,1.23,,,,,,,"
      val validCalcRequest = ValidCalculationRequest("S2730000B", s"${nino}", "Smith", "John", Some("ref1"), Some(1), Some(yesterdaysDate.toString()), None, Some(0), Some(LocalDate.now().toString()), Some(false))
      val calcResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.parse("1983-03-07")), yesterdaysDate, "3.12", "1.23", 1, 0, Some(1), Some("0.00"), Some("0.00"), Some(0), None)), 0, None, None, None)
      val listCalcRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalcRequest), None, Some(calcResponse)))
      val bulkRequest = bulkCalculationRequestSingle.copy(calculationRequests = listCalcRequests)

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "not show reval rate for calctype 1 and SM, where DOL and reval date dates exist in the same tax year, reval date after current date" in {
      val yesterdaysDate = LocalDate.now.minusDays(1)
      val expectedResult = s"gmp.success,S2730000B,${nino},John,Smith,ref1,gmp.calc_type.specific_date,03/03/2017,01/01/2017,,gmp.generic.no,3.12,1.23,,,07/03/1983,03/03/2017,3.12,1.23,,,,,,,"
      val validCalcRequest = ValidCalculationRequest("S2730000B", s"${nino}", "Smith", "John", Some("ref1"), Some(1), Some(LocalDate.parse("2017-01-01").toString), None, Some(0), Some(LocalDate.parse("2017-03-03").toString()), Some(false))
      val calcResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.parse("1983-03-07")), LocalDate.parse("2017-03-03"), "3.12", "1.23", 1, 0, Some(1), Some("0.00"), Some("0.00"), Some(0), None)), 0, None, None, None)
      val listCalcRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalcRequest), None, Some(calcResponse)))
      val bulkRequest = bulkCalculationRequestSingle.copy(calculationRequests = listCalcRequests)

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }

    "not calculate revalution rate where DOL and reval dates exist in the same tax year, rval date before current date" in {
      val expectedResult = s"gmp.success,S2730000B,BH000002A,HARRY,STYLES,E2E06,gmp.calc_type.specific_date,12/10/2016,03/01/2017,,gmp.generic.yes,17.70,9.91,6.93,7.79,07/03/1983,12/10/2016,17.70,9.91,6.93,7.79,,,,,"
      val validCalcRequest = ValidCalculationRequest("S2730000B", "BH000002A", "STYLES", "HARRY", Some("E2E06"), Some(1), Some(LocalDate.parse("2017-01-03").toString), None, Some(1), Some(LocalDate.parse("2016-10-12").toString), Some(false))
      val calcResponse = GmpBulkCalculationResponse(List(CalculationPeriod(Some(LocalDate.parse("1983-03-07")), LocalDate.parse("2016-10-12"), "17.70", "9.91", 1, 0, Some(1), Some("6.93"), Some("7.79"), Some(0), None)), 0, None, None, None)
      val listCalcRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalcRequest), None, Some(calcResponse)))
      val bulkRequest = bulkCalculationRequestSingle.copy(calculationRequests = listCalcRequests)

      val result = TestCsvGenerator.generateCsv(bulkRequest, Some(CsvFilter.All))
      val rows = result.split("\n").tail.tail.mkString("\n")

      rows must be(expectedResult)
    }
  }

}
