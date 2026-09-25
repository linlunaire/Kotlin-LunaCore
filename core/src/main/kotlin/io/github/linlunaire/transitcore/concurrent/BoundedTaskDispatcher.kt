package io.github.linlunaire.transitcore.concurrent

import java.util.Objects
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Bounds background work and completed results waiting for their owner thread.
 * A permit remains held until completion is uploaded or discarded, including
 * queued results. The owner supplies a cheap, thread-safe lifetime predicate
 * that must not throw and must never become true again after invalidation.
 *
 * Call trySchedule, uploadOne and discardStale from the owner thread. Invalidate
 * the lifetime predicate before discardStale; workers then close stale results
 * without publishing them. Upload.close releases staging storage and may run on
 * a worker. Other completion callbacks run only on the owner thread.
 *
 * The supplied executor remains caller-owned. Saturated calls do not run prepare
 * and allocate nothing; accepted calls retain their callbacks until completion.
 */
class BoundedTaskDispatcher(private val executor: Executor, maxInFlight: Int) {
    private val capacity: Semaphore
    private val completed = ConcurrentLinkedQueue<Completion>()

    init {
        require(maxInFlight > 0) { "maxInFlight must be positive" }
        capacity = Semaphore(maxInFlight)
    }

    // Prepare and completion callbacks run on the owner thread. Saturation does not run prepare.
    fun trySchedule(
        prepare: Runnable,
        build: Supplier<Upload>,
        current: BooleanSupplier,
        finished: Runnable,
        failed: Consumer<Throwable>,
    ): Boolean {
        if (!capacity.tryAcquire()) return false
        val started = AtomicBoolean()
        try {
            prepare.run()
            executor.execute {
                started.set(true)
                var upload: Upload? = null
                var error: Throwable? = null
                try {
                    if (current.asBoolean) upload = Objects.requireNonNull(build.get())
                } catch (failure: Throwable) {
                    error = failure
                }
                synchronized(completed) {
                    // Discard uses this same lock: a result cannot enter the queue after close.
                    if (current.asBoolean) {
                        completed.offer(Completion(upload, error, current, finished, failed))
                        return@execute
                    }
                }
                // Staging storage only; never upload or invoke owner-thread callbacks here.
                try {
                    upload?.close()
                } finally {
                    capacity.release()
                }
            }
        } catch (error: Throwable) {
            // An inline executor can propagate worker cleanup failure after its slot was released.
            if (started.get()) throw error
            finish(Completion(null, error, current, finished, failed))
        }
        return true
    }

    fun uploadOne(): Boolean {
        val completion = synchronized(completed) { completed.poll() } ?: return false
        finish(completion)
        return true
    }

    fun discardStale() {
        synchronized(completed) {
            var failure: Throwable? = null
            val iterator = completed.iterator()
            while (iterator.hasNext()) {
                val completion = iterator.next()
                if (!completion.current.asBoolean) {
                    iterator.remove()
                    try {
                        finish(completion)
                    } catch (error: Throwable) {
                        if (failure == null) failure = error
                        else if (failure !== error) failure.addSuppressed(error)
                    }
                }
            }
            if (failure != null) throw failure
        }
    }

    private fun finish(completion: Completion) {
        var failure = completion.error
        try {
            try {
                if (failure == null && completion.upload != null && completion.current.asBoolean) {
                    completion.upload.upload()
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                if (completion.upload != null) {
                    try {
                        completion.upload.close()
                    } catch (error: Throwable) {
                        if (failure == null) failure = error
                        else if (failure !== error) failure.addSuppressed(error)
                    }
                }
            }
            if (failure != null && completion.current.asBoolean) completion.failed.accept(failure)
        } finally {
            try {
                completion.finished.run()
            } finally {
                capacity.release()
            }
        }
    }

    fun interface Upload : AutoCloseable {
        fun upload()

        // Releases staging storage only. May run on a worker when the owner was invalidated.
        override fun close() {}
    }

    private class Completion(
        val upload: Upload?,
        val error: Throwable?,
        val current: BooleanSupplier,
        val finished: Runnable,
        val failed: Consumer<Throwable>,
    )

    companion object {
        @JvmStatic
        fun newWorkerPool(workerCount: Int, threadNamePrefix: String): ExecutorService {
            val counter = AtomicInteger()
            return Executors.newFixedThreadPool(workerCount) { task ->
                Thread(task, threadNamePrefix + counter.incrementAndGet()).apply { isDaemon = true }
            }
        }
    }
}
