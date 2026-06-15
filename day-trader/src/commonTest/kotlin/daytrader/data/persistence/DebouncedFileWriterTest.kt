package daytrader.data.persistence

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DebouncedFileWriterTest {
    @Test
    fun schedule_eventuallyPersistsAfterQuiescence() = runTest {
        val persisted = mutableListOf<Int>()
        val writer = DebouncedFileWriter<Int>(this) { persisted += it }
        writer.schedule(1)
        testScheduler.advanceTimeBy(DebouncedFileWriter.SAVE_DEBOUNCE_MS + 50)
        assertEquals(listOf(1), persisted)
    }

    @Test
    fun persistNow_writesImmediatelyEvenWhenDebounced() = runTest {
        val persisted = mutableListOf<Int>()
        val writer = DebouncedFileWriter<Int>(this) { persisted += it }
        writer.schedule(1)
        writer.persistNow(2)
        testScheduler.advanceTimeBy(DebouncedFileWriter.SAVE_DEBOUNCE_MS + 50)
        assertEquals(listOf(2), persisted)
    }
}
