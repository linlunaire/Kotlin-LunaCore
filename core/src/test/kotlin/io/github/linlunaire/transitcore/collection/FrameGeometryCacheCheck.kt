package io.github.linlunaire.transitcore.collection

import java.lang.management.ManagementFactory
import java.util.ArrayList
import java.util.function.Function

/** Exercises the real renderer cache policy without allocating native/GPU buffers. */
object FrameGeometryCacheCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        activeSceneDoesNotThrash()
        largeWorkingSetDoesNotThrash()
        inactiveEntriesRetireInAccessOrder()
        idleEntriesExpire()
        geometryUsesIdentity()
        failedUploadDoesNotPoisonCache()
        failedDisposalDoesNotLeakRemainingBuffers()
        allThrowableDisposalDrainsWithoutSelfSuppression()
        cachedFramesDoNotAllocate()
        println("PASS: Kotlin cache interface, oversized active-scene reuse, 4096 meshes over 200 frames, inactive LRU retirement, idle expiry, identity keys, upload failure, complete/idempotent disposal, allocation-free cached frames (no GPU/runtime claim)")
    }

    private fun activeSceneDoesNotThrash() {
        val allocated = ArrayList<Buffer>()
        val cache = FrameGeometryCache<Any, Buffer>(256, 120, { it.bytes }, Buffer::close)
        val geometry = Array(4) { Any() }
        repeat(10) {
            cache.beginFrame()
            for (key in geometry) {
                val buffer = cache.get(key) { allocate(allocated, 80) }
                require(!buffer.closed, "Prepared draw references a closed buffer")
            }
            cache.finishFrame()
        }
        require(allocated.size == geometry.size,
            "Active scene over budget repeatedly uploads meshes: expected " + geometry.size + " allocations, got " + allocated.size)
        cache.close()
        cache.close()
        allocated.forEach { require(it.closed, "Buffer leaked on resource reload") }
    }

    private fun largeWorkingSetDoesNotThrash() {
        val allocated = ArrayList<Buffer>()
        val cache = FrameGeometryCache<Any, Buffer>(1024, 120, { it.bytes }, Buffer::close)
        val geometry = Array(4096) { Any() }
        repeat(200) {
            cache.beginFrame()
            for (key in geometry) require(!cache.get(key) { allocate(allocated, 80) }.closed, "Active mesh retired")
            cache.finishFrame()
        }
        require(allocated.size == geometry.size, "Large stable scene re-uploaded geometry")
        cache.beginFrame()
        val replacement = cache.get(Any()) { allocate(allocated, 2048) }
        allocated.forEach { require(!it.closed, "Preparation prematurely disposed a buffer") }
        cache.finishFrame()
        for (index in geometry.indices) require(allocated[index].closed, "Invisible large scene leaked beyond the soft budget")
        require(!replacement.closed, "Replacement active mesh was evicted")
        cache.close()
    }

    private fun inactiveEntriesRetireInAccessOrder() {
        val cache = FrameGeometryCache<Any, Buffer>(20, 120, { it.bytes }, Buffer::close)
        val a = Any()
        val b = Any()
        val c = Any()
        cache.beginFrame()
        val first = cache.get(a) { Buffer(10) }
        val second = cache.get(b) { Buffer(10) }
        val third = cache.get(c) { Buffer(10) }
        cache.finishFrame()
        cache.beginFrame()
        require(cache.get(a) { throw AssertionError("Warm buffer missed") } === first, "Identity cache miss")
        cache.finishFrame()
        require(second.closed && !third.closed && !first.closed, "Least-recently-used inactive entry was not retired")
        cache.beginFrame()
        val fourth = cache.get(Any()) { Buffer(10) }
        cache.get(a) { throw AssertionError("Warm buffer missed") }
        require(!third.closed, "Retired an inactive buffer before prepared draws completed")
        cache.finishFrame()
        require(third.closed && !fourth.closed && !first.closed, "Soft budget did not retire remaining inactive entry")
        cache.close()
    }

    private fun idleEntriesExpire() {
        val cache = FrameGeometryCache<Any, Buffer>(256, 120, { it.bytes }, Buffer::close)
        cache.beginFrame()
        val buffer = cache.get(Any()) { Buffer(80) }
        cache.finishFrame()
        repeat(120) {
            cache.beginFrame()
            cache.finishFrame()
        }
        require(!buffer.closed, "Idle mesh expired before its grace period")
        cache.beginFrame()
        require(buffer.closed, "Idle mesh never expired")
        cache.finishFrame()
        cache.close()
    }

    private fun geometryUsesIdentity() {
        val cache = FrameGeometryCache<Any, Buffer>(256, 120, { it.bytes }, Buffer::close)
        cache.beginFrame()
        val first = cache.get(EqualGeometry(1)) { Buffer(80) }
        val second = cache.get(EqualGeometry(1)) { Buffer(80) }
        require(first !== second, "Rebuilt/equal geometry reused the old GPU buffer")
        cache.finishFrame()
        cache.close()
    }

    private fun failedUploadDoesNotPoisonCache() {
        val cache = FrameGeometryCache<Any, Buffer>(256, 120, { it.bytes }, Buffer::close)
        val key = Any()
        cache.beginFrame()
        try {
            cache.get(key) { throw IllegalStateException("simulated upload failure") }
            throw AssertionError("Upload failure swallowed")
        } catch (_: IllegalStateException) { }
        val retry = cache.get(key) { Buffer(80) }
        require(!retry.closed, "Failed upload prevented retry")
        cache.close()
        require(retry.closed, "Retried buffer leaked on close")
    }

    private fun failedDisposalDoesNotLeakRemainingBuffers() {
        val cache = FrameGeometryCache<Any, Buffer>(256, 120, { it.bytes }, {
            it.close()
            throw IllegalStateException("simulated disposal failure")
        })
        cache.beginFrame()
        val first = cache.get(Any()) { Buffer(80) }
        val second = cache.get(Any()) { Buffer(80) }
        try {
            cache.close()
            throw AssertionError("Disposal failure swallowed")
        } catch (expected: IllegalStateException) {
            require(expected.suppressed.size == 1, "Secondary disposal failure lost")
        }
        require(first.closed && second.closed, "One disposal failure leaked remaining buffers")
        cache.close()
    }

    private fun cachedFramesDoNotAllocate() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        require(bean.isThreadAllocatedMemorySupported, "Allocation accounting unavailable on this JVM")
        bean.isThreadAllocatedMemoryEnabled = true
        val cache = FrameGeometryCache<Any, Buffer>(1024, 120, { it.bytes }, Buffer::close)
        val geometry = Array(1024) { Any() }
        val create = Function<Any, Buffer> { Buffer(80) }
        // Warm the Java-callable interface used by the renderer, including identity-map lookup
        // and current-frame pinning when the active working set exceeds the soft budget.
        cachedFrames(cache, geometry, create, 2000)
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        cachedFrames(cache, geometry, create, 2000)
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        require(allocated < 16384, "Cached Kotlin frames introduced per-access allocation: $allocated bytes")
        println("CACHE_ALLOCATION: $allocated bytes for 2048000 warm accesses across 2000 frames")
        cache.close()
    }

    private fun allThrowableDisposalDrainsWithoutSelfSuppression() {
        for (first in arrayOf(java.io.IOException("checked disposal"), AssertionError("error disposal"), IllegalStateException("runtime disposal"))) {
            val second = java.io.IOException("secondary disposal")
            var attempts = 0
            val cache = FrameGeometryCache<Any, Buffer>(256, 120, { it.bytes }, {
                it.close()
                // A callback can reuse the same failure object; suppressing it onto itself must not interrupt cleanup.
                // Kotlin can throw checked exceptions directly from a Java Consumer.
                throw if (attempts++ < 2) first else second
            })
            cache.beginFrame()
            val buffers = ArrayList<Buffer>()
            repeat(3) { buffers.add(cache.get(Any()) { Buffer(80) }) }
            var escaped: Throwable? = null
            try { cache.close() } catch (failure: Throwable) { escaped = failure }
            require(escaped === first, "Disposal changed the primary Throwable identity")
            require(first.suppressed.size == 1 && first.suppressed[0] === second, "Disposal lost suppression or suppressed a failure onto itself")
            require(attempts == 3, "A checked/Error disposal failure stopped cleanup early")
            buffers.forEach { require(it.closed, "A Throwable disposal failure leaked another buffer") }
            cache.close()
            require(attempts == 3, "Failed close was not idempotent")
        }
    }

    private fun cachedFrames(cache: FrameGeometryCache<Any, Buffer>, geometry: Array<Any>, create: Function<Any, Buffer>, frames: Int) {
        repeat(frames) {
            cache.beginFrame()
            for (key in geometry) require(!cache.get(key, create).closed, "Active mesh retired during allocation probe")
            cache.finishFrame()
        }
    }

    private data class EqualGeometry(val value: Int)

    private fun allocate(allocated: MutableList<Buffer>, bytes: Long): Buffer = Buffer(bytes).also { allocated.add(it) }

    private class Buffer(val bytes: Long) {
        var closed = false
            private set
        fun close() {
            require(!closed, "Buffer was disposed twice")
            closed = true
        }
    }

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
