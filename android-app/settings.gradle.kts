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
        // Репозиторий OrionStar (AgentOS SDK). Логин/пароль публичные — из документации вендора,
        // docs/vendor/AgentOS_SDK_Doc_v0.4.12_en.md, раздел 1.2.1. Это не секрет проекта.
        maven {
            url = uri("https://npm.ainirobot.com/repository/maven-public/")
            credentials {
                username = "agentMaven"
                password = "agentMaven"
            }
            content {
                includeGroupByRegex("com\\.orionstar.*")
                includeGroupByRegex("com\\.ainirobot.*")
            }
        }
    }
}

rootProject.name = "AstanaHubRobot"
include(":app")
