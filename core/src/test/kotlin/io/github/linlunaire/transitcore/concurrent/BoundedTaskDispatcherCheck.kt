package io.github.linlunaire.transitcore.concurrent

import io.github.linlunaire.transitcore.interop.JavaInteropCheck
import java.lang.management.ManagementFactory
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the Kotlin dispatcher through its public Java-friendly interface without Minecraft. */
object BoundedTaskDispatcherCheck {
    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        checkScale()
        checkLifetime()
        checkFailures()
        checkErrorAndSuppression()
        checkCheckedThrowables()
        checkCheckedCleanup()
        checkWorkers()
        checkCloseDuringBuild()
        checkSaturationAllocation()
        println("PASS: real Kotlin core through Java SAM/default methods; 1000 jobs; at most 4 admitted including queued uploads, configurable workers; allocation-free saturation, failures and 32 close-during-build races")
    }

    private fun checkScale() {
        val worker = ManualExecutor()
        val scheduler = BoundedTaskDispatcher(worker, 4)
        var prepared = 0
        var uploaded = 0
        var discarded = 0
        var finished = 0
        var admitted = 0
        var frame = 0
        while (finished < 1000) {
            val before = admitted
            while (admitted < 1000 && scheduler.trySchedule({ prepared++ }, {
                object : BoundedTaskDispatcher.Upload {
                    override fun upload() { uploaded++ }
                    override fun close() { discarded++ }
                }
            }, { true }, { finished++ }, { throw AssertionError(it) })) admitted++
            require(admitted - finished <= 4, "In-flight cap exceeded")
            require(prepared == admitted, "Rejected work ran prepare")
            worker.runAll()
            if (admitted < 1000) require(!scheduler.trySchedule(
                { throw AssertionError("Completed work lost its slot before upload") },
                { BoundedTaskDispatcher.Upload {} }, { true }, {}, { throw AssertionError(it) }),
                "Completed work did not retain admission")
            val prior = finished
            require(scheduler.uploadOne() && finished == prior + 1, "One frame did not process exactly one result")
            require(frame < 1001 && admitted >= before, "Work did not progress")
            frame++
        }
        require(uploaded == 1000 && discarded == 1000 && !scheduler.uploadOne(), "Work was lost or duplicated")
    }

    private fun checkLifetime() {
        val worker = ManualExecutor()
        val scheduler = BoundedTaskDispatcher(worker, 1)
        val current = AtomicBoolean(true)
        var built = 0
        var uploads = 0
        var freed = 0
        val build = java.util.function.Supplier<BoundedTaskDispatcher.Upload> {
            built++
            object : BoundedTaskDispatcher.Upload {
                override fun upload() { uploads++ }
                override fun close() { freed++ }
            }
        }
        require(scheduler.trySchedule({}, build, current::get, {}, { throw AssertionError(it) }), "First build rejected")
        worker.runAll()
        current.set(false)
        scheduler.discardStale()
        require(uploads == 0 && freed == 1, "Invalidated owner uploaded or leaked its completed result")
        require(!scheduler.uploadOne(), "Closed completed result remained queued")
        current.set(true)
        scheduler.trySchedule({}, build, current::get, {}, { throw AssertionError(it) })
        current.set(false)
        scheduler.discardStale()
        worker.runAll()
        require(!scheduler.uploadOne(), "Closed queued worker still published a result")
        require(built == 1 && freed == 1, "Already invalidated owner still built a result")
        current.set(true)
        scheduler.trySchedule({}, {
            val result = build.get()
            current.set(false)
            scheduler.discardStale() // close while the worker owns staging, before publication
            result
        }, current::get, { throw AssertionError("Worker ran owner-thread finished callback after close") }, { throw AssertionError(it) })
        worker.runAll()
        require(uploads == 0 && freed == 2 && !scheduler.uploadOne(), "Close-during-build leaked or published staging")
        // Replacing an invalidated owner lets new work use the released slot.
        require(scheduler.trySchedule({}, build, { true }, {}, { throw AssertionError(it) }), "Reload did not recover admission")
        worker.runAll()
        scheduler.uploadOne()
        require(uploads == 1 && freed == 3, "Reload result was not uploaded exactly once")
    }

    private fun checkFailures() {
        repeat(6) { failure ->
            val worker = ManualExecutor()
            val scheduler = BoundedTaskDispatcher(worker, 1)
            var finished = 0
            var freed = 0
            var errors = 0
            try {
                scheduler.trySchedule({ if (failure == 0) throw IllegalStateException("prepare") }, {
                    if (failure == 1) throw IllegalStateException("build")
                    object : BoundedTaskDispatcher.Upload {
                        override fun upload() { if (failure == 2 || failure == 4) throw IllegalStateException("upload") }
                        override fun close() { freed++; if (failure == 3) throw IllegalStateException("close") }
                    }
                }, { true }, { finished++; if (failure == 5) throw IllegalStateException("finished callback") }, {
                    errors++; if (failure == 4) throw IllegalStateException("failure callback")
                })
                worker.runAll()
                scheduler.uploadOne()
            } catch (_: IllegalStateException) {
                require(failure >= 4, "Unexpected escaping failure")
            }
            require(finished == 1 && freed == if (failure >= 2) 1 else 0, "Failure lost finish/disposal callback: $failure")
            require(errors == if (failure < 5) 1 else 0, "Failure not reported exactly once")
            require(scheduler.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, {}, {}), "Failure leaked admission: $failure")
            worker.runAll()
            scheduler.uploadOne()
        }
        var rejected = 0
        val rejecting = BoundedTaskDispatcher({ throw java.util.concurrent.RejectedExecutionException() }, 1)
        repeat(3) { require(rejecting.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, { rejected++ }, {}), "Executor rejection leaked admission") }
        require(rejected == 3, "Rejected tasks did not finish")

        val inline = BoundedTaskDispatcher(Runnable::run, 1)
        val current = AtomicBoolean(true)
        try {
            inline.trySchedule({}, {
                current.set(false)
                object : BoundedTaskDispatcher.Upload {
                    override fun upload() { throw AssertionError("Stale upload") }
                    override fun close() { throw IllegalStateException("stale staging cleanup") }
                }
            }, current::get, { throw AssertionError("Stale worker callback") }, {})
            throw AssertionError("Inline cleanup failure was lost")
        } catch (_: IllegalStateException) { }
        require(inline.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, {}, {}), "Inline cleanup leaked its slot")
        require(!inline.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, {}, {}), "Inline cleanup released its slot twice")
        inline.uploadOne()
    }

    private fun checkErrorAndSuppression() {
        val worker = ManualExecutor()
        val scheduler = BoundedTaskDispatcher(worker, 1)
        val uploadFailure = AssertionError("upload")
        val closeFailure = AssertionError("staging close")
        var finished = 0
        var failed = 0
        require(scheduler.trySchedule({}, {
            object : BoundedTaskDispatcher.Upload {
                override fun upload() { throw uploadFailure }
                override fun close() { throw closeFailure }
            }
        }, { true }, { finished++ }, {
            require(it === uploadFailure && it.suppressed.size == 1 && it.suppressed[0] === closeFailure,
                "Error identity or suppressed cleanup failure was lost")
            failed++
        }), "Error probe was not admitted")
        worker.runAll()
        require(scheduler.uploadOne() && finished == 1 && failed == 1, "Error cleanup lost callbacks")
        require(scheduler.trySchedule({}, JavaInteropCheck.nullUploadSupplier(), { true }, { finished++ }, {
            require(it is NullPointerException, "Null build result did not fail at the scheduler seam")
            failed++
        }), "Error cleanup leaked admission")
        worker.runAll()
        require(scheduler.uploadOne() && finished == 2 && failed == 2, "Null result cleanup lost callbacks")
        require(scheduler.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, {}, { throw AssertionError(it) }), "Null result leaked admission")
        worker.runAll()
        scheduler.uploadOne()
    }

    private fun checkWorkers() {
        val pool = BoundedTaskDispatcher.newWorkerPool(2, "Transit core test ")
        val entered = CountDownLatch(2)
        val unblock = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val finished = AtomicInteger()
        val scheduler = BoundedTaskDispatcher(pool, 4)
        try {
            repeat(4) {
                require(scheduler.trySchedule({}, {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max)
                    entered.countDown()
                    try {
                        if (!unblock.await(5, TimeUnit.SECONDS)) throw AssertionError("Worker gate timed out")
                        BoundedTaskDispatcher.Upload {}
                    } catch (error: InterruptedException) { throw AssertionError(error) }
                    finally { active.decrementAndGet() }
                }, { true }, { finished.incrementAndGet() }, { throw AssertionError(it) }), "Four jobs not admitted")
            }
            require(entered.await(5, TimeUnit.SECONDS), "Two workers did not run")
            require(active.get() == 2 && !scheduler.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, {}, {}), "Worker or total limit exceeded")
            unblock.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (finished.get() < 4 && System.nanoTime() < deadline) {
                if (!scheduler.uploadOne()) Thread.sleep(1)
            }
            require(finished.get() == 4 && peak.get() == 2, "Worker limit or completion failed")
        } finally { unblock.countDown(); pool.shutdownNow() }
    }

    private fun checkCheckedThrowables() {
        // Kotlin can throw checked Java exceptions from any Java SAM directly.
        repeat(7) { failure ->
            val expected = java.io.IOException("checked callback $failure")
            val uploadFailure = java.io.IOException("upload before failure callback")
            val worker = ManualExecutor()
            val reject = AtomicBoolean(failure == 6)
            val scheduler = BoundedTaskDispatcher({
                if (reject.getAndSet(false)) throw expected
                worker.execute(it)
            }, 1)
            var finished = 0
            var freed = 0
            var errors = 0
            var escaped: Throwable? = null
            try {
                scheduler.trySchedule({ if (failure == 0) throw expected }, {
                    if (failure == 1) throw expected
                    object : BoundedTaskDispatcher.Upload {
                        override fun upload() {
                            if (failure == 2) throw expected
                            if (failure == 4) throw uploadFailure
                        }
                        override fun close() { freed++; if (failure == 3) throw expected }
                    }
                }, { true }, { finished++; if (failure == 5) throw expected }, {
                    errors++
                    require(it === if (failure == 4) uploadFailure else expected, "Checked error identity changed")
                    if (failure == 4) throw expected
                })
                worker.runAll()
                scheduler.uploadOne()
            } catch (error: Throwable) { escaped = error }
            require(escaped === if (failure == 4 || failure == 5) expected else null, "Checked error escaped the wrong callback: $failure")
            require(finished == 1 && freed == if (failure in 2..5) 1 else 0, "Checked failure lost cleanup: $failure")
            require(errors == if (failure == 5) 0 else 1, "Checked failure was not reported once: $failure")
            require(scheduler.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, {}, {}), "Checked failure leaked admission: $failure")
            worker.runAll()
            require(scheduler.uploadOne() && !scheduler.uploadOne(), "Checked failure left broken queue state")
        }

        val uploadFailure = java.io.IOException("checked upload")
        val closeFailure = java.io.IOException("checked staging close")
        val scheduler = BoundedTaskDispatcher(Runnable::run, 1)
        var reported = 0
        scheduler.trySchedule({}, {
            object : BoundedTaskDispatcher.Upload {
                override fun upload() { throw uploadFailure }
                override fun close() { throw closeFailure }
            }
        }, { true }, {}, {
            require(it === uploadFailure && it.suppressed.size == 1 && it.suppressed[0] === closeFailure,
                "Checked upload/close identity or suppression changed")
            reported++
        })
        require(scheduler.uploadOne() && reported == 1, "Checked suppression lost completion")
    }

    private fun checkCheckedCleanup() {
        val scheduler = BoundedTaskDispatcher(Runnable::run, 2)
        val current = AtomicBoolean(true)
        val first = java.io.IOException("first stale finished")
        val second = java.io.IOException("second stale finished")
        var closed = 0
        for (failure in arrayOf(first, second)) {
            scheduler.trySchedule({}, {
                object : BoundedTaskDispatcher.Upload {
                    override fun upload() { throw AssertionError("Stale completion uploaded") }
                    override fun close() { closed++ }
                }
            }, current::get, { throw failure }, { throw AssertionError(it) })
        }
        current.set(false)
        var escaped: Throwable? = null
        try { scheduler.discardStale() } catch (error: Throwable) { escaped = error }
        require(escaped === first && first.suppressed.size == 1 && first.suppressed[0] === second,
            "Checked stale failures were not fully drained and suppressed")
        require(closed == 2 && !scheduler.uploadOne(), "Checked stale failure stranded queued results")
        repeat(2) { require(scheduler.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, {}, {}), "Checked stale cleanup leaked a permit") }
        require(!scheduler.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, {}, {}), "Checked stale cleanup released extra permits")
        scheduler.uploadOne()
        scheduler.uploadOne()

        val inline = BoundedTaskDispatcher(Runnable::run, 1)
        val valid = AtomicBoolean(true)
        val closeFailure = java.io.IOException("inline stale close")
        escaped = null
        try {
            inline.trySchedule({}, {
                valid.set(false)
                object : BoundedTaskDispatcher.Upload {
                    override fun upload() { throw AssertionError("Stale completion uploaded") }
                    override fun close() { throw closeFailure }
                }
            }, valid::get, { throw AssertionError("Stale worker invoked callback") }, {})
        } catch (error: Throwable) { escaped = error }
        require(escaped === closeFailure, "Checked inline cleanup failure lost identity")
        require(inline.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, {}, {}), "Checked inline cleanup leaked admission")
        require(!inline.trySchedule({}, { BoundedTaskDispatcher.Upload {} }, { true }, {}, {}), "Checked inline cleanup released twice")
        inline.uploadOne()
    }

    private fun checkCloseDuringBuild() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val scheduler = BoundedTaskDispatcher(pool, 1)
        try {
            repeat(32) {
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val current = AtomicBoolean(true)
                val freed = AtomicInteger()
                require(scheduler.trySchedule({}, {
                    started.countDown()
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw AssertionError("Build did not resume")
                    } catch (error: InterruptedException) { throw AssertionError(error) }
                    object : BoundedTaskDispatcher.Upload {
                        override fun upload() { throw AssertionError("Closed build was uploaded") }
                        override fun close() { freed.incrementAndGet() }
                    }
                }, current::get, { throw AssertionError("Closed worker invoked owner-thread callback") }, { throw AssertionError(it) }), "Closed build leaked its slot")
                require(started.await(5, TimeUnit.SECONDS), "Worker never entered build")
                current.set(false)
                scheduler.discardStale()
                release.countDown()
                // Same executor barrier: the publication/disposal finally block has completed.
                pool.submit(Runnable {}).get(5, TimeUnit.SECONDS)
                require(freed.get() == 1 && !scheduler.uploadOne(), "Close during build retained staging without another draw")
            }
        } finally { pool.shutdownNow() }
    }

    private fun checkSaturationAllocation() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        require(bean.isThreadAllocatedMemorySupported, "Allocation accounting unavailable on this JVM")
        bean.isThreadAllocatedMemoryEnabled = true
        val worker = ManualExecutor()
        val scheduler = BoundedTaskDispatcher(worker, 1)
        val noOp = Runnable {}
        val build = java.util.function.Supplier { BoundedTaskDispatcher.Upload {} }
        val current = java.util.function.BooleanSupplier { true }
        val failed = java.util.function.Consumer<Throwable> { throw AssertionError(it) }
        require(scheduler.trySchedule(noOp, build, current, noOp, failed), "Initial allocation probe was not admitted")
        worker.runAll() // Completed work must continue to hold admission until uploadOne.
        repeat(1_000_000) { require(!scheduler.trySchedule(noOp, build, current, noOp, failed), "Saturated scheduler admitted work") }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(1_000_000) { require(!scheduler.trySchedule(noOp, build, current, noOp, failed), "Saturated scheduler admitted work") }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        require(allocated < 16384, "Saturated Kotlin scheduler introduced per-call allocation: $allocated bytes")
        println("SCHEDULER_ALLOCATION: $allocated bytes for 1000000 saturated submissions")
        require(scheduler.uploadOne() && !scheduler.uploadOne(), "Allocation probe lost completed work")
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(task: Runnable) { tasks.add(task) }
        fun runAll() { while (!tasks.isEmpty()) tasks.remove().run() }
    }

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
