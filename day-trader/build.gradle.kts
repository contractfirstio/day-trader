import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.testing.internal.KotlinTestReport

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.kover)
}

private val bddEmulatorShardTaskNames = listOf(
    "bddEmulatorShard1",
    "bddEmulatorShard2",
)
private val bddIbShardTaskNames = listOf(
    "bddIbShard1",
    "bddIbShard2",
    "bddIbShard3",
)

private val bddCoverageTaskNames = listOf(
    "bddTest",
    *bddEmulatorShardTaskNames.toTypedArray(),
    "bddEmulatorWip",
    "bddPaper",
    *bddIbShardTaskNames.toTypedArray(),
    "bddIbWip",
    "bddReplay",
)

/** Maps coverage.bdd.only aggregator tasks to instrumented shard Test tasks. */
private fun resolveBddCoverageTasks(only: String): List<String> = when (only) {
    "bddEmulator" -> bddEmulatorShardTaskNames
    "bddIb" -> bddIbShardTaskNames
    else -> listOf(only)
}

private val programmaticE2eTestTaskNames = listOf(
    "e2eEmulatorTests",
    "e2ePaperTests",
    "e2eIbTests",
    "e2eReplayTests",
)

/** Default coverage scope is BDD; pass -Pcoverage.scope=unit for unit-only reports. */
fun Project.requestedCoverageScope(): String {
    (findProperty("coverage.scope") as String?)?.let { return it }
    val requestedTasks = gradle.startParameter.taskNames.joinToString(" ")
    return if (requestedTasks.contains("coverageUnit")) "unit" else "bdd"
}

fun Project.koverExcludedTestTaskNames(): List<String> = buildList {
    addAll(programmaticE2eTestTaskNames)
    when (requestedCoverageScope()) {
        "bdd" -> add("desktopTest")
        "unit" -> addAll(bddCoverageTaskNames)
        "all" -> Unit
        else -> error("Unknown coverage.scope (use bdd, unit, or all)")
    }
    (findProperty("coverage.bdd.only") as String?)?.let { onlyBddTask ->
        val included = resolveBddCoverageTasks(onlyBddTask)
        addAll(bddCoverageTaskNames.filter { it !in included })
    }
}

fun Project.koverBddTasksForCoverage(): List<String> {
    val only = findProperty("coverage.bdd.only") as String?
    return if (only != null) resolveBddCoverageTasks(only) else bddCoverageTaskNames
}

fun Project.shouldInstrumentBddTask(taskName: String): Boolean {
    if (requestedCoverageScope() == "unit") return false
    return taskName in koverBddTasksForCoverage()
}

/**
 * Kover auto-instruments only Kotlin JVM platform test tasks (desktopTest).
 * Custom bdd* Test tasks need the same on-the-fly agent so their binary reports merge into koverHtmlReport.
 */
fun Test.configureManualKoverInstrumentation(project: Project, enabled: Provider<Boolean>) {
    val findAgentJar = project.tasks.named("koverFindJar")
    dependsOn(findAgentJar)
    val agentJar = project.layout.buildDirectory.file("kover/kover-jvm-agent-0.9.8.jar")
    val binReport = project.layout.buildDirectory.map { dir ->
        dir.file("kover/binreports/${name}.ic")
    }
    doFirst {
        val reportFile = binReport.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.delete()
    }
    jvmArgumentProviders.add(
        object : CommandLineArgumentProvider {
            @get:InputFile
            @get:PathSensitive(PathSensitivity.RELATIVE)
            val agentJarPath = agentJar

            @get:OutputFile
            val binReportPath = binReport

            @get:Input
            val instrumentationEnabled = enabled

            @get:Internal
            val tempDir = temporaryDir

            override fun asArguments(): Iterable<String> {
                if (!instrumentationEnabled.get()) {
                    return emptyList()
                }
                val reportFile = binReportPath.get().asFile
                val argsFile = tempDir.resolve("kover-${name}.args")
                argsFile.writeText("report.file=${reportFile.canonicalPath}\n")
                val jar = agentJarPath.get().asFile
                return listOf("-javaagent:${jar.canonicalPath}=file:${argsFile.canonicalPath}")
            }
        },
    )
}

kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.addAll(koverExcludedTestTaskNames())
        }
    }
    reports {
        filters {
            excludes {
                // Third-party IB API — not our code.
                classes("com.ib.*")
            }
        }
        total {
            html {
                title.set("Day Trader — BDD coverage")
            }
            additionalBinaryReports.set(
                provider {
                    if (requestedCoverageScope() == "unit") {
                        emptySet()
                    } else {
                        koverBddTasksForCoverage().map { taskName ->
                            layout.buildDirectory.file("kover/binreports/$taskName.ic").get().asFile
                        }.toSet()
                    }
                },
            )
        }
    }
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
    configureTestIsolation()
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

/** Per Gradle Test task: isolated app-data root and no session price JSONL during tests. */
fun Test.configureTestIsolation() {
    val testHome = layout.buildDirectory.dir("test-home/$name").get().asFile
    environment("DAY_TRADER_DATA_DIR", testHome.absolutePath)
    environment("DAY_TRADER_SESSION_PRICE_LOGS", "false")
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

fun Test.configureUnitTestTask() {
    group = "verification"
    configureTestDefaults(excludeModeTag = "e2e")
    maxParallelForks = (project.findProperty("daytrader.unit.maxParallelForks") as String?)
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: 1
    forkEvery = (project.findProperty("daytrader.unit.forkEvery") as String?)?.toLongOrNull() ?: 25L
    filter {
        excludeTestsMatching("daytrader.e2e.Cucumber*")
    }
    reports {
        html.outputLocation.set(layout.buildDirectory.dir("reports/tests/unitTest"))
        junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/unitTest"))
    }
}

tasks.named<Test>("desktopTest") {
    configureUnitTestTask()
    description =
        "Unit tests and domain smoke tests (excludes all E2E and Cucumber BDD). " +
            "KMP default test task — instrumented by Kover; same as unitTest."
}

tasks.register("unitTest") {
    group = "verification"
    description = "Alias for desktopTest (unit and domain tests only)."
    dependsOn(tasks.named("desktopTest"))
}

tasks.register<Test>("bddTest") {
    group = "verification"
    description = "All Cucumber BDD scenarios in one JVM (CucumberTestSuite — prefer bddAll for per-mode isolation)."
    configureE2eTestDefaults()
    useDesktopTestClasspath()
    includeCucumberSuite("daytrader.e2e.CucumberTestSuite")
    configureE2eModeReports("bddTest")
}

/** Per-mode Cucumber suites — [bddAll] runs these one after another (separate JVM per mode/shard). */
private val bddModeAggregatorTaskNames = listOf(
    "bddEmulator",
    "bddEmulatorWip",
    "bddPaper",
    "bddIb",
    "bddIbWip",
    "bddReplay",
)

tasks.register("bddAll") {
    group = "verification"
    description =
        "All Cucumber BDD scenarios, one broker mode at a time: emulator (2 shards) → emulator @wip → " +
            "paper → IB (3 shards) → IB @wip → replay."
    dependsOn(bddModeAggregatorTaskNames.map { tasks.named(it) })
}

listOf(
    Triple("bddEmulatorShard1", "daytrader.e2e.CucumberEmulatorShard1TestSuite", "Emulator BDD shard 1/2."),
    Triple("bddEmulatorShard2", "daytrader.e2e.CucumberEmulatorShard2TestSuite", "Emulator BDD shard 2/2."),
    Triple("bddIbShard1", "daytrader.e2e.CucumberIbShard1TestSuite", "IB BDD shard 1/3 (gateway and bootstrap)."),
    Triple("bddIbShard2", "daytrader.e2e.CucumberIbShard2TestSuite", "IB BDD shard 2/3 (liquidity, prepare, capture)."),
    Triple("bddIbShard3", "daytrader.e2e.CucumberIbShard3TestSuite", "IB BDD shard 3/3 (five-minute and prepare)."),
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

tasks.register("bddEmulator") {
    group = "verification"
    description = "All emulator Cucumber BDD (2 parallel shards when sequentialTasks=false)."
    dependsOn(bddEmulatorShardTaskNames.map { tasks.named(it) })
}

listOf(
    Triple("bddEmulatorWip", "daytrader.e2e.CucumberEmulatorWipTestSuite", "Cucumber BDD for emulator mode work-in-progress scenarios (@wip)."),
    Triple("bddPaper", "daytrader.e2e.CucumberPaperTestSuite", "Cucumber BDD for paper mode (live IB data + emulator execution)."),
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

tasks.register("bddIb") {
    group = "verification"
    description = "All IB Cucumber BDD (3 parallel shards when sequentialTasks=false)."
    dependsOn(bddIbShardTaskNames.map { tasks.named(it) })
}

tasks.register<Test>("bddIbMonolith") {
    group = "verification"
    description = "All IB Cucumber BDD in one JVM (CucumberIbTestSuite — debugging only; prefer bddIb shards)."
    configureE2eTestDefaults()
    useDesktopTestClasspath()
    includeCucumberSuite("daytrader.e2e.CucumberIbTestSuite")
    configureE2eModeReports("bddIbMonolith")
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
    tasks.named<Test>(dependencies[1]) {
        mustRunAfter(tasks.named(dependencies[0]))
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

/** Internal aggregator: unitTest then e2eTest. Prefer [allTestsSequential] or [allTestsParallel] at the CLI. */
tasks.register("fullTestSuite") {
    group = "verification"
    description =
        "Low-level aggregator (unitTest + e2eTest). Ordering is chosen by the requested suite task " +
            "(allTestsSequential, allTestsParallel, or allTestsParallelModes)."
    dependsOn(tasks.named("unitTest"), tasks.named("e2eTest"))
}

private fun fullSuiteUnitForksNote(): String =
    "Unit tests use parallel Gradle forks (daytrader.unit.maxParallelForks, default 4)."

tasks.register("allTestsSequential") {
    group = "verification — full suite"
    description =
        "Full suite, CI-safe: unitTest then all E2E/BDD with every isolated E2E task and broker mode " +
            "running strictly one at a time (IB/emulator BDD shards sequential). ${fullSuiteUnitForksNote()}"
    dependsOn(tasks.named("fullTestSuite"))
}

tasks.register("allTestsParallel") {
    group = "verification — full suite"
    description =
        "Full suite, faster local run: same coverage as allTestsSequential but IB Cucumber (3 shards) and " +
            "emulator Cucumber (2 shards) run in parallel within each mode; broker modes still sequential. " +
            fullSuiteUnitForksNote()
    dependsOn(tasks.named("fullTestSuite"))
}

tasks.register("allTestsParallelModes") {
    group = "verification — full suite"
    description =
        "Full suite, expert / may flake: parallel BDD shards plus all four broker modes " +
            "(e2eEmulator, e2ePaper, e2eIb, e2eReplay) at once. Prefer allTestsParallel for day-to-day. " +
            fullSuiteUnitForksNote()
    dependsOn(tasks.named("fullTestSuite"))
}

tasks.register("fastAllTests") {
    group = "verification — full suite"
    description = "Alias for allTestsParallel."
    dependsOn(tasks.named("allTestsParallel"))
}

/**
 * When multiple E2E/BDD Test tasks are scheduled in one build, run them strictly one after another
 * (even when org.gradle.parallel=true). Does not affect unitTest or desktopTest.
 */
private val isolatedE2eTestTaskNames = listOf(
    "bddTest",
    "bddEmulatorShard1",
    "bddEmulatorShard2",
    "bddEmulatorWip",
    "e2eEmulatorTests",
    "bddPaper",
    "e2ePaperTests",
    "bddIbShard1",
    "bddIbShard2",
    "bddIbShard3",
    "bddIbWip",
    "e2eIbTests",
    "bddReplay",
    "e2eReplayTests",
)

/**
 * Task-graph flags for E2E ordering (configuration time only).
 *
 * - [parallelBddShards]: IB/emulator Cucumber shards in parallel within a mode.
 * - [parallelE2eModes]: all four broker-mode E2E aggregators in parallel (expert only; can flake).
 * - [fullSequentialE2e]: chain every isolated E2E/BDD Test task (CI default).
 */
private val parallelBddShardSuiteTaskNames = setOf(
    "allTestsParallel",
    "fastAllTests",
    "allTestsParallelStress3",
    "fastAllTestsStress3",
)

private val parallelE2eModeSuiteTaskNames = setOf(
    "allTestsParallelModes",
)

fun Project.requestedTaskLeafNames(): List<String> =
    gradle.startParameter.taskNames.map { it.removePrefix(":").substringAfterLast(':') }

fun Project.parallelBddShards(): Boolean {
    if (requestedTaskLeafNames().any { it in parallelE2eModeSuiteTaskNames }) return true
    if (requestedTaskLeafNames().any { it in parallelBddShardSuiteTaskNames }) return true
    return (findProperty("daytrader.e2e.sequentialTasks") as String?)?.toBoolean() == false
}

fun Project.parallelE2eModes(): Boolean {
    if (requestedTaskLeafNames().any { it in parallelE2eModeSuiteTaskNames }) return true
    return (findProperty("daytrader.e2e.sequentialTasks") as String?)?.toBoolean() == false
}

fun Project.fullSequentialE2e(): Boolean = !parallelBddShards() && !parallelE2eModes()

if (!parallelBddShards()) {
    bddEmulatorShardTaskNames.zipWithNext { first, second ->
        tasks.named<Test>(second) {
            mustRunAfter(tasks.named<Test>(first))
        }
    }
    bddIbShardTaskNames.zipWithNext { first, second ->
        tasks.named<Test>(second) {
            mustRunAfter(tasks.named<Test>(first))
        }
    }
}

if (fullSequentialE2e()) {
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
}

if (!parallelE2eModes()) {
    e2eModeAggregatorTaskNames.zipWithNext { first, second ->
        tasks.named(second) {
            mustRunAfter(tasks.named(first))
        }
    }
}

/** Unit tests always finish before any E2E/BDD Test task (use desktopTest, not the unitTest alias). */
private fun Project.configureE2eRunsAfterUnitTests() {
    isolatedE2eTestTaskNames.forEach { taskName ->
        tasks.named<Test>(taskName) {
            mustRunAfter(tasks.named("desktopTest"))
        }
    }
}

configureE2eRunsAfterUnitTests()

listOf(
    "e2eTest",
    "fullTestSuite",
    "allTestsSequential",
    "allTestsParallel",
    "allTestsParallelModes",
    "fastAllTests",
).forEach { suiteTaskName ->
    tasks.named(suiteTaskName) {
        mustRunAfter(tasks.named("desktopTest"))
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
    taskName = "allTestsSequentialStress10",
    descriptionText = "Runs allTestsSequential 10 times with --rerun-tasks; fails on first failure.",
    repeats = 10,
    ":day-trader:allTestsSequential",
)
registerE2eStressTask(
    taskName = "allTestsStress10",
    descriptionText = "Alias for allTestsSequentialStress10.",
    repeats = 10,
    ":day-trader:allTestsSequential",
)
registerE2eStressTask(
    taskName = "allTestsParallelStress3",
    descriptionText = "Runs allTestsParallel 3 times with --rerun-tasks; fails on first failure.",
    repeats = 3,
    ":day-trader:allTestsParallel",
)
registerE2eStressTask(
    taskName = "fastAllTestsStress3",
    descriptionText = "Alias for allTestsParallelStress3.",
    repeats = 3,
    ":day-trader:allTestsParallel",
)

tasks.register("printTestSuites") {
    group = "help"
    description = "Print top-level full-suite Gradle tasks and what each runs."
    doLast {
        println(
            """
            |
            |Full-suite commands (repo root; each delegates to :day-trader):
            |
            |  ./gradlew allTestsSequential
            |    unitTest + all E2E/BDD — every isolated task sequential; broker modes sequential;
            |    IB/emulator BDD shards sequential. CI default.
            |
            |  ./gradlew allTestsParallel   (alias: fastAllTests)
            |    Same coverage — IB Cucumber 3 shards + emulator 2 shards in parallel;
            |    broker modes still one at a time.
            |
            |  ./gradlew allTestsParallelModes
            |    Expert — parallel shards AND all four broker modes at once (may flake).
            |
            |  ./gradlew allTests
            |    Alias for allTestsSequential (KMP KotlinTestReport wiring).
            |
            |Per-mode E2E: e2eEmulator | e2ePaper | e2eIb | e2eReplay
            |Unit only:     unitTest
            |
            |Add --rerun-tasks to force a fresh run.
            |
            """.trimMargin()
        )
    }
}

/** Convenience alias — same as [allTestsSequential]. */
tasks.register("test") {
    group = "verification — full suite"
    description = "Alias for allTestsSequential."
    dependsOn(tasks.named("allTestsSequential"))
}

tasks.register("coverageBdd") {
    group = "verification"
    description =
        "Run all Cucumber BDD suites and generate a merged HTML coverage report " +
            "(build/reports/kover/html/index.html). Unit and programmatic E2E are excluded."
    dependsOn(tasks.named("bddAll"))
}

tasks.named("coverageBdd") {
    finalizedBy(tasks.named("koverHtmlReport"))
}

listOf(
    "bddEmulator" to "Emulator",
    "bddEmulatorWip" to "EmulatorWip",
    "bddPaper" to "Paper",
    "bddIb" to "Ib",
    "bddIbWip" to "IbWip",
    "bddReplay" to "Replay",
).forEach { (bddTask, suffix) ->
    tasks.register("coverageBdd$suffix") {
        group = "verification"
        description =
            "BDD coverage for $bddTask only. Runs :day-trader:koverHtmlReport with " +
                "-Pcoverage.bdd.only=$bddTask."
        notCompatibleWithConfigurationCache("Spawns nested Gradle with mode property")
        doLast {
            val repoRoot = rootProject.layout.projectDirectory.asFile
            val gradlew = repoRoot.resolve("gradlew").absolutePath
            val exitCode = ProcessBuilder(
                listOf(
                    gradlew,
                    ":day-trader:koverHtmlReport",
                    "-Pcoverage.bdd.only=$bddTask",
                    "--no-configuration-cache",
                ),
            )
                .directory(repoRoot)
                .inheritIO()
                .start()
                .waitFor()
            if (exitCode != 0) {
                throw GradleException("coverageBdd$suffix failed (exit code $exitCode)")
            }
        }
    }
}

tasks.register("coverageUnit") {
    group = "verification"
    description =
        "Unit-test coverage only (desktopTest). Use -Pcoverage.scope=unit implicitly via this task name."
    dependsOn(tasks.named("desktopTest"))
}

tasks.named("coverageUnit") {
    finalizedBy(tasks.named("koverHtmlReport"))
}

afterEvaluate {
    bddCoverageTaskNames.forEach { bddTaskName ->
        tasks.named<Test>(bddTaskName).configure {
            val enabled = project.provider { shouldInstrumentBddTask(bddTaskName) }
            configureManualKoverInstrumentation(project, enabled)
        }
    }
}

// KMP registers `allTests` (KotlinTestReport) → desktopTest by default. Rewire to allTestsSequential.
afterEvaluate {
    tasks.named<KotlinTestReport>("allTests") {
        enabled = true
        group = "verification — full suite"
        description = "Alias for allTestsSequential (full suite, strictly sequential E2E/BDD)."
        setDependsOn(listOf(tasks.named("allTestsSequential")))
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