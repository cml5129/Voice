plugins {
  id("voice.library")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.datastore)

  implementation(libs.datastore)
  implementation(libs.serialization.json)
}
