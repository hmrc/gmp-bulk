import play.sbt.PlayImport._
import sbt._

object AppDependencies {

  val compile: Seq[ModuleID] = Seq(
    ws,
    "uk.gov.hmrc" %% "simple-reactivemongo" % "7.20.0-play-25",
    "org.reactivemongo" %% "reactivemongo-iteratees" % "0.16.4",
    "uk.gov.hmrc" %% "microservice-bootstrap" % "10.6.0",
    "uk.gov.hmrc" %% "domain" % "5.6.0-play-25",
    "com.typesafe.akka" %% "akka-contrib" % "2.5.23",
    "uk.gov.hmrc" %% "play-scheduling" % "6.0.0",
    "uk.gov.hmrc" %% "mongo-lock" % "6.15.0-play-25",
    "uk.gov.hmrc" %% "reactive-circuit-breaker" % "3.3.0",
    "uk.gov.hmrc" %% "tax-year" % "0.6.0",
    "uk.gov.hmrc" %% "auth-client" % "2.26.0-play-25"
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc" %% "hmrctest" % "3.9.0-play-25",
    "org.scalatest" %% "scalatest" % "3.0.8",
    "org.scalamock" %% "scalamock" % "3.6.0",
    "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.1",
    "org.pegdown" % "pegdown" % "1.6.0",
    "uk.gov.hmrc" %% "reactivemongo-test" % "4.15.0-play-25",
    "org.reactivemongo" %% "reactivemongo-iteratees" % "0.16.1",
    "com.typesafe.akka" %% "akka-testkit" % "2.5.23",
    "org.mockito" % "mockito-all" % "1.9.5",
    "uk.gov.hmrc" %% "tax-year" % "0.6.0"
  ).map(_ % "test")

  val all: Seq[ModuleID] = compile ++ test

}
