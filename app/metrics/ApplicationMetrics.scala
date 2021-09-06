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

package metrics

import java.util.concurrent.TimeUnit

import com.google.inject.Inject
import com.kenshoo.play.metrics.Metrics
import play.api.Logging
import scala.concurrent.duration
import scala.util.Try

class ApplicationMetrics @Inject()(metrics: Metrics) extends Logging {
  lazy val registry = metrics.defaultRegistry

  private val timer = (name: String) => Try{registry.timer(name)}
  private val counter = (name: String) => Try{registry.counter(name)}

  logger.info("[Metrics][constructor] Preloading metrics keys")

  Seq(
    ("processRequest-timer", timer),
    ("des-connector-requests-successful", counter),
    ("des-connector-requests-failed", counter),
    ("des-connector-httpstatus-200", counter),
    ("des-connector-httpstatus-400", counter),
    ("des-connector-httpstatus-422", counter),
    ("des-connector-httpstatus-500", counter),
    ("des-connector-httpstatus-504", counter),
    ("des-connector-timer", timer),
    ("mongo-insertResponseByReference-timer", timer),
    ("mongo-findRequestsToProcess-timer", timer),
    ("mongo-findCountRemaining-timer", timer),
    ("mongo-findByUserId-timer", timer),
    ("mongo-insertBulkDocument-timer", timer),
    ("mongo-findAndComplete-timer", timer),
    ("mongo-findSummaryByReference-timer", timer),
    ("mongo-findByReference-timer", timer),
    ("mongo-findAndCompleteChildren-timer", timer),
    ("mongo-findAndCompleteParent-timer", timer),
    ("mongo-findAndCompleteAllChildren-timer", timer),
    ("mci-connection-timer", timer),
    ("mci-lock-result-count", counter),
    ("mci-error-result-count", counter)
  ).foreach { t => t._2(t._1) }

  def metricTimer(diff: Long, unit: duration.TimeUnit, name : String) : Unit = {
    Try{registry.timer(name).update(diff, unit)}
      .failed.foreach(ex => logger.warn(s"$name failed : Metrics may be disabled" ))
  }

  def metricCounter(name : String) : Unit = {
    Try{registry.counter(name).inc()}
      .failed.foreach(ex => logger.warn(s"$name failed : Metrics may be disabled" ))
  }

  def processRequest(diff: Long, unit: duration.TimeUnit): Unit = metricTimer(diff, unit, "processRequest-timer")

  def registerSuccessfulRequest() = metricCounter("des-connector-requests-successful")

  def registerFailedRequest() = metricCounter("des-connector-requests-failed")

  def registerStatusCode(code: String) = metricCounter(s"des-connector-httpstatus-$code")

  def desConnectionTime(diff: Long, timeUnit: TimeUnit) = metricTimer(diff, timeUnit, "des-connector-timer")

  def insertResponseByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-insertResponseByReference-timer")

  def findRequestsToProcessTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-findRequestsToProcess-timer")

  def findCountRemainingTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-findCountRemaining-timer")

  def findByUserIdTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-findByUserId-timer")

  def insertBulkDocumentTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-insertBulkDocument-timer")

  def findAndCompleteTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-findAndComplete-timer")

  def findSummaryByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-findSummaryByReference-timer")

  def findByReferenceTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-findByReference-timer")

  def findAndCompleteChildrenTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-findAndCompleteChildren-timer")

  def findAndCompleteParentTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-findAndCompleteParent-timer")

  def findAndCompleteAllChildrenTimer(diff: Long, unit: duration.TimeUnit): Unit =
    metricTimer(diff, unit, "mongo-findAndCompleteAllChildren-timer")

  def mciConnectionTimer(diff: Long, unit: TimeUnit) =
    metricTimer(diff, unit, "mci-connection-timer")

  def mciLockResult() =
    metricCounter("mci-lock-result-count")

  def mciErrorResult() =
    metricCounter("mci-error-result-count")

}
