import play.sbt.PlayImport._
import sbt._

object AppDependencies {

  val compile: Seq[ModuleID] = Seq(
    ws,
    "uk.gov.hmrc" %% "play-reactivemongo" % "6.2.0",
    "org.reactivemongo" %% "reactivemongo-iteratees" % "0.16.1",
    "uk.gov.hmrc" %% "microservice-bootstrap" % "10.6.0",
    "uk.gov.hmrc" %% "domain" % "5.3.0",
    "com.typesafe.akka" %% "akka-contrib" % "2.4.10",
    "uk.gov.hmrc" %% "play-scheduling" % "5.4.0",
    "uk.gov.hmrc" %% "mongo-lock" % "6.8.0-play-25",
    "uk.gov.hmrc" %% "reactive-circuit-breaker" % "3.3.0",
    "uk.gov.hmrc" %% "tax-year" % "0.5.0",
    "uk.gov.hmrc" %% "auth-client" % "2.22.0-play-25"
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc" %% "hmrctest" % "3.4.0-play-25",
    "org.scalatest" %% "scalatest" % "3.0.2",
    "org.scalamock" %% "scalamock" % "3.6.0",
    "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.1",
    "org.pegdown" % "pegdown" % "1.6.0",
    "uk.gov.hmrc" %% "reactivemongo-test" % "4.7.0-play-25",
    "org.reactivemongo" %% "reactivemongo-iteratees" % "0.16.1",
    "com.typesafe.akka" %% "akka-testkit" % "2.4.10",
    "org.mockito" % "mockito-core" % "1.9.5",
    "uk.gov.hmrc" %% "tax-year" % "0.5.0"
  ).map(_ % "test")

  val all: Seq[ModuleID] = compile ++ test

}
