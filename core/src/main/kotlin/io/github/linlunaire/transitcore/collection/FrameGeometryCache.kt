// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 linlunaire. Prior MIT provenance: licenses/LunaCore-MIT.txt.
package io.github.linlunaire.transitcore.collection

import java.util.IdentityHashMap
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.ToLongFunction

/**
 * Render-thread-owned buffer lifetime policy, independent of GPU allocation.
 * The budget is soft: current-frame geometry is pinned through execution, even
 * when the active working set exceeds it. After a frame, retained bytes are at
 * most max(budget, active working set); invisible entries never expand that bound.
 *
 * Java functional interfaces keep callers independent of Kotlin function types.
 * Cached access only relinks existing entries; it creates no iterators or wrappers.
 * Close attempts every retained disposal even if callbacks throw, then rethrows
 * the first failure with later failures suppressed; already retired entries are
 * not disposed again on a subsequent close.
 */
class FrameGeometryCache<K, V>(
    private val budgetBytes: Long,
    private val maxIdleFrames: Int,
    private val size: ToLongFunction<V>,
    private val dispose: Consumer<V>,
) {
    private val entries = IdentityHashMap<K, Entry<K, V>>()
    private var frame = 0L
    private var bytes = 0L
    private var oldest: Entry<K, V>? = null
    private var newest: Entry<K, V>? = null

    fun beginFrame() {
        frame++
        while (true) {
            val entry = oldest ?: break
            if (frame - entry.lastFrame <= maxIdleFrames) break
            retireOldest(entry)
        }
    }

    fun get(key: K, create: Function<K, V>): V {
        var entry = entries[key]
        if (entry == null) {
            val value = create.apply(key)
            entry = Entry(key, value, size.applyAsLong(value))
            entries[key] = entry
            bytes += entry.bytes
        } else {
            unlink(entry)
        }
        entry.lastFrame = frame
        entry.previous = newest
        val previous = newest
        if (previous == null) oldest = entry else previous.next = entry
        newest = entry
        return entry.value
    }

    fun finishFrame() {
        // Access order puts inactive entries before all pinned current-frame entries.
        // Do not evict an active mesh merely to upload it again on the next frame.
        while (bytes > budgetBytes) {
            val entry = oldest ?: break
            if (entry.lastFrame == frame) break
            retireOldest(entry)
        }
    }

    fun close() {
        var failure: Throwable? = null
        while (true) {
            val entry = oldest ?: break
            try {
                retireOldest(entry)
            } catch (exception: Throwable) {
                if (failure == null) failure = exception
                else if (failure !== exception) failure.addSuppressed(exception)
            }
        }
        if (failure != null) throw failure
    }

    private fun retireOldest(entry: Entry<K, V>) {
        unlink(entry)
        entries.remove(entry.key)
        bytes -= entry.bytes
        dispose.accept(entry.value)
    }

    private fun unlink(entry: Entry<K, V>) {
        val previous = entry.previous
        val next = entry.next
        if (previous == null) oldest = next else previous.next = next
        if (next == null) newest = previous else next.previous = previous
        entry.previous = null
        entry.next = null
    }

    private class Entry<K, V>(val key: K, val value: V, val bytes: Long) {
        var lastFrame = 0L
        var previous: Entry<K, V>? = null
        var next: Entry<K, V>? = null
    }
}
