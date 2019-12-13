/*
 * Copyright 2019 HM Revenue & Customs
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

package config

import actors.{ActorUtils, ProcessingSupervisor}
import akka.actor.Props
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import play.api.Mode.Mode
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.{Application, Configuration, Play}
import services.BulkCompletionService
import uk.gov.hmrc.play.config.{AppName, ControllerConfig, RunMode}
import uk.gov.hmrc.play.scheduling.{ExclusiveScheduledJob, RunningOfScheduledJobs, ScheduledJob}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class Scheduler extends RunMode with RunningOfScheduledJobs with ActorUtils {

  override val scheduledJobs: Seq[ScheduledJob] = {
    Seq(new ExclusiveScheduledJob {
          lazy val processingSupervisor = Akka.system.actorOf(Props[ProcessingSupervisor], "processing-supervisor")

          override def name: String = "BulkProcesssingService"

          override def executeInMutex(implicit ec: ExecutionContext): Future[Result] = {
            if(env != "Test") {
              processingSupervisor ! START
              Future.successful(Result("started"))
            }else {
              Future.successful(Result("not running scheduled jobs"))
            }
          }

          override def interval: FiniteDuration = ApplicationConfig.bulkProcessingInterval

          override def initialDelay: FiniteDuration = 0 seconds
        },
        new ExclusiveScheduledJob {

          override def executeInMutex(implicit ec: ExecutionContext): Future[Result] = {
            if(env != "Test") {
              val bulkCompletionService = Play.current.injector.instanceOf[BulkCompletionService]
              bulkCompletionService.checkForComplete()
              Future.successful(Result("started"))
            }else {
              Future.successful(Result("not running scheduled jobs"))
            }
          }

          override def name: String = "BulkCompletionService"

          override def interval: FiniteDuration = ApplicationConfig.bulkCompleteInterval

          override def initialDelay: FiniteDuration = 0 seconds
        })
  }

  override protected def mode: Mode = Play.current.mode

  override protected def runModeConfiguration: Configuration = Play.current.configuration
}
