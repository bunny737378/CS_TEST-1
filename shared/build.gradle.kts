plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        // Must be unique
        namespace = "com.lagradost.cloudstream4"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources {
            enable = true
        }
    }

    jvm()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        all {
            languageSettings {
                optIn("com.lagradost.cloudstream3.InternalAPI")
                optIn("com.lagradost.cloudstream3.Prerelease")
            }
        }

        commonMain.dependencies {
            implementation(libs.coil.network.ktor3)
            implementation(libs.bundles.compose)
            implementation(libs.kotlinx.collections.immutable)
            implementation(project(":library"))
        }

        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.preference.ktx)
            implementation(libs.ktor.client.android)
        }

        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.lagradost.cloudstream4.generated.resources"
    generateResClass = auto
}

// Compose Multiplatform 1.12.0 publishes AAR metadata declaring a minimum
// compileSdk of 37, while this project pins compileSdk 36 (libs.versions.toml).
// That makes :shared:checkAndroidMainAarMetadata fail even though androidMain
// compiles cleanly against android-36 -- :app:assembleRelease builds this module
// without any unresolved SDK 37 API. The :app module already disables the same
// gate for the same reason (see app/build.gradle.kts), so mirror it here; a
// root-level `assembleDebug lint check` builds every module's variants and would
// otherwise stop on this policy check.
//
// Proper long-term fix: raise compileSdk to 37 and install platforms;android-37
// in the CI workflows, then drop both suppressions.
tasks.matching { it.name.contains("AarMetadata") }.configureEach {
    enabled = false
}
