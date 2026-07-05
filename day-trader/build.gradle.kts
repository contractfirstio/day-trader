import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.testing.internal.KotlinTestReport

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinxSerialization)
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
                implementation(libs.compose.material3)
                implementation(compose.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                // UI Component & Icon packs
                implementation(libs.compose.icons.core)
                implementation(libs.compose.icons.extended)
            }
        }

        // Desktop specific wrappers (windowing, OS-level hooks)
        named("desktopMain").configure {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(files("libs/TwsApi.jar"))
                implementation(libs.protobuf.java)
            }
        }

        // Shared cross-platform testing suite
        named("commonTest").configure {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.compose.ui:ui-test:1.8.1")
                implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.11.4")
                implementation(libs.junit)
                implementation("io.cucumber:cucumber-java:7.20.1")
                implementation("io.cucumber:cucumber-junit:7.20.1")
                // CucumberTestSuite uses JUnit 4 @RunWith — vintage engine runs it on JUnit Platform.
                runtimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
            }
        }
    }
}

fun Test.configureTestDefaults(
    includeModeTag: String? = null,
    excludeModeTag: String? = null,
) {
    useJUnitPlatform {
        includeModeTag?.let { includeTags(it) }
        excludeModeTag?.let { excludeTags(it) }
    }
    maxParallelForks = 1
    // Recycle JVM every 50 classes so coroutine leaks cannot accumulate across the full suite.
    forkEvery = 50
    systemProperty("junit.jupiter.execution.timeout.default", "30s")
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

/**
 * Stricter defaults for BDD and programmatic E2E: one Gradle Test worker, one JVM per test class,
 * no JUnit parallel execution. Gradle [isolatedE2eTestTaskNames] also chains these tasks so only
 * one E2E/BDD task runs at a time when multiple are scheduled in the same build.
 */
fun Test.configureE2eTestDefaults(
    includeModeTag: String? = null,
    excludeModeTag: String? = null,
) {
    configureTestDefaults(includeModeTag = includeModeTag, excludeModeTag = excludeModeTag)
    forkEvery = (project.findProperty("daytrader.e2e.forkEvery") as String?)?.toLongOrNull() ?: 1L
}

fun Test.useDesktopTestClasspath() {
    val desktopTestTask = tasks.named<Test>("desktopTest")
    classpath = desktopTestTask.get().classpath
    testClassesDirs = desktopTestTask.get().testClassesDirs
    dependsOn(tasks.named("desktopTestClasses"))
}

fun Test.includeCucumberSuite(suiteClass: String) {
    filter {
        includeTestsMatching(suiteClass)
    }
}

fun Test.configureE2eModeReports(taskName: String) {
    reports {
        html.outputLocation.set(layout.buildDirectory.dir("reports/tests/$taskName"))
        junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/$taskName"))
    }
}

tasks.named<Test>("desktopTest") {
    configureTestDefaults()
    description =
        "Full verification suite (same as allTests / fullTestSuite). " +
            "Runs unitTest then all broker-mode E2E groups sequentially; this KMP task runs no tests itself."
    dependsOn(tasks.named("fullTestSuite"))
    useJUnitPlatform {
        includeTags("daytrader.fullTestSuite.delegate")
    }
    filter {
        isFailOnNoMatchingTests = false
    }
}

tasks.register<Test>("unitTest") {
    group = "verification"
    description =
        "Unit tests and domain smoke tests (excludes all E2E and Cucumber BDD)."
    configureTestDefaults(excludeModeTag = "e2e")
    useDesktopTestClasspath()
    filter {
        excludeTestsMatching("daytrader.e2e.Cucumber*")
    }
    reports {
        html.outputLocation.set(layout.buildDirectory.dir("reports/tests/unitTest"))
        junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/unitTest"))
    }
}

tasks.register<Test>("bddTest") {
    group = "verification"
    description = "All Cucumber BDD scenarios in one JVM (CucumberTestSuite — prefer bddAll for per-mode isolation)."
    configureE2eTestDefaults()
    useDesktopTestClasspath()
    includeCucumberSuite("daytrader.e2e.CucumberTestSuite")
    configureE2eModeReports("bddTest")
}

/** Per-mode Cucumber suites — [bddAll] runs these one after another (separate JVM per mode). */
private val bddModeTestTaskNames = listOf(
    "bddEmulator",
    "bddPaper",
    "bddIb",
    "bddIbWip",
    "bddReplay",
)

tasks.register("bddAll") {
    group = "verification"
    description =
        "All Cucumber BDD scenarios, one broker mode at a time: emulator → paper → IB → IB @wip → replay."
    dependsOn(bddModeTestTaskNames.map { tasks.named(it) })
}

listOf(
    Triple("bddEmulator", "daytrader.e2e.CucumberEmulatorTestSuite", "Cucumber BDD for broker emulator mode."),
    Triple("bddPaper", "daytrader.e2e.CucumberPaperTestSuite", "Cucumber BDD for paper mode (live IB data + emulator execution)."),
    Triple("bddIb", "daytrader.e2e.CucumberIbTestSuite", "Cucumber BDD for Interactive Brokers mode."),
    Triple("bddIbWip", "daytrader.e2e.CucumberIbWipTestSuite", "Cucumber BDD for IB mode work-in-progress scenarios (@wip)."),
    Triple("bddReplay", "daytrader.e2e.CucumberReplayTestSuite", "Cucumber BDD for session replay mode."),
).forEach { (taskName, suiteClass, descriptionText) ->
    tasks.register<Test>(taskName) {
        group = "verification"
        description = descriptionText
        configureE2eTestDefaults()
        useDesktopTestClasspath()
        includeCucumberSuite(suiteClass)
        configureE2eModeReports(taskName)
    }
}

listOf(
    Triple("e2eEmulatorTests", "e2e-emulator", "Programmatic E2E for broker emulator mode."),
    Triple("e2ePaperTests", "e2e-paper", "Programmatic E2E for paper mode (live IB data + emulator execution)."),
    Triple("e2eIbTests", "e2e-ib", "Programmatic E2E for Interactive Brokers mode."),
    Triple("e2eReplayTests", "e2e-replay", "Programmatic E2E for session replay mode."),
).forEach { (taskName, modeTag, descriptionText) ->
    tasks.register<Test>(taskName) {
        group = "verification"
        description = descriptionText
        configureE2eTestDefaults(includeModeTag = modeTag)
        useDesktopTestClasspath()
        configureE2eModeReports(taskName)
    }
}

listOf(
    "e2eEmulator" to listOf("bddEmulator", "e2eEmulatorTests"),
    "e2ePaper" to listOf("bddPaper", "e2ePaperTests"),
    "e2eIb" to listOf("bddIb", "e2eIbTests"),
    "e2eReplay" to listOf("bddReplay", "e2eReplayTests"),
).forEach { (taskName, dependencies) ->
    tasks.register(taskName) {
        group = "verification"
        description = "Full end-to-end for ${taskName.removePrefix("e2e").lowercase()} mode (BDD + programmatic)."
        dependencies.forEach { dependsOn(tasks.named(it)) }
    }
}

/** Per-mode programmatic E2E (JUnit @E2E*Test) — [e2eProgrammaticAll] runs these sequentially. */
private val e2eProgrammaticTestTaskNames = listOf(
    "e2eEmulatorTests",
    "e2ePaperTests",
    "e2eIbTests",
    "e2eReplayTests",
)

tasks.register("e2eProgrammaticAll") {
    group = "verification"
    description = "All programmatic E2E tests, one broker mode at a time: emulator → paper → IB → replay."
    dependsOn(e2eProgrammaticTestTaskNames.map { tasks.named(it) })
}

/** Per-mode full E2E (BDD + programmatic) — [e2eTest] runs these sequentially. */
private val e2eModeAggregatorTaskNames = listOf(
    "e2eEmulator",
    "e2ePaper",
    "e2eIb",
    "e2eReplay",
)

tasks.register("e2eTest") {
    group = "verification"
    description = "All broker-mode end-to-end (BDD + programmatic per mode), one mode at a time."
    dependsOn(e2eModeAggregatorTaskNames.map { tasks.named(it) })
}

tasks.register("fullTestSuite") {
    group = "verification"
    description = "Everything: unit tests, then all broker-mode E2E (BDD + programmatic per mode), sequentially."
    dependsOn(tasks.named("unitTest"), tasks.named("e2eTest"))
}

/**
 * When multiple E2E/BDD Test tasks are scheduled in one build, run them strictly one after another
 * (even when org.gradle.parallel=true). Does not affect unitTest or desktopTest.
 */
private val isolatedE2eTestTaskNames = listOf(
    "bddTest",
    "bddEmulator",
    "e2eEmulatorTests",
    "bddPaper",
    "e2ePaperTests",
    "bddIb",
    "bddIbWip",
    "e2eIbTests",
    "bddReplay",
    "e2eReplayTests",
)

if ((project.findProperty("daytrader.e2e.sequentialTasks") as String?)?.toBoolean() != false) {
    bddModeTestTaskNames.zipWithNext { first, second ->
        tasks.named<Test>(second) {
            mustRunAfter(tasks.named<Test>(first))
        }
    }
    e2eProgrammaticTestTaskNames.zipWithNext { first, second ->
        tasks.named<Test>(second) {
            mustRunAfter(tasks.named<Test>(first))
        }
    }
    isolatedE2eTestTaskNames.zipWithNext { first, second ->
        tasks.named<Test>(second) {
            mustRunAfter(tasks.named<Test>(first))
        }
    }
    e2eModeAggregatorTaskNames.zipWithNext { first, second ->
        tasks.named(second) {
            mustRunAfter(tasks.named(first))
        }
    }
    tasks.named("e2eTest") {
        mustRunAfter(tasks.named("unitTest"))
    }
    tasks.named("fullTestSuite") {
        mustRunAfter(tasks.named("unitTest"))
    }
}

fun Project.registerE2eStressTask(
    taskName: String,
    descriptionText: String,
    repeats: Int,
    vararg gradleTasks: String,
) {
    tasks.register(taskName) {
        group = "verification"
        description = descriptionText
        notCompatibleWithConfigurationCache("Runs nested Gradle invocations in a loop")
        doLast {
            val repoRoot = rootProject.layout.projectDirectory.asFile
            val gradlew = repoRoot.resolve("gradlew").absolutePath
            val taskList = gradleTasks.toList()
            repeat(repeats) { runIndex ->
                val exitCode = ProcessBuilder(
                    listOf(gradlew) + taskList + listOf("--rerun-tasks", "-q", "--no-configuration-cache")
                )
                    .directory(repoRoot)
                    .inheritIO()
                    .start()
                    .waitFor()
                if (exitCode != 0) {
                    throw GradleException(
                        "$taskName failed on run ${runIndex + 1}/$repeats (exit code $exitCode)"
                    )
                }
                logger.lifecycle("$taskName: completed run ${runIndex + 1}/$repeats")
            }
        }
    }
}

registerE2eStressTask(
    taskName = "bddIbStress10",
    descriptionText = "Runs bddIb 10 times sequentially; fails on first failure.",
    repeats = 10,
    ":day-trader:bddIb",
)
registerE2eStressTask(
    taskName = "bddIbWipStress10",
    descriptionText = "Runs bddIbWip 10 times sequentially (empty until new @wip scenarios are added).",
    repeats = 10,
    ":day-trader:bddIbWip",
)
registerE2eStressTask(
    taskName = "e2eIbStress10",
    descriptionText = "Runs bddIb + e2eIbTests 10 times; fails on first failure.",
    repeats = 10,
    ":day-trader:bddIb",
    ":day-trader:e2eIbTests",
)

registerE2eStressTask(
    taskName = "unitTestStress10",
    descriptionText = "Runs unitTest 10 times sequentially with --rerun-tasks.",
    repeats = 10,
    ":day-trader:unitTest",
)
registerE2eStressTask(
    taskName = "e2eEmulatorStress10",
    descriptionText = "Runs e2eEmulator 10 times sequentially with --rerun-tasks.",
    repeats = 10,
    ":day-trader:e2eEmulator",
)
registerE2eStressTask(
    taskName = "e2ePaperStress10",
    descriptionText = "Runs e2ePaper 10 times sequentially with --rerun-tasks.",
    repeats = 10,
    ":day-trader:e2ePaper",
)
registerE2eStressTask(
    taskName = "e2eIbModeStress10",
    descriptionText = "Runs e2eIb (bddIb + e2eIbTests) 10 times sequentially with --rerun-tasks.",
    repeats = 10,
    ":day-trader:e2eIb",
)
registerE2eStressTask(
    taskName = "e2eReplayStress10",
    descriptionText = "Runs e2eReplay 10 times sequentially with --rerun-tasks.",
    repeats = 10,
    ":day-trader:e2eReplay",
)
registerE2eStressTask(
    taskName = "allTestsStress10",
    descriptionText = "Runs allTests 10 times sequentially with --rerun-tasks.",
    repeats = 10,
    ":day-trader:fullTestSuite",
)

/** Convenience alias — same as [fullTestSuite] / [desktopTest] / [allTests]. */
tasks.register("test") {
    group = "verification"
    description = "Runs the full test suite (alias for fullTestSuite / allTests / desktopTest)."
    dependsOn(tasks.named("fullTestSuite"))
}

// KMP registers `allTests` (KotlinTestReport) → desktopTest by default. Rewire to fullTestSuite.
afterEvaluate {
    tasks.named<KotlinTestReport>("allTests") {
        enabled = true
        setDependsOn(listOf(tasks.named("fullTestSuite")))
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        jvmArgs += listOf(
            "-Xmx32g",
            "-Dapple.awt.application.name=Day Trader",
            "-Dapple.laf.useScreenMenuBar=true",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Day Trader"
            packageVersion = "1.0.0"
            macOS {
                dockName = "Day Trader"
            }
        }
    }
}