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

  private val microserviceBootstrapVersion  = "8.3.0"
  private val domainVersion                 = "5.2.0"
  private val playReactivemongoVersion      = "6.2.0"
  private val akkaContribVersion            = "2.3.4"
  private val playSchedulingVersion         = "4.1.0"
  private val mongoLockVersion              = "6.0.0-play-25"
  private val reactiveCircuitBreakerVersion = "3.2.0"
  private val taxyearVersion                = "0.4.0"
  private val scalatestVersion              = "3.0.1"
  private val scalatestPlusPlayVersion      = "2.0.0"
  private val pegdownVersion                = "1.6.0"
  private val reactiveMongoTest             = "4.1.0-play-25"
  private val mockitoCoreVersion            = "1.9.5"
  private val hmrcTestVersion               = "3.1.0"
  private val reactiveMongoVer = "0.16.0"
  
  val compile = Seq(
    "uk.gov.hmrc" %% "play-reactivemongo" % playReactivemongoVersion,
    ws,
    "org.reactivemongo" %% "reactivemongo-iteratees" % reactiveMongoVer,
    "uk.gov.hmrc" %% "microservice-bootstrap" % microserviceBootstrapVersion,
    "uk.gov.hmrc" %% "domain" % domainVersion,
    "com.typesafe.akka" %% "akka-contrib" % akkaContribVersion,
    "uk.gov.hmrc" %% "play-scheduling" % playSchedulingVersion,
    "uk.gov.hmrc" %% "mongo-lock" % mongoLockVersion,
    "uk.gov.hmrc" %% "reactive-circuit-breaker" % reactiveCircuitBreakerVersion,
    "uk.gov.hmrc" %% "tax-year" % taxyearVersion
  )

  trait TestDependencies {
    lazy val scope: String = "test"
    lazy val test : Seq[ModuleID] = Seq.empty
  }

  object Test {
    def apply() = new TestDependencies {
      override lazy val test = Seq(
        "uk.gov.hmrc" %% "hmrctest" % hmrcTestVersion % scope,
        "org.scalatest" %% "scalatest" % scalatestVersion % scope,
        "org.scalamock" %% "scalamock" % "3.6.0" % scope,
        "org.scalatestplus.play" %% "scalatestplus-play" % scalatestPlusPlayVersion % scope,
        "org.pegdown" % "pegdown" % pegdownVersion % scope,
        "uk.gov.hmrc" %% "reactivemongo-test" % reactiveMongoTest % scope,
        "org.reactivemongo" %% "reactivemongo-iteratees" % reactiveMongoVer,
        "com.typesafe.akka" % "akka-testkit_2.11" % akkaContribVersion % scope, // Check it
        "org.mockito" % "mockito-core" % mockitoCoreVersion % scope,
        "uk.gov.hmrc" %% "tax-year" % taxyearVersion % scope
      )
    }.test
  }

  object IntegrationTest {
    def apply() = new TestDependencies {

      override lazy val scope: String = "it"

      override lazy val test = Seq(
        "uk.gov.hmrc" %% "hmrctest" % hmrcTestVersion % scope,
        "org.scalatest" %% "scalatest" % scalatestVersion % scope,
        "org.pegdown" % "pegdown" % pegdownVersion % scope,
        "com.typesafe.play" %% "play-test" % PlayVersion.current % scope,
        "uk.gov.hmrc" %% "tax-year" % taxyearVersion % scope
      )
    }.test
  }

  def apply() = compile ++ Test() ++ IntegrationTest()
}

