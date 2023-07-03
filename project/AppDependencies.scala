import play.sbt.PlayImport._
import sbt._
import play.core.PlayVersion

object AppDependencies {

  val playVersion = "play-28"
  val akkaVersion = "2.6.19"
  val mongoVersion = "0.74.0"

  val compile: Seq[ModuleID] = Seq(
    ws,
    "uk.gov.hmrc.mongo"                          %% "hmrc-mongo-play-28"         % mongoVersion,
    "uk.gov.hmrc"                                %% "bootstrap-backend-play-28"  % "7.8.0",
    "uk.gov.hmrc"                                %% "domain"                     % s"8.1.0-$playVersion",
    "uk.gov.hmrc"                                %% "reactive-circuit-breaker"   % "4.1.0",
    "uk.gov.hmrc"                                %% "tax-year"                   % "3.0.0",
    "com.typesafe.play"                          %% "play-json-joda"             % "2.9.3",
    "com.github.ghik"                            %  "silencer-lib"               % "1.7.11" % Provided cross CrossVersion.full,
    compilerPlugin("com.github.ghik" %  "silencer-plugin"            % "1.7.11" cross CrossVersion.full)
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"             %% s"bootstrap-test-$playVersion"   % "7.8.0"             % "test",
    "com.typesafe.akka"       %% "akka-testkit"                   % akkaVersion         % "test",
    "com.github.tomakehurst"  %  "wiremock-jre8"                  % "2.31.0"            % "test",
    "uk.gov.hmrc.mongo"       %% s"hmrc-mongo-test-$playVersion"  % mongoVersion        % "test",
    "org.mockito"             %  "mockito-all"                    % "1.10.19"           % "test",
    "org.scalatestplus.play"  %% "scalatestplus-play"             % "5.1.0"             % "test",
    "org.scalatestplus"       %% "scalatestplus-mockito"          % "1.0.0-M2"          % "test",
    "com.typesafe.play"       %% "play-test"                      % PlayVersion.current % "test",
    "com.vladsch.flexmark"    %  "flexmark-all"                   % "0.35.10"           % "test"
  )

  val all: Seq[ModuleID] = compile ++ test

}
