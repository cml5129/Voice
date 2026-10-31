plugins {
  id("voice.library")
  id("voice.compose")
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.strings)
  implementation(projects.core.ui)
  implementation(projects.navigation)
  implementation(projects.core.data.api)
  implementation(projects.core.datastore)
  implementation(projects.core.logging.api)
  implementation(projects.core.summaries)
  implementation(projects.core.transcript)
}
