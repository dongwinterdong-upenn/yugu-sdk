// 优谷雅言 Android 示例工程，从 Maven 仓库拉取 com.shengzhiai.yugu:yugu-android-sdk:2.0.0。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// 默认仓库为 https://open.shengzhiai.com/maven/，可用 -PyuguMavenUrl=file:///path/to/repo 指向本地目录。
val yuguMavenUrl: String = providers.gradleProperty("yuguMavenUrl").getOrElse("https://open.shengzhiai.com/maven/")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The SDK group resolves only from the Yugu repository, everything else from Google and Maven Central.
        exclusiveContent {
            forRepository {
                maven {
                    name = "yugu"
                    url = uri(yuguMavenUrl)
                }
            }
            filter {
                includeGroup("com.shengzhiai.yugu")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "yugu-android-demo"
include(":app")
