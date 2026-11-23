pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // JDK 25 가 없으면 Gradle 이 자동으로 내려받도록 함
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "minecraft_plugin"

include(":common")

// plugins/ 아래 폴더 하나 = 플러그인 하나
include(":plugins:custom-items")
include(":plugins:home")
