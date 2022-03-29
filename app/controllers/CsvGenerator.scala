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

package controllers

import models._
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import play.api.i18n.Messages
import scala.collection.mutable.ListBuffer
import com.github.ghik.silencer.silent

class CsvGenerator {

  val DATE_DEFAULT_FORMAT = "dd/MM/yyyy"

  sealed trait Cell {
    val text: String

    override def toString = text
  }

  case class TextCell(text: String) extends Cell

  case object BlankCell extends Cell {
    val text = ""
  }

  sealed trait Row {

    val cells: Traversable[Cell]

    @silent
    def toCsvString(cellCount: Int)(implicit csvFilter: CsvFilter) = {
      cells map {
        _.text
      } mkString ","
    }
  }

  case class ResponseRow(cells: Traversable[Cell], error: Option[Cell] = None, whatToDo: Option[Cell] = None) extends Row {

    override def toCsvString(cellCount: Int)(implicit csvFilter: CsvFilter): String = {

      val padCount = (csvFilter match {
        case CsvFilter.Successful => cellCount
        case _ => cellCount - 2
      }) - cells.size

      (cells.toList ::: List.fill(padCount)(BlankCell) ::: (csvFilter match {
        case CsvFilter.Successful => List()
        case _ => List(error.getOrElse(BlankCell), whatToDo.getOrElse(BlankCell))
      })) mkString ","
    }
  }

  case class HeaderRow(cells: Traversable[Cell]) extends Row

  class RowBuilder {

    protected val cells = new ListBuffer[Cell]()
    protected var errorCell: Cell = BlankCell
    protected var errorResolutionCell: Cell = BlankCell

    def addCell(text: Option[Any], default: String): RowBuilder = {
      addCell(text match {
        case Some(t) => t.toString
        case _ => default
      })
    }

    def addCell(text: String): RowBuilder = addCell(TextCell(text))

    def addCell(cell: Cell) = {
      cells += cell
      this
    }

    def addCell(cells: Traversable[String]): RowBuilder = {
      cells foreach addCell
      this
    }

    def addFilteredCell(f: PartialFunction[CsvFilter, String])(implicit filter: CsvFilter) = {
      if (f.isDefinedAt(filter))
        addCell(TextCell(f(filter)))
      this
    }

    def addFilteredCells(f: PartialFunction[CsvFilter, Traversable[String]])(implicit filter: CsvFilter) = {
      if (f.isDefinedAt(filter))
        f(filter) foreach addCell
      this
    }

    def addCells(row: Row) = {
      row.cells map addCell
      this
    }

    def addRows(rows: Traversable[Row]) = {
      rows map addCells
      this
    }

    def setErrorCell(cell: Cell) = this.errorCell = cell

    def setErrorResolutionCell(cell: Cell) = errorResolutionCell = cell

    def build: Row = ResponseRow(cells, Some(errorCell), Some(errorResolutionCell))
  }

  @silent
  class ResponseRowBuilder(request: ProcessReadyCalculationRequest)(implicit filter: CsvFilter, messages: Messages) extends RowBuilder {

