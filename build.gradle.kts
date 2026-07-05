plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/** Shortcuts from the repo root — each delegates to :day-trader. */
listOf(
    // Unit
    "unitTest",
    "desktopTest",
    "coverageUnit",
    // Per-mode E2E (BDD + programmatic)
    "e2eEmulator",
    "e2ePaper",
    "e2eIb",
    "e2eReplay",
    "e2eTest",
    "e2eProgrammaticAll",
    // Full suite — prefer the descriptive names (see printTestSuites)
    "allTestsSequential",
    "allTestsParallel",
    "allTestsParallelModes",
    "allTests",
    "fastAllTests",
    "fullTestSuite",
    "test",
    "printTestSuites",
    // BDD
    "bddAll",
    "bddEmulator",
    "bddEmulatorShard1",
    "bddEmulatorShard2",
    "bddEmulatorWip",
    "bddPaper",
    "bddIb",
    "bddIbShard1",
    "bddIbShard2",
    "bddIbShard3",
    "bddIbWip",
    "bddIbMonolith",
    "bddReplay",
    // Programmatic E2E only
    "e2eEmulatorTests",
    "e2ePaperTests",
    "e2eIbTests",
    "e2eReplayTests",
    // Stress
    "unitTestStress10",
    "e2eEmulatorStress10",
    "e2ePaperStress10",
    "e2eIbModeStress10",
    "e2eReplayStress10",
    "allTestsSequentialStress10",
    "allTestsStress10",
    "allTestsParallelStress3",
    "fastAllTestsStress3",
    // Coverage
    "coverageBdd",
    "coverageBddEmulator",
    "coverageBddEmulatorWip",
    "coverageBddPaper",
    "coverageBddIb",
    "coverageBddIbWip",
    "coverageBddReplay",
    "koverHtmlReport",
    "koverXmlReport",
    "koverVerify",
).forEach { taskName ->
    tasks.register(taskName) {
        group = if (taskName == "printTestSuites") "help" else "verification"
        dependsOn(project(":day-trader").tasks.named(taskName))
    }
}
