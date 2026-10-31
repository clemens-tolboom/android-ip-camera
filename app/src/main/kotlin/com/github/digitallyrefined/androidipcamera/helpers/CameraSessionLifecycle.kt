package com.github.digitallyrefined.androidipcamera.helpers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Main-dispatcher confined. The lock covers initialization AND recording attachment. */
internal class CameraSessionLifecycle(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var pending: Job? = null
    var recordingOwnsSession = false
        private set

    fun schedule(delayMs: Long = 300, restart: suspend () -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            mutex.withLock {
                // Cancellation alone is insufficient for a job already waiting for the lock.
                if (!recordingOwnsSession) restart()
            }
        }
    }

    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> startRecording(timeoutMs: Long, block: suspend () -> T): T {
        pending?.cancel()
        pending = null
        check(!recordingOwnsSession) { "Recording already in progress" }
        // Reserve before suspending for a restart already holding the lock.
        recordingOwnsSession = true
        try {
            return withTimeout(timeoutMs) { mutex.withLock { block() } }
        } catch (e: Throwable) {
            recordingOwnsSession = false
            throw e
        }
    }

    fun recordingStopped() { recordingOwnsSession = false }
}
