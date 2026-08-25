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

rootProject.name = "BrazilMR_V2"
include(":app")

// Descomente a linha abaixo depois de importar o módulo OpenCV
// (File > New > Import Module, aponte para <OpenCV-android-sdk>/sdk/java, renomeie para "opencv")
// include(":opencv")
