plugins {
  id("voice.library")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.data.api)
  implementation(projects.core.datastore)
  implementation(projects.core.initializer)
  implementation(projects.core.logging.api)

  implementation(libs.androidxCore)
  implementation(libs.datastore)
  implementation(libs.okhttp)
  implementation(libs.serialization.json)

  testImplementation(libs.okhttp.mockwebserver)
}
