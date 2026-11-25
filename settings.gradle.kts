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
        // Skript API (Harbor-Skript compile only)
        maven("https://repo.skriptlang.org/releases") {
            content { includeGroup("com.github.SkriptLang") }
        }
        // PlaceholderAPI (Harbor-Core compile only: the %harbor_head%… expansion)
        maven("https://repo.extendedclip.com/releases/") {
            content { includeGroup("me.clip") }
        }
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
    "harbor-skript",
    "harbor-web",
    "harbor-tools",
)
