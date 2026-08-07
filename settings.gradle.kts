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

rootProject.name = "SoldNearbyUK"
include(":app")
// Play Asset Delivery install-time asset pack carrying seed_prices.db — see seed_data/build.gradle.kts
// for why this isn't just a plain asset in the app module.
include(":seed_data")