    request.validCalculationRequest match {
      case Some(x) =>

        val hasValidationErrors = request.validationErrors match {
          case Some(_) => true
          case _ => false
        }

        if (filter == CsvFilter.All) {
          addCell(request.hasErrors match {
            case true => Messages("gmp.error")
            case false => Messages("gmp.success")
          })
        }

        addValidatedCell(x.scon, RequestFieldKey.SCON)
        addValidatedCell(x.nino, RequestFieldKey.NINO)
        addValidatedCell(x.firstForename, RequestFieldKey.FORENAME)
        addValidatedCell(x.surname, RequestFieldKey.SURNAME)
        addValidatedCell(x.memberReference.getOrElse(""), RequestFieldKey.MEMBER_REFERENCE)
        addValidatedCell(convertCalcType(x.calctype), RequestFieldKey.CALC_TYPE)
        addValidatedCell(convertDate(x.terminationDate), RequestFieldKey.DATE_OF_LEAVING)

        hasValidationErrors match {
          case true => addValidatedCell(convertDate(x.revaluationDate), RequestFieldKey.GMP_DATE)
          case false => addCell(determineGmpAtDate(request))
        }

        addValidatedCell(convertRevalRate(x.revaluationRate), RequestFieldKey.REVALUATION_RATE)

        addValidatedCell(x.dualCalc match {
          case Some(1) => Messages("gmp.generic.yes")
          case _ => if (hasValidationErrors) "" else Messages("gmp.generic.no")
        }, RequestFieldKey.OPPOSITE_GENDER)

        if (filter != CsvFilter.Failed) {

          request.validationErrors match {
            case None =>

              addCell(sumPeriod(request, {
                _.gmpTotal
              }).toString)

              addCell(sumPeriod(request, {
                _.post88GMPTotal
              }).toString)

              addCell(x.dualCalc match {
                case Some(d) if d == 1 => sumPeriod(request, {
                  _.dualCalcPost90TrueTotal match {
                    case Some(trueCalc) => trueCalc
                    case _ => ""
                  }
                }).toString
                case _ => ""
              })

              addCell(x.dualCalc match {
                case Some(d) if d == 1 => sumPeriod(request, {
                  _.dualCalcPost90OppositeTotal match {
                    case Some(oppositeCalc) => oppositeCalc
                    case _ => "0"
                  }
                }).toString
                case _ => ""
              })

            case _ => (1 to 4) foreach { _ => addCell(BlankCell) } // Add 4 cells to compensate for not adding the 4 totals cells
          }
        }

        request.calculationResponse match {
          case Some(response) =>
            response.calculationPeriods.zipWithIndex.map {
              case (period, index) =>
                val periodBuilder = new PeriodRowBuilder(period, index, x)
                addCells(periodBuilder.build)
            }
          case _ =>
        }

        request.getGlobalErrorMessageReason match {
          case Some(msg) => setErrorCell(TextCell(msg))
          case _ =>
        }

        request.getGlobalErrorMessageWhat match {
          case Some(msg) => setErrorResolutionCell(TextCell(msg))
          case _ =>
        }

      case _ if request.validationErrors.isDefined =>

        Seq(
          (RequestFieldKey.LINE_ERROR_TOO_FEW.toString, Messages("gmp.error.line.too_few")),
          (RequestFieldKey.LINE_ERROR_TOO_MANY.toString, Messages("gmp.error.line.too_many")),
          (RequestFieldKey.LINE_ERROR_EMPTY.toString, "" /* Intentionally empty */ )
        ) collectFirst {
          case x if request.validationErrors.get.isDefinedAt(x._1) => x
        } match {
          case Some(err) =>
            setErrorCell(TextCell(request.validationErrors.get(err._1)))
            setErrorResolutionCell(TextCell(err._2))
        }

      case _ =>
    }

    private def addValidatedCell(value: Any, validationColumn: Int): Cell = {

      val cell = TextCell(request.validationErrors match {
        case Some(v) => if (v.isDefinedAt(validationColumn.toString)) v(validationColumn.toString) else value.toString
        case _ => value.toString
      })

      addCell(cell)
      cell
    }

    private def sumPeriod(request: ProcessReadyCalculationRequest, selector: (CalculationPeriod) => String) = {
      request.calculationResponse match {
        case Some(response) => response.calculationPeriods.foldLeft(BigDecimal(0)) { (sum, period) => sum + BigDecimal(selector(period)) }
        case _ => 0
      }
    }

    private def convertCalcType(calcType: Option[Int]): String = {
      calcType match {
        case Some(0) => Messages("gmp.calc_type.leaving")
        case Some(1) => Messages("gmp.calc_type.specific_date")
        case Some(2) => Messages("gmp.calc_type.payable_age")
        case Some(3) => Messages("gmp.calc_type.survivor")
        case _ => Messages("gmp.calc_type.spa")
      }
    }

    private def convertDate(date: Option[String]): String = {

      val inputDateFormatter = DateTimeFormat.forPattern("yyyy-MM-dd")

      date match {
        case Some(d) => LocalDate.parse(d, inputDateFormatter).toString(DATE_DEFAULT_FORMAT)
        case _ => ""
      }
    }

