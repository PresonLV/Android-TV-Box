package app.jianxia.core.aggregate

import app.jianxia.core.model.AggregateOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

object ParallelAggregator {
    suspend fun <T> collect(
        timeoutMs: Long,
        blocks: List<suspend () -> List<T>>,
    ): AggregateOutcome<T> {
        if (blocks.isEmpty()) {
            return AggregateOutcome(emptyList(), 0, 0, 0)
        }
        val slots = coroutineScope {
            blocks.map { block ->
                async {
                    try {
                        Slot.Ok(withTimeout(timeoutMs) { block() })
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        Slot.TimedOut
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        Slot.Failed
                    }
                }
            }.awaitAll()
        }
        val items = slots.flatMap { slot -> if (slot is Slot.Ok) slot.values else emptyList() }
        return AggregateOutcome(
            items = items,
            successCount = slots.count { it is Slot.Ok },
            failureCount = slots.count { it is Slot.Failed },
            timedOutCount = slots.count { it is Slot.TimedOut },
        )
    }

    private sealed interface Slot<out T> {
        data class Ok<T>(val values: List<T>) : Slot<T>
        data object TimedOut : Slot<Nothing>
        data object Failed : Slot<Nothing>
    }
}
