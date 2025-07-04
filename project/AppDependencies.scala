import play.sbt.PlayImport._
import sbt._

object AppDependencies {

  private val playSuffix = "-play-30"
  private val bootstrapVersion = "9.13.0"
  private val hmrcMongoVersion = "2.6.0"

  val compile: Seq[ModuleID] = Seq(
    ws,
    "uk.gov.hmrc.mongo"                          %% s"hmrc-mongo$playSuffix"        % hmrcMongoVersion,
    "uk.gov.hmrc"                                %% s"bootstrap-backend$playSuffix" % bootstrapVersion,
    "uk.gov.hmrc"                                %% s"domain$playSuffix"            % "10.0.0",
    "uk.gov.hmrc"                                %% "reactive-circuit-breaker"      % "5.0.0",
    "uk.gov.hmrc"                                %% "tax-year"                      % "5.0.0",
    "com.github.ghik"                            %  "silencer-lib"                  % "1.7.19" % Provided cross CrossVersion.full,
    compilerPlugin("com.github.ghik" %  "silencer-plugin"               % "1.7.19" cross CrossVersion.full)
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% s"bootstrap-test$playSuffix"  % bootstrapVersion,
    "uk.gov.hmrc.mongo"      %% s"hmrc-mongo-test$playSuffix" % hmrcMongoVersion,
    "org.scalatestplus.play" %% "scalatestplus-play"          % "7.0.1",
    "org.mockito"            %% "mockito-scala-scalatest"     % "1.17.37",
    "org.apache.pekko"       %% "pekko-testkit"               % "1.0.3"
  ).map(_ % "test")

  val jacksonVersion         = "2.17.2"

  val jacksonOverrides = Seq(
    "com.fasterxml.jackson.core"       %  "jackson-databind",
    "com.fasterxml.jackson.core"       %  "jackson-core",
    "com.fasterxml.jackson.core"       %  "jackson-annotations",
    "com.fasterxml.jackson.datatype"   %  "jackson-datatype-jdk8",
    "com.fasterxml.jackson.datatype"   %  "jackson-datatype-jsr310",
    "com.fasterxml.jackson.dataformat" %  "jackson-dataformat-cbor",
    "com.fasterxml.jackson.module"     %  "jackson-module-parameter-names",
    "com.fasterxml.jackson.module"     %% "jackson-module-scala"
  ).map(_ % jacksonVersion)

  val all: Seq[ModuleID] = compile ++ jacksonOverrides ++ test

}