    private def determineGmpAtDate(request: ProcessReadyCalculationRequest): String = {

      val inputDateFormatter = DateTimeFormat.forPattern("yyyy-MM-dd")

      request.validCalculationRequest.map {

        calculationRequest =>

          request.calculationResponse.map {
            calculationResponse =>

              calculationRequest.calctype match {

                case Some(2) => calculationResponse.payableAgeDate.map {
                  dod => dod.toString(DATE_DEFAULT_FORMAT)
                }.getOrElse("")

                case Some(3) =>
                  calculationRequest.revaluationDate.map {
                    d => LocalDate.parse(d, inputDateFormatter).toString(DATE_DEFAULT_FORMAT)
                  }.getOrElse(calculationResponse.dateOfDeath.map {
                    dod => dod.toString(DATE_DEFAULT_FORMAT)
                  }.getOrElse(""))

                case Some(4) => calculationResponse.spaDate.map {
                  dod => dod.toString(DATE_DEFAULT_FORMAT)
                }.getOrElse("")

                case _ if calculationRequest.revaluationDate.isEmpty => calculationResponse.calculationPeriods.headOption.map {
                  period => period.endDate.toString(DATE_DEFAULT_FORMAT)
                }.getOrElse("")

                case _ => convertDate(calculationRequest.revaluationDate)
              }

          }.getOrElse("")
      }.getOrElse("")
    }
  }

  class PeriodRowBuilder(calculationPeriod: CalculationPeriod, index: Int, request: ValidCalculationRequest)(implicit filter: CsvFilter, messages: Messages) extends RowBuilder {

    addCell(calculationPeriod.startDate match {
      case Some(date) => date.toString(DATE_DEFAULT_FORMAT)
      case _ => ""
    })

    addCell(calculationPeriod.endDate.toString(DATE_DEFAULT_FORMAT))
    addCell(calculationPeriod.gmpTotal)
    addCell(calculationPeriod.post88GMPTotal)

    request.dualCalc match {
      case Some(x) if x == 1 =>
        addCell(calculationPeriod.dualCalcPost90TrueTotal, "")
        addCell(calculationPeriod.dualCalcPost90OppositeTotal, "")
      case _ =>
        (1 to 2) foreach { _ => addCell(BlankCell) }
    }

    addCell(request.calctype match {
      case Some(x) if x > 0 => calculatePeriodRevalRate(calculationPeriod, index, request)
      case _ => ""
    })

    if (filter != CsvFilter.Successful) {
      addCell(calculationPeriod.getPeriodErrorMessageReason.getOrElse(""))
      addCell(calculationPeriod.getPeriodErrorMessageWhat.getOrElse(""))
    }

    private def calculatePeriodRevalRate(period: CalculationPeriod, index: Int, request: ValidCalculationRequest): String = {
      if (period.revaluationRate == 0)
        ""
      else {
        request.memberIsInScheme match {
          case Some(true) if Set(2, 3, 4) contains request.calctype.get => ""
          case Some(true) if request.calctype.get == 1 && index == 0 => ""
          case Some(false) =>
            if (request.calctype.get == 1 && index == 0 && (!period.endDate.isBefore(LocalDate.now) || period.revalued.getOrElse(1) == 1))
              ""
            else
              convertRevalRate(Some(period.revaluationRate))
          case _ => convertRevalRate(Some(period.revaluationRate))
        }
      }
    }

  }

  class HeaderRowBuilder(periodCount: Int)(implicit filter: CsvFilter, messages: Messages) extends RowBuilder {

    val periodCell = (msg: String, periodIndex: Int) => new Cell {
      val text = s"${Messages("gmp.period")} $periodIndex ${Messages(msg)}"
    }

    addFilteredCell({
        case CsvFilter.All => Messages("gmp.status") // Add status column only for all
      })
      .addCell(Messages("gmp.bulk.csv.headers") split ",") // headers for all
      .addFilteredCells({
        case CsvFilter.All | CsvFilter.Successful => Messages("gmp.bulk.totals.headers") split "," // totals for all
      })
      .addRows(generatePeriodHeaders(periodCount))
      .addFilteredCells({
        case CsvFilter.All | CsvFilter.Failed => Messages("gmp.bulk.csv.globalerror.headers") split "," // global errors for all and failed
      })

    private def generatePeriodHeaders(periodCount: Int) = {
      (1 to periodCount).map {
        implicit index =>

          val builder = new RowBuilder

          builder.addCell(periodCell("gmp.period.start_date", index))
            .addCell(periodCell("gmp.period.end_date", index))
            .addCell(periodCell("gmp.period.total", index))
            .addCell(periodCell("gmp.period.post_88", index))
            .addCell(periodCell("gmp.period.post_90_true", index))
            .addCell(periodCell("gmp.period.post_90_opp", index))
            .addCell(periodCell("gmp.period.reval_rate", index))
            .addFilteredCell({
              case CsvFilter.All | CsvFilter.Failed => periodCell("gmp.period.error", index).text
            })
            .addFilteredCell({
              case CsvFilter.All | CsvFilter.Failed => periodCell("gmp.period.what", index).text
            })

          builder.build
      }
    }

