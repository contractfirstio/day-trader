import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    // Only targeting Desktop JVM
    jvm("desktop")

    // ============================================================================
    // FIXED: Keeps the desktop compiler happy regarding experimental testing hooks
    // ============================================================================
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-opt-in=androidx.compose.ui.ExperimentalComposeUiApi")
                    freeCompilerArgs.add("-opt-in=org.jetbrains.compose.ui.test.ExperimentalComposeUiTestApi")
                    freeCompilerArgs.add("-opt-in=androidx.compose.ui.test.ExperimentalTestApi")
                }
            }
        }
    }

    sourceSets {
        // Shared logic and UI layout core
        named("commonMain").configure {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib")
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                // UI Component & Icon packs
                implementation(libs.compose.material3)
                implementation(libs.compose.icons.core)
                implementation(libs.compose.icons.extended)
            }
        }

        // Desktop specific wrappers (windowing, OS-level hooks)
        named("desktopMain").configure {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }

        // Shared cross-platform testing suite
        named("commonTest").configure {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.compose.ui:ui-test:1.8.1")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ComposeApp"
            packageVersion = "1.0.0"
        }
    }
}