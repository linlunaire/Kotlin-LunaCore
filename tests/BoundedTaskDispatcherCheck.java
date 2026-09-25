package io.github.linlunaire.transitcore.concurrent;


import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the Kotlin dispatcher through its public Java interface without Minecraft. */
public final class BoundedTaskDispatcherCheck {
    public static void main(String[] args) throws Exception {
        checkScale();
        checkLifetime();
        checkFailures();
        checkErrorAndSuppression();
        checkCheckedThrowables();
        checkCheckedCleanup();
        checkWorkers();
        checkCloseDuringBuild();
        checkJavaInterface();
        checkSaturationAllocation();
        System.out.println("PASS: real Kotlin core through Java SAM/default methods; 1000 jobs; at most 4 admitted including queued uploads, configurable workers; allocation-free saturation, failures and 32 close-during-build races");
    }

    private static void checkScale() {
        ManualExecutor worker = new ManualExecutor();
        BoundedTaskDispatcher scheduler = new BoundedTaskDispatcher(worker, 4);
        int[] prepared = {0}, uploaded = {0}, discarded = {0}, finished = {0};
        int admitted = 0;
        for (int frame = 0; finished[0] < 1000; frame++) {
            int before = admitted;
            while (admitted < 1000 && scheduler.trySchedule(() -> prepared[0]++, () -> new BoundedTaskDispatcher.Upload() {
                @Override public void upload() { uploaded[0]++; }
                @Override public void close() { discarded[0]++; }
            }, () -> true, () -> finished[0]++, error -> { throw new AssertionError(error); })) admitted++;
            require(admitted - finished[0] <= 4, "In-flight cap exceeded");
            require(prepared[0] == admitted, "Rejected work ran prepare");
            worker.runAll();
            if (admitted < 1000) require(!scheduler.trySchedule(() -> { throw new AssertionError("Completed work lost its slot before upload"); }, () -> () -> {}, () -> true, () -> {}, error -> { throw new AssertionError(error); }), "Completed work did not retain admission");
            int prior = finished[0];
            require(scheduler.uploadOne() && finished[0] == prior + 1, "One frame did not process exactly one result");
            require(frame < 1001 && admitted >= before, "Work did not progress");
        }
        require(uploaded[0] == 1000 && discarded[0] == 1000 && !scheduler.uploadOne(), "Work was lost or duplicated");
    }

    private static void checkLifetime() {
        ManualExecutor worker = new ManualExecutor();
        BoundedTaskDispatcher scheduler = new BoundedTaskDispatcher(worker, 1);
        AtomicBoolean current = new AtomicBoolean(true);
        int[] built = {0}, uploads = {0}, freed = {0};
        java.util.function.Supplier<BoundedTaskDispatcher.Upload> build = () -> {
            built[0]++;
            return new BoundedTaskDispatcher.Upload() {
                @Override public void upload() { uploads[0]++; }
                @Override public void close() { freed[0]++; }
            };
        };
        require(scheduler.trySchedule(() -> {}, build, current::get, () -> {}, error -> { throw new AssertionError(error); }), "First build rejected");
        worker.runAll();
        current.set(false);
        scheduler.discardStale();
        require(uploads[0] == 0 && freed[0] == 1, "Invalidated owner uploaded or leaked its completed result");
        require(!scheduler.uploadOne(), "Closed completed result remained queued");
        current.set(true);
        scheduler.trySchedule(() -> {}, build, current::get, () -> {}, error -> { throw new AssertionError(error); });
        current.set(false);
        scheduler.discardStale();
        worker.runAll();
        require(!scheduler.uploadOne(), "Closed queued worker still published a result");
        require(built[0] == 1 && freed[0] == 1, "Already invalidated owner still built a result");
        current.set(true);
        scheduler.trySchedule(() -> {}, () -> {
            BoundedTaskDispatcher.Upload result = build.get();
            current.set(false);
            scheduler.discardStale(); // close while the worker owns staging, before publication
            return result;
        }, current::get, () -> { throw new AssertionError("Worker ran owner-thread finished callback after close"); }, error -> { throw new AssertionError(error); });
        worker.runAll();
        require(uploads[0] == 0 && freed[0] == 2 && !scheduler.uploadOne(), "Close-during-build leaked or published staging");
        // Replacing an invalidated owner lets new work use the released slot.
        require(scheduler.trySchedule(() -> {}, build, () -> true, () -> {}, error -> { throw new AssertionError(error); }), "Reload did not recover admission");
        worker.runAll();
        scheduler.uploadOne();
        require(uploads[0] == 1 && freed[0] == 3, "Reload result was not uploaded exactly once");
    }

