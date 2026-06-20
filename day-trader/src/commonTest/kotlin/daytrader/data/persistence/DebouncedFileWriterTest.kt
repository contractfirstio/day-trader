package daytrader.data.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DebouncedFileWriterTest {
    @Test
    fun schedule_eventuallyPersistsAfterQuiescence() = runTest {
        val persisted = mutableListOf<Int>()
        val writer = DebouncedFileWriter<Int>(backgroundScope) { persisted += it }
        writer.schedule(1)
        testScheduler.advanceTimeBy(DebouncedFileWriter.SAVE_DEBOUNCE_MS + 50)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1), persisted)
    }

    @Test
    fun persistNow_writesImmediatelyEvenWhenDebounced() = runTest {
        val persisted = mutableListOf<Int>()
        val writer = DebouncedFileWriter<Int>(backgroundScope) { persisted += it }
        writer.schedule(1)
        writer.persistNow(2)
        testScheduler.advanceTimeBy(DebouncedFileWriter.SAVE_DEBOUNCE_MS + 50)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(2), persisted)
    }

    @Test
    fun flush_enqueuesImmediatelyWithoutBlocking() = runBlocking {
        val persisted = mutableListOf<Int>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writer = DebouncedFileWriter<Int>(scope) { persisted += it }
            writer.schedule(1)
            writer.flush(2)
            writer.awaitIdleBlocking()
            assertEquals(listOf(2), persisted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun flushBlocking_writesBeforeReturning() = runBlocking {
        val persisted = mutableListOf<Int>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writer = DebouncedFileWriter<Int>(scope) { persisted += it }
            writer.flushBlocking(2)
            assertEquals(listOf(2), persisted)
        } finally {
            scope.cancel()
        }
    }
}
