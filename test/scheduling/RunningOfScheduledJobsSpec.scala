/*
 * Copyright 2026 HM Revenue & Customs
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

package scheduling

import org.apache.pekko.actor.{ActorSystem, CoordinatedShutdown, Scheduler}
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.http.*
import play.api.inject.*
import play.api.mvc.RequestHeader
import play.api.mvc.request.{DefaultRequestFactory, RequestFactory}
import play.api.{Application, Configuration, Environment, Mode}

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}

class RunningOfScheduledJobsSpec
  extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  implicit val system: ActorSystem = ActorSystem("test-system")
  implicit val ec: ExecutionContext = system.dispatcher

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 10.seconds, interval = 50.millis)

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }


  class TestApplicationLifecycle extends ApplicationLifecycle {
    private val hooks = mutable.ListBuffer[() => Future[?]]()

    override def addStopHook(hook: () => Future[?]): Unit = hooks += hook

    override def stop(): Future[?] = Future.successful(())

    def runStopHooks(): Future[Unit] =
      hooks.foldLeft(Future.successful(()))((acc, hook) => acc.flatMap(_ => hook().map(_ => ())))
  }

  class TestApplication extends Application {
    override def actorSystem: ActorSystem = system

    override implicit def materializer: Materializer = Materializer(system)

    override def coordinatedShutdown: CoordinatedShutdown = CoordinatedShutdown(system)

    override def environment: Environment = Environment.simple()

    override def configuration: Configuration = Configuration.empty

    override def injector: Injector = SimpleInjector(NewInstanceInjector)

    override def requestFactory: RequestFactory = DefaultRequestFactory(HttpConfiguration())

    override def requestHandler: HttpRequestHandler = new HttpRequestHandler {
      override def handlerForRequest(request: RequestHeader) =
        throw new NotImplementedError("requestHandler is not used in tests")
    }

    override def errorHandler: HttpErrorHandler = DefaultHttpErrorHandler

    override def stop(): Future[?] = Future.successful(())

    override def mode: Mode = Mode.Test

    override def classloader: ClassLoader = getClass.getClassLoader

    override def isTest: Boolean = true

    override def path: java.io.File = new java.io.File(".")
  }

  class TestRunner(
                    jobs: Seq[ScheduledJob],
                    val applicationLifecycle: ApplicationLifecycle,
                    implicit val ec: ExecutionContext
                  ) extends RunningOfScheduledJobs {
    override val application: Application = new TestApplication
    override lazy val scheduler: Scheduler = system.scheduler
    override lazy val scheduledJobs: Seq[ScheduledJob] = jobs
  }

  def makeRunner(jobs: Seq[ScheduledJob], lifecycle: TestApplicationLifecycle): TestRunner =
    new TestRunner(jobs, lifecycle, system.dispatcher)

  class TestJob extends ExclusiveScheduledJob {
    override val name: String = "test-job"
    override val initialDelay: FiniteDuration = 1.hour
    override val interval: FiniteDuration = 1.hour

    val innerPromise: Promise[Result] = Promise()

    override def executeInMutex(implicit ec: ExecutionContext): Future[Result] =
      innerPromise.future
  }

  "stop-hook runningFuture handling" should {

    "complete immediately when runningFuture is None (job is idle)" in {
      val lifecycle = new TestApplicationLifecycle
      makeRunner(Seq(new TestJob), lifecycle)

      lifecycle.runStopHooks().futureValue shouldBe()
    }

    "wait for the in-flight Future to complete when runningFuture is Some" in {
      val lifecycle = new TestApplicationLifecycle
      val job = new TestJob
      makeRunner(Seq(job), lifecycle)

      job.execute
      val result = lifecycle.runStopHooks()

      result.isCompleted shouldBe false

      job.innerPromise.success(job.Result("done"))

      result.futureValue shouldBe()
    }

    "complete successfully even if the in-flight Future fails" in {
      val lifecycle = new TestApplicationLifecycle
      val job = new TestJob
      makeRunner(Seq(job), lifecycle)

      job.execute
      val result = lifecycle.runStopHooks()

      job.innerPromise.failure(new RuntimeException("job blew up"))

      result.futureValue shouldBe()
    }
  }

  "ExclusiveScheduledJob" should {

    "set runningFuture to Some while executing and None once finished" in {
      val job = new TestJob

      job.runningFuture shouldBe None

      val execution = job.execute

      job.runningFuture shouldBe defined

      job.innerPromise.success(job.Result("done"))
      execution.futureValue

      job.runningFuture shouldBe None
    }

    "skip and leave currentExecution unchanged when already running" in {
      val job = new TestJob

      job.execute

      val skipped = job.execute
      skipped.futureValue.message shouldBe "Skipping execution: job running"

      job.runningFuture shouldBe defined

      job.innerPromise.success(job.Result("done"))
    }
  }
}