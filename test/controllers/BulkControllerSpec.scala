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

package controllers

import connectors.{EmailConnector, ReceivedUploadTemplate}
import controllers.auth.FakeAuthAction
import helpers.RandomNino
import models._

import java.time.{LocalDate, LocalDateTime}
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import org.scalatestplus.play.PlaySpec
import play.api.i18n.{Messages, MessagesImpl}
import play.api.libs.json.{JsString, Json}
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test.{FakeHeaders, FakeRequest, Helpers}
import repositories.{BulkCalculationMongoRepository, BulkCalculationRepository}
import uk.gov.hmrc.auth.core.AuthConnector

import scala.concurrent.{ExecutionContext, Future}

class BulkControllerSpec extends PlaySpec with GuiceOneAppPerSuite with MockitoSugar {

  val cc = stubMessagesControllerComponents()
  implicit val messages: MessagesImpl = MessagesImpl(cc.langs.availables.head, cc.messagesApi)
  val mockRepo = mock[BulkCalculationRepository]
  val mockEmailConnector = mock[EmailConnector]
  val createdAt = Some(LocalDateTime.now)
  val csvGenerator = app.injector.instanceOf[CsvGenerator]
  val authConnector = mock[AuthConnector]
  val fakeAuthAction = FakeAuthAction(authConnector)
  lazy val mockRepository = mock[BulkCalculationMongoRepository]

  object TestBulkController extends BulkController(fakeAuthAction, mockEmailConnector, csvGenerator, stubMessagesControllerComponents(), mockRepository, ExecutionContext.global) {
    override lazy val repository = mockRepo
  }

  val nino = RandomNino.generate

  "BulkController" must {

    val json =
      s"""
              {
                "uploadReference" : "UPLOAD1234",
                "email" : "test@test.com",
                "reference" : "REF1234",
                "calculationRequests" : [
                  {
                    "lineId": 1,

                    "validCalculationRequest": {
                      "scon" : "S2730000B",
                      "memberReference": "MEMREF123",
                      "nino" : "$nino",
                      "surname" : "Richard-Smith",
                      "firstForename": "Cliff",
                      "calctype" : 1,
                      "revaluationDate": "2018-01-01",
                      "revaluationRate": 2,
                      "requestEarnings": 1,
                      "dualCalc" : 1,
                      "terminationDate" : "2016-07-07"
                    }
                  },
                  {
                    "lineId" : 2,
                    "validationError" : "No scon supplied"
                  },
                  {
                    "lineId" : 3,
                    "validCalculationRequest": {
                      "scon" : "S2730000B",
                      "nino" : "XR106701A",
                      "surname" : "Richard-Smith",
                      "firstForename": "Cliff",
                      "calctype" : 0
                    }
                  },
                  {
                    "lineId" : 4,
                    "validationError" : "Invalid scon format"
                  }],
                "userId" : "123456",
                "timestamp" : "2016-04-26T14:53:18.308",
                "complete" : true
              }
      """

    "POST" must {

      "sending correct data" must {

        "return an accepted status code" in {
          when(mockRepo.insertBulkDocument(ArgumentMatchers.any())).thenReturn(Future.successful(true))
          val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = Json.parse(json))
          val result = TestBulkController.post("USER_ID").apply(fakeRequest)
          status(result) must be(OK)
        }

        "return conflict when inserting a duplicate" in {
          when(mockRepo.insertBulkDocument(ArgumentMatchers.any())).thenReturn(Future.successful(false))
          val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = Json.parse(json))
          val result = TestBulkController.post("USER_ID").apply(fakeRequest)
          status(result) must be(CONFLICT)
        }

        "when unable to save into mongo" must {

          "return an internal server error" in {

            when(mockRepo.insertBulkDocument(ArgumentMatchers.any())).thenReturn(Future.failed(new RuntimeException("Exception")))

            val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = Json.parse(json))
            val result = TestBulkController.post("USER_ID").apply(fakeRequest)
            intercept[RuntimeException] {
              status(result) must be(INTERNAL_SERVER_ERROR)
            }

          }
        }

