plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/** Shortcuts from the repo root — each delegates to :day-trader. */
listOf(
    "unitTest",
    "bddAll",
    "bddEmulator",
    "bddPaper",
    "bddIb",
    "bddIbWip",
    "bddReplay",
    "e2eProgrammaticAll",
    "e2eEmulatorTests",
    "e2ePaperTests",
    "e2eIbTests",
    "e2eReplayTests",
    "e2eEmulator",
    "e2ePaper",
    "e2eIb",
    "e2eReplay",
    "e2eTest",
    "allTests",
    "fullTestSuite",
    "test",
    "desktopTest",
    "unitTestStress10",
    "e2eEmulatorStress10",
    "e2ePaperStress10",
    "e2eIbModeStress10",
    "e2eReplayStress10",
    "allTestsStress10",
).forEach { taskName ->
    tasks.register(taskName) {
        group = "verification"
        val dayTraderTask = if (taskName == "allTests") "allTests" else taskName
        dependsOn(project(":day-trader").tasks.named(dayTraderTask))
    }
}