    override def build = HeaderRow(cells)

  }

  class CsvBuilder(cellCount: Int)(implicit csvFilter: CsvFilter) {

    val rows = new ListBuffer[Row]()

    def addRow(row: Row): CsvBuilder = {
      rows += row
      this
    }

    def addRow(text: String): CsvBuilder = addRow(ResponseRow(List(TextCell(text))))

    def build: String = rows map {
      _.toCsvString(cellCount)
    } mkString "\n"

  }

  def generateCsv(result: ProcessedBulkCalculationRequest, csvFilter: Option[CsvFilter])(implicit messages: Messages): String = {

    implicit val filter = csvFilter.get

    val maxPeriods: Int = result.calculationRequests match {
      case calcRequests @ h :: tail => calcRequests.map {
        _.calculationResponse match {
          case Some(x: GmpBulkCalculationResponse) if x.calculationPeriods.nonEmpty => x.calculationPeriods.size
          case _ => 0
        }
      }.max
      case Nil => 0
    }

    val headerRow = new HeaderRowBuilder(maxPeriods).build
    val csvBuilder = new CsvBuilder(headerRow.cells.size)

    csvBuilder.addRow(Messages("gmp.bulk.csv.guidance"))
    csvBuilder.addRow(headerRow)

    result.calculationRequests foreach { request =>
      val requestBuilder = new ResponseRowBuilder(request)
      csvBuilder.addRow(requestBuilder.build)
    }

    csvBuilder.build
  }

  def generateContributionsCsv(request: ProcessedBulkCalculationRequest)(implicit messages: Messages): String = {

    Messages("gmp.bulk.csv.contributions.headers") + "\n" + request.calculationRequests.map {
      case calcRequest => {
        calcRequest.validCalculationRequest match {
          case Some(validCalcRequest) => {

            // Write out each period
            val periodRows = calcRequest.calculationResponse match {
              case Some(response) => response.calculationPeriods.map {
                generateContributionsPeriodRowData
              }
              case _ => List.empty[String]
            }

            val firstRow = List(
              validCalcRequest.scon,
              validCalcRequest.nino,
              validCalcRequest.firstForename,
              validCalcRequest.surname,
              periodRows.size match {
                case 0 => ""
                case _ => periodRows.head
              }).mkString(",")

            firstRow + generateLineSeparator(calcRequest) + (periodRows.size match {
              case 0 => ""
              case _ => periodRows.tail.map {
                "," * 4 + _
              }.mkString("\n")
            })

          }
          case _ => ""
        }
      }
    }.mkString("\n")
  }

  private def generateContributionsPeriodRowData(period: CalculationPeriod): String = {

    List(
      (period.startDate match {
        case Some(d) => d.toString("dd/MM/yyyy")
        case _ => ""
      }) + " - " + period.endDate.toString("dd/MM/yyyy"),
      period.contsAndEarnings match {
        case Some(c) =>
          val map = c.foldLeft(Map[Int, String]()) {
            (m, earnings) => {
              if(m.contains(earnings.taxYear))
                m + (earnings.taxYear -> (m(earnings.taxYear) + " & " + earnings.contEarnings))
              else
                m + (earnings.taxYear -> earnings.contEarnings)
            }
          }

          (1978 to 1998).map {
            map.getOrElse(_, "")
          }.mkString(",")
        case _ => "," * 20
      } 
    ).mkString(",")

  }

  private def convertRevalRate(revalRate: Option[Int]): String = {
    revalRate match {
      case Some(0) => RevaluationRate.HMRC
      case Some(1) => RevaluationRate.S148
      case Some(2) => RevaluationRate.FIXED
      case Some(3) => RevaluationRate.LIMITED
      case _ => ""
    }
  }

  private def generateLineSeparator(calcRequest: ProcessReadyCalculationRequest): String = {
    calcRequest.calculationResponse match {
      case Some(response) if response.calculationPeriods.size > 1 => "\n"
      case _ => ""
    }
  }

}
