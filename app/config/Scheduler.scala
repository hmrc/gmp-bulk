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
import akka.actor.{ActorSystem, Props}
import com.google.inject.Singleton
import com.typesafe.config.Config
import javax.inject.Inject
import net.ceedubs.ficus.Ficus._
import play.api.Mode.Mode
import play.api.Play.current
import play.api.inject.DefaultApplicationLifecycle
import play.api.libs.concurrent.Akka
import play.api.{Application, Configuration, Environment, Play}
import services.BulkCompletionService
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.play.scheduling.{ExclusiveScheduledJob, RunningOfScheduledJobs, ScheduledJob}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Scheduler@Inject()(override val applicationLifecycle: DefaultApplicationLifecycle,
                         actorSystem: ActorSystem,
                         env: Environment,
                        override val application: Application)(implicit val ec: ExecutionContext) extends RunningOfScheduledJobs with ActorUtils {

  lazy val scheduledJobs: Seq[ScheduledJob] = {
    Seq(new ExclusiveScheduledJob {
          lazy val processingSupervisor = actorSystem.actorOf(Props[ProcessingSupervisor], "processing-supervisor")

          override def name: String = "BulkProcesssingService"

          override def executeInMutex(implicit ec: ExecutionContext): Future[Result] = {
            if(env.mode != "Test") {
              processingSupervisor ! START
              Future.successful(Result("started"))
            }else {
              Future.successful(Result("not running scheduled jobs"))
            }
          }

          override def interval: FiniteDuration = 15 seconds

          override def initialDelay: FiniteDuration = 1 seconds
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

          override def interval: FiniteDuration = 1 minute

          override def initialDelay: FiniteDuration = 1 seconds
        })
  }

}