import sbt.Keys.resolvers
import scoverage.ScoverageKeys
import uk.gov.hmrc.DefaultBuildSettings.{addTestReportOption, defaultSettings, scalaSettings, targetJvm}
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

val appName = "gmp-bulk"

lazy val scoverageSettings = Seq(
  ScoverageKeys.coverageExcludedPackages := "<empty>;app.*;gmp.*;config.*;metrics.*;testOnlyDoNotUseInAppConf.*;views.html.*;uk.gov.hmrc.*;prod.*",
  ScoverageKeys.coverageMinimum := 50,
  ScoverageKeys.coverageFailOnMinimum := true,
  ScoverageKeys.coverageHighlighting := true
)

lazy val plugins : Seq[Plugins] = Seq(
  play.sbt.PlayScala
)

lazy val microservice = Project(appName, file("."))
  .enablePlugins(plugins : _*)
  .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning, SbtArtifactory, SbtDistributablesPlugin)
  .disablePlugins(sbt.plugins.JUnitXmlReportPlugin)
  .settings(scoverageSettings,
    majorVersion := 2,
    scalaSettings,
    publishingSettings,
    defaultSettings(),
    routesImport += "extensions.Binders._",
    libraryDependencies ++= AppDependencies.all,
    parallelExecution in Test := false,
    fork in Test := false,
    retrieveManaged := true,
    PlayKeys.playDefaultPort := 9955,
    routesGenerator := InjectedRoutesGenerator,
    resolvers += Resolver.bintrayRepo("hmrc", "releases"),
    resolvers += Resolver.typesafeRepo("releases"),
    resolvers += Resolver.jcenterRepo,
    resolvers += "hmrc-releases" at "https://artefacts.tax.service.gov.uk/artifactory/hmrc-releases/"
  )
