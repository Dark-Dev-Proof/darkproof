pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "Blackout"
include(":app")
// The desktop app (Windows first) joins the build only when asked for with -Pdesktop, so that building the Android app
// never fetches the desktop's libraries: ./gradlew -Pdesktop :desktop:run
if (providers.gradleProperty("desktop").isPresent) include(":desktop")
