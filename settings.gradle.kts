rootProject.name = "creeper-harbor"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.purpurmc.org/snapshots")
    }
}

include(
    "harbor-common",
    "harbor-core",
    "harbor-proxy",
    "harbor-jobs",
    "harbor-fishing",
    "harbor-collection",
    "harbor-npc",
    "harbor-quest",
    "harbor-economy",
    "harbor-world",
    "harbor-boss",
    "harbor-season",
    "harbor-shop",
    "harbor-wildlife",
    "harbor-tools",
)
