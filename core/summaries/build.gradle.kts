plugins {
  id("voice.library")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.data.api)
  implementation(projects.core.datastore)
  implementation(projects.core.logging.api)
  implementation(projects.core.transcript)

  implementation(libs.anthropic)
  implementation(libs.datastore)
  implementation(libs.okhttp)
  implementation(libs.serialization.json)

  testImplementation(libs.okhttp.mockwebserver)
}
