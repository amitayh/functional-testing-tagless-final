import sbt._

object Dependencies {
  lazy val catsCore   = "org.typelevel" %% "cats-core"    % "1.6.0"
  lazy val catsEffect = "org.typelevel" %% "cats-effect"  % "1.2.0"
  lazy val specs2     = "org.specs2"    %% "specs2-core"  % "4.3.4"
}
