import sbt.Keys.resolvers
import scoverage.ScoverageKeys
import uk.gov.hmrc.DefaultBuildSettings.{defaultSettings, scalaSettings}
import java.time.LocalDate
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.{HeaderLicense, headerLicense}

val appName = "gmp-bulk"

lazy val scoverageSettings = Seq(
  ScoverageKeys.coverageExcludedPackages := "<empty>;app.*;gmp.*;config.*;metrics.*;testOnlyDoNotUseInAppConf.*;views.html.*;uk.gov.hmrc.*;prod.*;models.*;",
  ScoverageKeys.coverageMinimumStmtTotal := 76,
  ScoverageKeys.coverageFailOnMinimum := true,
  ScoverageKeys.coverageHighlighting := true
)

lazy val plugins : Seq[Plugins] = Seq(
  play.sbt.PlayScala
)

lazy val microservice = Project(appName, file("."))
  .enablePlugins(plugins *)
  .settings(headerLicense := {Some(HeaderLicense.ALv2(LocalDate.now().getYear.toString, "HM Revenue & Customs"))})
  .enablePlugins(SbtDistributablesPlugin)
  .disablePlugins(sbt.plugins.JUnitXmlReportPlugin)
  .settings(scoverageSettings,
    majorVersion := 2,
    scalaSettings,
    defaultSettings(),
    routesImport += "extensions.Binders._",
    libraryDependencies ++= AppDependencies.all,
    Test / parallelExecution := false,
    Test / fork := false,
    retrieveManaged := true,
    PlayKeys.playDefaultPort := 9955,
    routesGenerator := InjectedRoutesGenerator,
    resolvers += Resolver.typesafeRepo("releases")
  )
  .settings(scalaVersion := "3.7.4")
  .settings(
    scalacOptions ++= List(
      "-explaintypes",
      "-feature",
      "-unchecked",
      "-Wconf:src=html/.*:s",
      "-Wconf:src=routes/.*:s",
      "-Wconf:msg=Flag.*repeatedly:s",
      "-Wconf:msg=Implicit parameters should be provided with a `using` clause:s",
      "-Wconf:msg=unused explicit parameter:s",
      "-Wconf:msg=Setting -Wunused set to all redundantly:s"
    ))
  
