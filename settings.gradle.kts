pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Qualcomm AI Hub / GenieX SDK — add local maven path when SDK is available
        // maven { url = uri("${rootDir}/libs/genieX/repo") }
    }
}

rootProject.name = "Lumi"
include(":app")
