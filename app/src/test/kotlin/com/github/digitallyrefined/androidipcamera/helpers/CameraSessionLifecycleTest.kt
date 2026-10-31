package com.github.digitallyrefined.androidipcamera.helpers

import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(InternalCoroutinesApi::class)
class CameraSessionLifecycleTest {
    /** Virtual time, including withTimeout; no threads, Android, or wall-clock sleeps. */
    private class Scheduler : CoroutineDispatcher(), Delay {
        private data class Task(val at: Long, val order: Long, val run: Runnable) : Comparable<Task> {
            override fun compareTo(other: Task) = compareValuesBy(this, other, Task::at, Task::order)
        }
        var now = 0L
        private var sequence = 0L
        private val tasks = PriorityQueue<Task>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { enqueue(0, block) }
        private fun enqueue(ms: Long, block: Runnable): Task = Task(now + ms, sequence++, block).also { tasks.add(it) }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val task = enqueue(timeMillis, Runnable { if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) })
            continuation.invokeOnCancellation { tasks.remove(task) }
        }
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            val task = enqueue(timeMillis, block)
            return object : DisposableHandle { override fun dispose() { tasks.remove(task) } }
        }
        fun advance(ms: Long = 0) {
            val end = now + ms
            while (tasks.isNotEmpty() && tasks.peek()!!.at <= end) {
                val task = tasks.remove()
                now = task.at
                task.run.run()
            }
            now = end
        }
    }

    @Test fun `immediate recording cancels 300ms settings restart`() {
        val time = Scheduler()
        val scope = CoroutineScope(time + Job())
        val session = CameraSessionLifecycle(scope)
        var restarts = 0
        session.schedule { restarts++ }
        time.advance(299)
        scope.launch { session.startRecording(30_000) { delay(8_000) } }
        time.advance(8_001)
        assertEquals(0, restarts)
        assertTrue(session.recordingOwnsSession)
        scope.cancel()
    }

    @Test fun `restart requested during startup cannot truncate recording`() {
        val time = Scheduler()
        val scope = CoroutineScope(time + Job())
        val session = CameraSessionLifecycle(scope)
        var restarts = 0
        scope.launch { session.startRecording(30_000) { delay(8_000) } }
        time.advance()
        session.schedule { restarts++ }
        time.advance(9_000)
        assertEquals(0, restarts)
        session.recordingStopped()
        session.schedule { restarts++ }
        time.advance(300)
        assertEquals(1, restarts)
        scope.cancel()
    }

    @Test fun `timeout cancels startup and cannot attach late`() {
        val time = Scheduler()
        val scope = CoroutineScope(time + Job())
        val session = CameraSessionLifecycle(scope)
        var attached = false
        var timedOut = false
        scope.launch {
            try { session.startRecording(30_000) { delay(31_000); attached = true } }
            catch (_: TimeoutCancellationException) { timedOut = true }
        }
        time.advance(40_000)
        assertTrue(timedOut)
        assertFalse(attached)
        assertFalse(session.recordingOwnsSession)
        scope.cancel()
    }

    @Test fun `startup awaits in flight restart before attachment`() {
        val time = Scheduler()
        val scope = CoroutineScope(time + Job())
        val session = CameraSessionLifecycle(scope)
        val events = mutableListOf<String>()
        scope.launch { session.serialized { events.add("restart"); delay(8_000); events.add("ready") } }
        time.advance()
        scope.launch { session.startRecording(30_000) { events.add("attach") } }
        time.advance(3_000)
        assertEquals(listOf("restart"), events)
        time.advance(5_000)
        assertEquals(listOf("restart", "ready", "attach"), events)
        scope.cancel()
    }

    @Test fun `failed startup releases ownership and permits retry`() {
        val time = Scheduler()
        val scope = CoroutineScope(time + Job())
        val session = CameraSessionLifecycle(scope)
        scope.launch {
            try { session.startRecording(30_000) { error("bind failed") } }
            catch (_: IllegalStateException) { }
        }
        time.advance()
        assertFalse(session.recordingOwnsSession)
        scope.launch { session.startRecording(30_000) { } }
        time.advance()
        assertTrue(session.recordingOwnsSession)
        scope.cancel()
    }

    @Test fun `duplicate start cannot replace an attached recording`() {
        val time = Scheduler()
        val scope = CoroutineScope(time + Job())
        val session = CameraSessionLifecycle(scope)
        var attachments = 0
        var rejected = false
        scope.launch { session.startRecording(30_000) { attachments++ } }
        scope.launch {
            try { session.startRecording(30_000) { attachments++ } }
            catch (_: IllegalStateException) { rejected = true }
        }
        time.advance()
        assertTrue(rejected)
        assertEquals(1, attachments)
        assertTrue(session.recordingOwnsSession)
        scope.cancel()
    }
}