        "send a file received notification email to the user" in {
          val c = ArgumentCaptor.forClass(classOf[ReceivedUploadTemplate])
          when(mockEmailConnector.sendReceivedTemplatedEmail(ArgumentMatchers.any())(ArgumentMatchers.any())).thenReturn(Future.successful(true))
          val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = Json.parse(json))
          TestBulkController.post("USER_ID")(fakeRequest)
          verify(mockEmailConnector).sendReceivedTemplatedEmail(c.capture())(ArgumentMatchers.any())
          c.getValue.email must be("test@test.com")
          c.getValue.uploadReference must be("REF1234")
        }
      }

      "sending incorrect data" must {

        "return a bad request status code" in {
          val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = Json.toJson("""{ "random" : "json" }"""))
          val result = TestBulkController.post("USER_ID").apply(fakeRequest)
          status(result) must be(BAD_REQUEST)
        }
      }

      "sending no data" must {

        "return a bad request status code" in {
          val fakeRequest = FakeRequest(method = "POST", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = Json.toJson(""""""))
          val result = TestBulkController.post("USER_ID").apply(fakeRequest)
          status(result) must be(BAD_REQUEST)
        }
      }
    }

    "getPreviousRequests" must {

      "retrieve list of previous bulk calculation requests" in {
        when(mockRepo.findByUserId(ArgumentMatchers.any())).thenReturn(Future.successful(Option(List(BulkPreviousRequest("uploadRef", "ref", LocalDateTime.now, LocalDateTime.now)))))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = AnyContentAsEmpty)
        val result = TestBulkController.getPreviousRequests("USER_ID").apply(fakeRequest)
        status(result) must be(OK)

        val uploadRefs = (contentAsJson(result).\\("uploadReference"))
        uploadRefs.head.as[JsString].value must be("uploadRef")
        val refs = (contentAsJson(result).\\("reference"))
        refs.head.as[JsString].value must be("ref")
      }

      "retrieve empty list of previous bulk calculation requests" in {
        when(mockRepo.findByUserId(ArgumentMatchers.any())).thenReturn(Future.successful(Option(Nil)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = AnyContentAsEmpty)
        val result = TestBulkController.getPreviousRequests("USER_ID").apply(fakeRequest)
        status(result) must be(OK)

        val uploadRefs = (contentAsJson(result).\\("uploadReference"))
        uploadRefs.size must be(0)
        val refs = (contentAsJson(result).\\("reference"))
        refs.size must be(0)
      }

    }

    "getCalculationsAsCsv" must {

      val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

      "return not found with an invalid file reference" in {

        when(mockRepo.findByReference(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(None))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "USER_ID", CsvFilter.All)(fakeRequest)

        status(result) must be(NOT_FOUND)

      }

      "does not authenticate when user id is not valid" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List.empty, 0, None, None, None)

        val id = "1"
        val calculationRequests = List(ProcessReadyCalculationRequest(id, 1, Some(validCalculationRequest.copy(calctype = Some(1))), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = ProcessedBulkCalculationRequest(id, "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

        when(mockRepo.findByReference(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))

        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("wrongId", "USER_ID", CsvFilter.All)(fakeRequest)

        status(result) must be(FORBIDDEN)
      }

    }

    "getResultsSummary" must {

      "retrieve a results summary" in {

        when(mockRepo.findSummaryByReference(ArgumentMatchers.any())).thenReturn(Future.successful(Some(BulkResultsSummary("my ref", Some(10), Some(1), "USER_ID"))))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = AnyContentAsEmpty)
        val result = TestBulkController.getResultsSummary("USER_ID", "thing-ref")(fakeRequest)
        (contentAsJson(result).\("reference")).as[JsString].value must be("my ref")

      }

      "return 404 when not found" in {

        when(mockRepo.findSummaryByReference(ArgumentMatchers.any())).thenReturn(Future.successful(None))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = AnyContentAsEmpty)
        val result = TestBulkController.getResultsSummary("USER_ID", "thing-ref")(fakeRequest)
        status(result) must be(NOT_FOUND)

      }

      "return 403 unauthorized when userid doesn't match" in {
        when(mockRepo.findSummaryByReference(ArgumentMatchers.any())).thenReturn(Future.successful(Some(BulkResultsSummary("my ref", Some(10), Some(1), "USER_ID"))))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = AnyContentAsEmpty)
        val result = TestBulkController.getResultsSummary("WRONG_USER_ID", "thing-ref")(fakeRequest)
        status(result) must be(FORBIDDEN)
      }
    }

    "getContributionsAndEarningsAsCsv" must {

      "include the contributions data" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.of(2001, 1, 1)), LocalDate.of(2005, 1, 1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "123.45"))))), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val id = "1"
        val calculationRequests = List(ProcessReadyCalculationRequest(id, 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = ProcessedBulkCalculationRequest(id, "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

        when(mockRepo.findByReference(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("userId", "reference")(fakeRequest)

        contentAsString(result) must include(Messages("gmp.bulk.csv.contributions.headers"))
        contentAsString(result) must include("S2730000B")
        contentAsString(result) must include(nino)
        contentAsString(result) must include("Smith")
        contentAsString(result) must include("John")
        contentAsString(result) must include("01/01/2001 - 01/01/2005")
        contentAsString(result) must include("123.45")
      }

      "include member detail in contributions data rows" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.of(2001, 1, 1)), LocalDate.of(2005, 1, 1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "123.45"))))), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

        when(mockRepo.findByReference(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("userId", "reference")(fakeRequest)

        contentAsString(result) must include(Messages("gmp.bulk.csv.contributions.headers"))
        contentAsString(result) must include("S2730000B")
        contentAsString(result) must include(nino)
        contentAsString(result) must include("Smith")
        contentAsString(result) must include("John")
        contentAsString(result) must include("01/01/2001 - 01/01/2005")
        contentAsString(result) must include("123.45")
      }

      "return Forbidden if no user is found" in {
        when(mockRepo.findByReference(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(
          Future.successful(Some(ProcessedBulkCalculationRequest("1", "Ref", "email", "ref2",
            List.empty[ProcessReadyCalculationRequest], "USER-ID", LocalDateTime.now, true, 1, 0))))

        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("WRONG_USER_ID", "thing-ref")(fakeRequest)
        status(result) must be(FORBIDDEN)
      }


      "return 404 when not found" in {

        when(mockRepo.findByReference(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(None))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(Seq("Content-type" -> "application/json")), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("USER_ID", "thing-ref")(fakeRequest)
        status(result) must be(NOT_FOUND)
      }

      "cope with multiple period contributions data" in {

        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.of(2001, 1, 1)), LocalDate.of(2005, 1, 1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "123.45")))),
          CalculationPeriod(Some(LocalDate.of(2001, 1, 1)), LocalDate.of(2006, 1, 1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "200.12"))))
        ), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

        when(mockRepo.findByReference(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("userId", "reference")(fakeRequest)

        contentAsString(result) must include(Messages("gmp.bulk.csv.contributions.headers"))
        contentAsString(result) must include("S2730000B")
        contentAsString(result) must include(nino)
        contentAsString(result) must include("Smith")
        contentAsString(result) must include("John")
        contentAsString(result) must include("01/01/2001 - 01/01/2005")
        contentAsString(result) must include("01/01/2001 - 01/01/2006")
        contentAsString(result) must include("123.45")
        contentAsString(result) must include("200.12")
      }
    }


    "getResultsAsCsv" must {

      "return a file name" in {
        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.of(2001, 1, 1)), LocalDate.of(2005, 1, 1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "123.45"))))), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

        when(mockRepo.findByReference(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getCalculationsAsCsv("userId", "reference", CsvFilter.Successful)(fakeRequest)


        header("Content-Disposition", result).get must be("attachment; filename=\"reference1_total_GMP.csv\"")
        contentType(result).get must be("text/csv")

      }

      "return a contributions and earnings file name" in {
        val gmpBulkCalculationResponse = GmpBulkCalculationResponse(List(
          CalculationPeriod(Some(LocalDate.of(2001, 1, 1)), LocalDate.of(2005, 1, 1), "3.12", "1.23", 0, 0, None, None, None, None,
            Some(List(ContributionsAndEarnings(1994, "123.45"),
              ContributionsAndEarnings(1995, "123.45"))))), 0, None, None, None)

        val validCalculationRequest = ValidCalculationRequest("S2730000B", nino, "Smith", "John", Some("ref1"), Some(0), None, None, None, None)

        val calculationRequests = List(ProcessReadyCalculationRequest("1", 1, Some(validCalculationRequest), None, Some(gmpBulkCalculationResponse)))
        val bulkCalculationRequest = ProcessedBulkCalculationRequest("1", "abcd", "mail@mail.com", "reference1", calculationRequests, "userId", LocalDateTime.now(), true, 1, 0)

        when(mockRepo.findByReference(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Future.successful(Some(bulkCalculationRequest)))
        val fakeRequest = FakeRequest(method = "GET", uri = "", headers = FakeHeaders(), body = AnyContentAsEmpty)
        val result = TestBulkController.getContributionsAndEarningsAsCsv("userId", "reference")(fakeRequest)


        header("Content-Disposition", result).get must be("attachment; filename=\"reference1_contributions_and_earnings.csv\"")
        contentType(result).get must be("text/csv")

      }
    }
  }
}
