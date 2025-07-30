import sbt.Keys.resolvers
import scoverage.ScoverageKeys
import uk.gov.hmrc.DefaultBuildSettings.{defaultSettings, scalaSettings}
import java.time.LocalDate
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.{HeaderLicense, headerLicense}

val appName = "gmp-bulk"

lazy val scoverageSettings = Seq(
  ScoverageKeys.coverageExcludedPackages := "<empty>;app.*;gmp.*;config.*;metrics.*;testOnlyDoNotUseInAppConf.*;views.html.*;uk.gov.hmrc.*;prod.*",
  ScoverageKeys.coverageMinimumStmtTotal := 76,
  ScoverageKeys.coverageFailOnMinimum := true,
  ScoverageKeys.coverageHighlighting := true
)

lazy val plugins : Seq[Plugins] = Seq(
  play.sbt.PlayScala
)

lazy val microservice = Project(appName, file("."))
  .enablePlugins(plugins : _*)
  .settings(headerLicense := {Some(HeaderLicense.ALv2(LocalDate.now().getYear.toString, "HM Revenue & Customs"))})
  .enablePlugins(SbtDistributablesPlugin)
  .disablePlugins(sbt.plugins.JUnitXmlReportPlugin)
  .settings(scoverageSettings,
    majorVersion := 2,
    scalaSettings,
    defaultSettings(),
    routesImport += "extensions.Binders._",
    libraryDependencies ++= AppDependencies.all,
    libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always,
    Test / parallelExecution := false,
    Test / fork := false,
    retrieveManaged := true,
    PlayKeys.playDefaultPort := 9955,
    routesGenerator := InjectedRoutesGenerator,
    resolvers += Resolver.typesafeRepo("releases")
  )
  .settings(scalaVersion := "2.13.16")
  .settings(
    scalacOptions ++= List(
      "-Yrangepos",
      "-Xlint:-missing-interpolator,_",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
      "-P:silencer:lineContentFilters=^\\w",
      "-P:silencer:pathFilters=routes"
    ))
  