    private static void checkFailures() {
        for (int failureAt = 0; failureAt < 6; failureAt++) {
            final int failure = failureAt;
            ManualExecutor worker = new ManualExecutor();
            BoundedTaskDispatcher scheduler = new BoundedTaskDispatcher(worker, 1);
            int[] finished = {0}, freed = {0}, errors = {0};
            try {
                scheduler.trySchedule(() -> { if (failure == 0) throw new IllegalStateException("prepare"); }, () -> {
                    if (failure == 1) throw new IllegalStateException("build");
                    return new BoundedTaskDispatcher.Upload() {
                        @Override public void upload() { if (failure == 2 || failure == 4) throw new IllegalStateException("upload"); }
                        @Override public void close() { freed[0]++; if (failure == 3) throw new IllegalStateException("close"); }
                    };
                }, () -> true, () -> { finished[0]++; if (failure == 5) throw new IllegalStateException("finished callback"); }, error -> {
                    errors[0]++; if (failure == 4) throw new IllegalStateException("failure callback");
                });
                worker.runAll();
                scheduler.uploadOne();
            } catch (IllegalStateException expected) {
                require(failure >= 4, "Unexpected escaping failure");
            }
            require(finished[0] == 1 && freed[0] == (failure >= 2 ? 1 : 0), "Failure lost finish/disposal callback: " + failure);
            require(errors[0] == (failure < 5 ? 1 : 0), "Failure not reported exactly once");
            require(scheduler.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> {}, error -> {}), "Failure leaked admission: " + failure);
            worker.runAll();
            scheduler.uploadOne();
        }
        int[] rejected = {0};
        BoundedTaskDispatcher rejecting = new BoundedTaskDispatcher(task -> { throw new java.util.concurrent.RejectedExecutionException(); }, 1);
        for (int i = 0; i < 3; i++) require(rejecting.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> rejected[0]++, error -> {}), "Executor rejection leaked admission");
        require(rejected[0] == 3, "Rejected tasks did not finish");

        BoundedTaskDispatcher inline = new BoundedTaskDispatcher(Runnable::run, 1);
        AtomicBoolean current = new AtomicBoolean(true);
        try {
            inline.trySchedule(() -> {}, () -> {
                current.set(false);
                return new BoundedTaskDispatcher.Upload() {
                    @Override public void upload() { throw new AssertionError("Stale upload"); }
                    @Override public void close() { throw new IllegalStateException("stale staging cleanup"); }
                };
            }, current::get, () -> { throw new AssertionError("Stale worker callback"); }, error -> {});
            throw new AssertionError("Inline cleanup failure was lost");
        } catch (IllegalStateException expected) {}
        require(inline.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> {}, error -> {}), "Inline cleanup leaked its slot");
        require(!inline.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> {}, error -> {}), "Inline cleanup released its slot twice");
        inline.uploadOne();
    }

    private static void checkErrorAndSuppression() {
        ManualExecutor worker = new ManualExecutor();
        BoundedTaskDispatcher scheduler = new BoundedTaskDispatcher(worker, 1);
        AssertionError uploadFailure = new AssertionError("upload");
        AssertionError closeFailure = new AssertionError("staging close");
        int[] finished = {0}, failed = {0};
        require(scheduler.trySchedule(() -> {}, () -> new BoundedTaskDispatcher.Upload() {
            @Override public void upload() { throw uploadFailure; }
            @Override public void close() { throw closeFailure; }
        }, () -> true, () -> finished[0]++, error -> {
            require(error == uploadFailure && error.getSuppressed().length == 1 && error.getSuppressed()[0] == closeFailure,
                    "Error identity or suppressed cleanup failure was lost");
            failed[0]++;
        }), "Error probe was not admitted");
        worker.runAll();
        require(scheduler.uploadOne() && finished[0] == 1 && failed[0] == 1, "Error cleanup lost callbacks");
        require(scheduler.trySchedule(() -> {}, () -> null, () -> true, () -> finished[0]++, error -> {
            require(error instanceof NullPointerException, "Null build result did not fail at the scheduler seam");
            failed[0]++;
        }), "Error cleanup leaked admission");
        worker.runAll();
        require(scheduler.uploadOne() && finished[0] == 2 && failed[0] == 2, "Null result cleanup lost callbacks");
        require(scheduler.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> {}, error -> { throw new AssertionError(error); }), "Null result leaked admission");
        worker.runAll();
        scheduler.uploadOne();
    }

    private static void checkWorkers() throws Exception {
        var pool = BoundedTaskDispatcher.newWorkerPool(2, "Transit core test ");
        CountDownLatch entered = new CountDownLatch(2), unblock = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), finished = new AtomicInteger();
        BoundedTaskDispatcher scheduler = new BoundedTaskDispatcher(pool, 4);
        try {
            for (int i = 0; i < 4; i++) require(scheduler.trySchedule(() -> {}, () -> {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                entered.countDown();
                try {
                    if (!unblock.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker gate timed out");
                    return () -> {};
                } catch (InterruptedException error) { throw new AssertionError(error); }
                finally { active.decrementAndGet(); }
            }, () -> true, finished::incrementAndGet, error -> { throw new AssertionError(error); }), "Four jobs not admitted");
            require(entered.await(5, TimeUnit.SECONDS), "Two workers did not run");
            require(active.get() == 2 && !scheduler.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> {}, error -> {}), "Worker or total limit exceeded");
            unblock.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (finished.get() < 4 && System.nanoTime() < deadline) {
                if (!scheduler.uploadOne()) Thread.sleep(1);
            }
            require(finished.get() == 4 && peak.get() == 2, "Worker limit or completion failed");
        } finally { unblock.countDown(); pool.shutdownNow(); }
    }

    private static void checkCheckedThrowables() {
        for (int failureAt = 0; failureAt < 7; failureAt++) {
            final int failure = failureAt;
            var expected = new java.io.IOException("checked callback " + failure);
            var uploadFailure = new java.io.IOException("upload before failure callback");
            ManualExecutor worker = new ManualExecutor();
            AtomicBoolean reject = new AtomicBoolean(failure == 6);
            BoundedTaskDispatcher scheduler = new BoundedTaskDispatcher(task -> {
                if (reject.getAndSet(false)) sneakyThrow(expected);
                worker.execute(task);
            }, 1);
            int[] finished = {0}, freed = {0}, errors = {0};
            Throwable escaped = null;
            try {
                scheduler.trySchedule(() -> { if (failure == 0) sneakyThrow(expected); }, () -> {
                    if (failure == 1) sneakyThrow(expected);
                    return new BoundedTaskDispatcher.Upload() {
                        @Override public void upload() {
                            if (failure == 2) sneakyThrow(expected);
                            if (failure == 4) sneakyThrow(uploadFailure);
                        }
                        @Override public void close() { freed[0]++; if (failure == 3) sneakyThrow(expected); }
                    };
                }, () -> true, () -> { finished[0]++; if (failure == 5) sneakyThrow(expected); }, error -> {
                    errors[0]++;
                    require(error == (failure == 4 ? uploadFailure : expected), "Checked error identity changed");
                    if (failure == 4) sneakyThrow(expected);
                });
                worker.runAll();
                scheduler.uploadOne();
            } catch (Throwable error) { escaped = error; }
            require(escaped == (failure == 4 || failure == 5 ? expected : null), "Checked error escaped the wrong callback: " + failure);
            require(finished[0] == 1 && freed[0] == (failure >= 2 && failure <= 5 ? 1 : 0), "Checked failure lost cleanup: " + failure);
            require(errors[0] == (failure == 5 ? 0 : 1), "Checked failure was not reported once: " + failure);
            require(scheduler.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> {}, error -> {}), "Checked failure leaked admission: " + failure);
            worker.runAll();
            require(scheduler.uploadOne() && !scheduler.uploadOne(), "Checked failure left broken queue state");
        }

        var uploadFailure = new java.io.IOException("checked upload");
        var closeFailure = new java.io.IOException("checked staging close");
        BoundedTaskDispatcher scheduler = new BoundedTaskDispatcher(Runnable::run, 1);
        int[] reported = {0};
        scheduler.trySchedule(() -> {}, () -> new BoundedTaskDispatcher.Upload() {
            @Override public void upload() { sneakyThrow(uploadFailure); }
            @Override public void close() { sneakyThrow(closeFailure); }
        }, () -> true, () -> {}, error -> {
            require(error == uploadFailure && error.getSuppressed().length == 1 && error.getSuppressed()[0] == closeFailure,
                    "Checked upload/close identity or suppression changed");
            reported[0]++;
        });
        require(scheduler.uploadOne() && reported[0] == 1, "Checked suppression lost completion");
    }

    private static void checkCheckedCleanup() {
        BoundedTaskDispatcher scheduler = new BoundedTaskDispatcher(Runnable::run, 2);
        AtomicBoolean current = new AtomicBoolean(true);
        var first = new java.io.IOException("first stale finished");
        var second = new java.io.IOException("second stale finished");
        int[] closed = {0};
        for (var failure : new java.io.IOException[]{first, second}) {
            scheduler.trySchedule(() -> {}, () -> new BoundedTaskDispatcher.Upload() {
                @Override public void upload() { throw new AssertionError("Stale completion uploaded"); }
                @Override public void close() { closed[0]++; }
            }, current::get, () -> sneakyThrow(failure), error -> { throw new AssertionError(error); });
        }
        current.set(false);
        Throwable escaped = null;
        try { scheduler.discardStale(); } catch (Throwable error) { escaped = error; }
        require(escaped == first && first.getSuppressed().length == 1 && first.getSuppressed()[0] == second,
                "Checked stale failures were not fully drained and suppressed");
        require(closed[0] == 2 && !scheduler.uploadOne(), "Checked stale failure stranded queued results");
        for (int i = 0; i < 2; i++) require(scheduler.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> {}, error -> {}), "Checked stale cleanup leaked a permit");
        require(!scheduler.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> {}, error -> {}), "Checked stale cleanup released extra permits");
        scheduler.uploadOne(); scheduler.uploadOne();

        BoundedTaskDispatcher inline = new BoundedTaskDispatcher(Runnable::run, 1);
        AtomicBoolean valid = new AtomicBoolean(true);
        var closeFailure = new java.io.IOException("inline stale close");
        escaped = null;
        try {
            inline.trySchedule(() -> {}, () -> {
                valid.set(false);
                return new BoundedTaskDispatcher.Upload() {
                    @Override public void upload() { throw new AssertionError("Stale completion uploaded"); }
                    @Override public void close() { sneakyThrow(closeFailure); }
                };
            }, valid::get, () -> { throw new AssertionError("Stale worker invoked callback"); }, error -> {});
        } catch (Throwable error) { escaped = error; }
        require(escaped == closeFailure, "Checked inline cleanup failure lost identity");
        require(inline.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> {}, error -> {}), "Checked inline cleanup leaked admission");
        require(!inline.trySchedule(() -> {}, () -> () -> {}, () -> true, () -> {}, error -> {}), "Checked inline cleanup released twice");
        inline.uploadOne();
    }

    // Kotlin may throw checked Java exceptions from any Java SAM without a throws declaration.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable error) throws E { throw (E) error; }

    private static void checkCloseDuringBuild() throws Exception {
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        BoundedTaskDispatcher scheduler = new BoundedTaskDispatcher(pool, 1);
        try {
            for (int iteration = 0; iteration < 32; iteration++) {
                CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
                AtomicBoolean current = new AtomicBoolean(true);
                AtomicInteger freed = new AtomicInteger();
                require(scheduler.trySchedule(() -> {}, () -> {
                    started.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Build did not resume");
                    } catch (InterruptedException error) { throw new AssertionError(error); }
                    return new BoundedTaskDispatcher.Upload() {
                        @Override public void upload() { throw new AssertionError("Closed build was uploaded"); }
                        @Override public void close() { freed.incrementAndGet(); }
                    };
                }, current::get, () -> { throw new AssertionError("Closed worker invoked owner-thread callback"); }, error -> { throw new AssertionError(error); }), "Closed build leaked its slot");
                require(started.await(5, TimeUnit.SECONDS), "Worker never entered build");
                current.set(false);
                scheduler.discardStale();
                release.countDown();
                // Same executor barrier: the publication/disposal finally block has completed.
                pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
                require(freed.get() == 1 && !scheduler.uploadOne(), "Close during build retained staging without another draw");
            }
        } finally { pool.shutdownNow(); }
    }

    private static void checkJavaInterface() throws Exception {
        require(java.lang.reflect.Modifier.isStatic(BoundedTaskDispatcher.class.getMethod("newWorkerPool", int.class, String.class).getModifiers()), "Worker factory is not Java-static");
        require(BoundedTaskDispatcher.Upload.class.getMethod("close").isDefault(), "Upload.close is not a JVM default method");
        BoundedTaskDispatcher.Upload upload = () -> {};
        upload.close();
        require(BoundedTaskDispatcher.class.getMethod("trySchedule", Runnable.class, java.util.function.Supplier.class,
                java.util.function.BooleanSupplier.class, Runnable.class, java.util.function.Consumer.class).getReturnType() == boolean.class,
                "Java interface introduced Kotlin function types or a boxed result");
    }

    private static void checkSaturationAllocation() {
        final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        require(bean.isThreadAllocatedMemorySupported(), "Allocation accounting unavailable on this JVM");
        bean.setThreadAllocatedMemoryEnabled(true);
        ManualExecutor worker = new ManualExecutor();
        BoundedTaskDispatcher scheduler = new BoundedTaskDispatcher(worker, 1);
        Runnable noOp = () -> {};
        java.util.function.Supplier<BoundedTaskDispatcher.Upload> build = () -> () -> {};
        java.util.function.BooleanSupplier current = () -> true;
        java.util.function.Consumer<Throwable> failed = error -> { throw new AssertionError(error); };
        require(scheduler.trySchedule(noOp, build, current, noOp, failed), "Initial allocation probe was not admitted");
        worker.runAll(); // Completed work must continue to hold admission until uploadOne.
        for (int index = 0; index < 1_000_000; index++) require(!scheduler.trySchedule(noOp, build, current, noOp, failed), "Saturated scheduler admitted work");
        final long thread = Thread.currentThread().threadId();
        final long before = bean.getThreadAllocatedBytes(thread);
        for (int index = 0; index < 1_000_000; index++) require(!scheduler.trySchedule(noOp, build, current, noOp, failed), "Saturated scheduler admitted work");
        final long allocated = bean.getThreadAllocatedBytes(thread) - before;
        require(allocated < 16384, "Saturated Kotlin scheduler introduced per-call allocation: " + allocated + " bytes");
        System.out.println("SCHEDULER_ALLOCATION: " + allocated + " bytes for 1000000 saturated submissions");
        require(scheduler.uploadOne() && !scheduler.uploadOne(), "Allocation probe lost completed work");
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
