import play.sbt.PlayImport._
import sbt._
import play.core.PlayVersion

object AppDependencies {

  val playVersion = "play-28"
  val akkaVersion = "2.6.14"

  val compile: Seq[ModuleID] = Seq(
    ws,
    "uk.gov.hmrc"         %% "simple-reactivemongo"       % s"8.0.0-$playVersion",
    "org.reactivemongo"   %% "reactivemongo-iteratees"    % "1.0.4",
    "uk.gov.hmrc"         %% "bootstrap-backend-play-28"  % "5.12.0",
    "uk.gov.hmrc"         %% "domain"                     % s"6.2.0-$playVersion",
    "uk.gov.hmrc"         %% "mongo-lock"                 % s"7.0.0-$playVersion",
    "uk.gov.hmrc"         %% "reactive-circuit-breaker"   % "3.5.0",
    "uk.gov.hmrc"         %% "tax-year"                   % "1.6.0",
    "com.typesafe.play"   %% "play-json-joda"             % "2.9.2",
    "com.github.ghik"     %  "silencer-lib"               % "1.7.5" % Provided cross CrossVersion.full,
    compilerPlugin("com.github.ghik" % "silencer-plugin"  % "1.7.5" cross CrossVersion.full)
  )

 val test: Seq[ModuleID] = Seq(
      "uk.gov.hmrc"             %% s"bootstrap-test-$playVersion"   % "5.3.0" % "test",
      "com.typesafe.akka"       %% "akka-testkit"                   % akkaVersion % "test",
      "com.github.tomakehurst"  %  "wiremock-jre8"                  % "2.27.1"         % "test",
      "uk.gov.hmrc"             %% "reactivemongo-test"             % s"5.0.0-$playVersion"        % "test",
      "org.mockito"             %  "mockito-all"                    % "1.10.19"               % "test",
      "org.scalatestplus.play"  %% "scalatestplus-play"             % "5.1.0"                 % "test",
      "org.scalatestplus"       %% "scalatestplus-mockito"          % "1.0.0-M2"              % "test",
      "com.typesafe.play"       %% "play-test"                      % PlayVersion.current      % "test",
      "com.vladsch.flexmark"    % "flexmark-all"              % "0.35.10"               % "test"
      )

  val all: Seq[ModuleID] = compile ++ test

}
