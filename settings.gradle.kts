pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TVDE Insight"
// Optional isolated cache for QA. --no-build-cache avoids both reads and writes.
buildCache {
    local {
        providers.gradleProperty("qaBuildCacheDir").orNull?.let { directory = file(it) }
    }
}
include(":app")
