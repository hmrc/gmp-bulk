/*
 * Copyright 2016 HM Revenue & Customs
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

import sbt._

object MicroServiceBuild extends Build with MicroService {
  val appName = "gmp-bulk"
  override lazy val appDependencies: Seq[ModuleID] = AppDependencies()
}

private object AppDependencies {
  import play.sbt.PlayImport._
  import play.core.PlayVersion

  val compile = Seq(
    "uk.gov.hmrc"       %% "play-reactivemongo"       % "6.2.0",
    ws,
    "org.reactivemongo" %% "reactivemongo-iteratees"  % "0.16.1",
    "uk.gov.hmrc"       %% "microservice-bootstrap"   % "10.6.0",
    "uk.gov.hmrc"       %% "domain"                   % "5.3.0",
    "com.typesafe.akka" %% "akka-contrib"             % "2.4.10",
    "uk.gov.hmrc"       %% "play-scheduling"          % "5.4.0",
    "uk.gov.hmrc"       %% "mongo-lock"               % "6.8.0-play-25",
    "uk.gov.hmrc"       %% "reactive-circuit-breaker" % "3.3.0",
    "uk.gov.hmrc"       %% "tax-year"                 % "0.5.0",
    "uk.gov.hmrc"       %% "auth-client"              %  "2.22.0-play-25"
  )

  trait TestDependencies {
    lazy val scope: String = "test"
    lazy val test : Seq[ModuleID] = Seq.empty
  }

  object Test {
    def apply() = new TestDependencies {
      override lazy val test = Seq(
        "uk.gov.hmrc"             %% "hmrctest"                 % "3.4.0-play-25" % scope,
        "org.scalatest"           %% "scalatest"                % "3.0.2"         % scope,
        "org.scalamock"           %% "scalamock"                % "3.6.0"         % scope,
        "org.scalatestplus.play"  %% "scalatestplus-play"       % "2.0.1"         % scope,
        "org.pegdown"             %  "pegdown"                  % "1.6.0"         % scope,
        "uk.gov.hmrc"             %% "reactivemongo-test"       % "4.7.0-play-25" % scope,
        "org.reactivemongo"       %% "reactivemongo-iteratees"  % "0.16.1",
        "com.typesafe.akka"       %% "akka-testkit"             % "2.4.10"        % scope,
        "org.mockito"             %  "mockito-core"             % "1.9.5"         % scope,
        "uk.gov.hmrc"             %% "tax-year"                 % "0.5.0"         % scope
      )
    }.test
  }

  object IntegrationTest {
    def apply() = new TestDependencies {

      override lazy val scope: String = "it"

      override lazy val test = Seq(
        "uk.gov.hmrc"         %% "hmrctest"   % "3.4.0-play-25"     % scope,
        "org.scalatest"       %% "scalatest"  % "3.0.2"             % scope,
        "org.pegdown"         %  "pegdown"    % "1.6.0"             % scope,
        "com.typesafe.play"   %% "play-test"  % PlayVersion.current % scope,
        "uk.gov.hmrc"         %% "tax-year"   % "0.5.0"             % scope
      )
    }.test
  }

  def apply() = compile ++ Test() ++ IntegrationTest()
}

