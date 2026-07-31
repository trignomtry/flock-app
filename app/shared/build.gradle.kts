plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("app.cash.sqldelight")
    kotlin("plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }
    js(IR) {
        browser()
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api("io.ktor:ktor-client-core:2.3.12")
            implementation("io.ktor:ktor-client-websockets:2.3.12")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")
            implementation("app.cash.sqldelight:runtime:2.0.2")
            implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
        }
        jsMain.dependencies {
            implementation("io.ktor:ktor-client-js:2.3.12")
        }
    }
}

android {
    namespace = "app.flock.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
}

sqldelight {
    databases {
        create("FlockDatabase") {
            packageName.set("app.flock.shared.store")
        }
    }
}
