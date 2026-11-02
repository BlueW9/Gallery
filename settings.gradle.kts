pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } } }
rootProject.name = "OpenFrame"
include(":app")
