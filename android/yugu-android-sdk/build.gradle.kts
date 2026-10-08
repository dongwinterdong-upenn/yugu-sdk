// 优谷雅言 Android 核心 SDK，发布坐标 com.shengzhiai.yugu:yugu-android-sdk:2.0.0。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    jacoco
}

val sdkGroup = "com.shengzhiai.yugu"
val sdkArtifact = "yugu-android-sdk"
val sdkVersion = "2.0.0"

group = sdkGroup
version = sdkVersion

android {
    namespace = "com.shengzhiai.yugu"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        // Interface default methods become real Java default methods, so Java callers can
        // implement listeners without overriding every optional callback.
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    testOptions {
        unitTests {
            // Plain JVM tests may touch android.* stubs (Log, Looper) through the default
            // logger and executor; Robolectric tests get the real framework.
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// Only the debug variant runs unit tests, so `./gradlew test` runs the suite once.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
}

// ------------------------------------------------------------------------------------ tests

val repoRoot: File = rootDir.parentFile

tasks.withType<Test>().configureEach {
    maxHeapSize = "1024m"
    maxParallelForks = 1
    systemProperty("yugu.repoRoot", repoRoot.absolutePath)
    systemProperty("robolectric.logging.enabled", "false")
    // Integration tests start tools/mock-server with this node binary.
    providers.environmentVariable("YUGU_NODE").orNull?.let { systemProperty("yugu.node", it) }
    providers.environmentVariable("YUGU_REQUIRE_MOCK").orNull?.let { systemProperty("yugu.requireMock", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// --------------------------------------------------------------------------------- coverage

jacoco {
    toolVersion = "0.8.12"
}

val coverageClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*")
}

val jacocoTestReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Line coverage of :yugu-android-sdk debug unit tests (HTML and XML)."
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(coverageClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

val jacocoCoverageVerification by tasks.registering(JacocoCoverageVerification::class) {
    group = "verification"
    description = "Fails when line coverage of :yugu-android-sdk is below 70 %."
    dependsOn(jacocoTestReport)
    classDirectories.setFrom(coverageClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

// ------------------------------------------------------------------------------- publishing

val publishDir: Provider<String> = providers.gradleProperty("yuguPublishDir")
    .orElse(layout.buildDirectory.dir("yugu-maven").map { it.asFile.absolutePath })

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = sdkGroup
            artifactId = sdkArtifact
            version = sdkVersion
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name.set("优谷雅言 Android SDK")
                description.set("优谷雅言语音评测 Android 客户端：整段评测，声通兼容评测，语音合成，报告查询，实时评测与录音。")
                url.set("https://open.shengzhiai.com/docs.html#sdk")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("shengzhiai")
                        name.set("优谷雅言 SDK")
                        url.set("https://open.shengzhiai.com")
                    }
                }
                scm {
                    url.set("https://open.shengzhiai.com/git/yugu-sdk.git")
                    connection.set("scm:git:https://open.shengzhiai.com/git/yugu-sdk.git")
                    developerConnection.set("scm:git:https://open.shengzhiai.com/git/yugu-sdk.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "yuguDir"
            url = uri(file(publishDir.get()))
        }
    }
}

// ./gradlew :yugu-android-sdk:publishToYuguDir -PyuguPublishDir=/abs/path/repo
tasks.register("publishToYuguDir") {
    group = "publishing"
    description = "Publishes AAR, sources, javadoc, POM and checksums into -PyuguPublishDir."
    dependsOn("publishReleasePublicationToYuguDirRepository")
}
