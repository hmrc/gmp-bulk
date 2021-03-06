import play.sbt.PlayImport._
import sbt._

object AppDependencies {

  val compile: Seq[ModuleID] = Seq(
    ws,
    "uk.gov.hmrc"         %% "simple-reactivemongo"       % "8.0.0-play-26",
    "org.reactivemongo"   %% "reactivemongo-iteratees"    % "1.0.4",
    "uk.gov.hmrc"         %% "bootstrap-backend-play-26"  % "5.3.0",
    "uk.gov.hmrc"         %% "domain"                     % "5.11.0-play-26",
    "com.typesafe.akka"   %% "akka-contrib"               % "2.5.23",
    "uk.gov.hmrc"         %% "play-scheduling"            % "7.4.0-play-26",
    "uk.gov.hmrc"         %% "mongo-lock"                 % "7.0.0-play-26",
    "uk.gov.hmrc"         %% "reactive-circuit-breaker"   % "3.5.0",
    "uk.gov.hmrc"         %% "tax-year"                   % "1.3.0",
    "com.typesafe.play"   %% "play-json-joda"             % "2.9.2",
    "com.github.ghik"     %  "silencer-lib"               % "1.7.1" % Provided cross CrossVersion.full,
    compilerPlugin("com.github.ghik" % "silencer-plugin"  % "1.7.1" cross CrossVersion.full)
  )

  trait TestDependencies {
    lazy val scope: String = "test,it"
    lazy val test: Seq[ModuleID] = ???
  }
  object Tests {
    def apply(): Seq[ModuleID] = new TestDependencies {
      override lazy val test: Seq[ModuleID] = Seq(
        "uk.gov.hmrc"             %% "hmrctest"                 % "3.9.0-play-26",
        "org.scalatest"           %% "scalatest"                % "3.0.9",
        "org.scalamock"           %% "scalamock"                % "4.4.0",
        "org.scalatestplus.play"  %% "scalatestplus-play"       % "3.1.3",
        "org.pegdown"             %  "pegdown"                  % "1.6.0",
        "uk.gov.hmrc"             %% "reactivemongo-test"       % "5.0.0-play-26",
        "org.reactivemongo"       %% "reactivemongo-iteratees"  % "1.0.4",
        "com.typesafe.akka"       %% "akka-testkit"             % "2.5.23",
        "org.mockito"             %  "mockito-all"              % "1.10.19",
        "uk.gov.hmrc"             %% "tax-year"                 % "1.3.0",
        "com.github.tomakehurst"  %  "wiremock-jre8"            % "2.26.3",
        "uk.gov.hmrc"             %% "bootstrap-play-26"        % "4.0.0" % Test classifier "tests")
    }.test
  }

  val all: Seq[ModuleID] = compile ++ Tests()

}
