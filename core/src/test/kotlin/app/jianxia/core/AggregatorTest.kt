package app.jianxia.core

import app.jianxia.core.aggregate.ParallelAggregator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AggregatorTest {
    @Test
    fun slowSourceTimesOutWithoutDroppingFastSource() = runBlocking {
        val outcome = ParallelAggregator.collect(
            timeoutMs = 200,
            blocks = listOf(
                { delay(5_000); listOf("slow") },
                { listOf("fast") },
                { error("down") },
            ),
        )
        assertEquals(listOf("fast"), outcome.items)
        assertEquals(1, outcome.successCount)
        assertEquals(1, outcome.failureCount)
        assertEquals(1, outcome.timedOutCount)
    }
}
