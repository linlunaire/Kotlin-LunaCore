// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 linlunaire. Prior MIT provenance: licenses/LunaCore-MIT.txt.
package io.github.linlunaire.transitcore.collection

import java.util.HashMap
import java.util.function.Consumer

/**
 * Owner-thread membership reconciliation without rebuilding sets on every frame.
 * Mark all observed keys, then reconcile once: additions always precede removals.
 * Equal keys share one member and retain the first key until removal; keys must
 * keep stable equals/hashCode while retained, just as with HashMap/HashSet.
 *
 * A failed callback leaves that transition pending and the frame open for retry.
 * Successful callbacks are not repeated. Callbacks must not mutate this module,
 * and a callback that fails must leave its own side effects safe to retry.
 * Clear forgets both retained and pending members; resource ownership stays with
 * the caller, so clear never invokes callbacks. Stable frames allocate no entries,
 * iterators or boxed frame counters.
 */
class FrameMembership<K> {
    private val entries = HashMap<K, Entry<K>>()
    private var first: Entry<K>? = null
    private var last: Entry<K>? = null
    private var frame = 0L
    private var reconciling = false

    fun mark(key: K) {
        check(!reconciling) { "Membership callbacks must not mutate membership" }
        var entry = entries[key]
        if (entry == null) {
            entry = Entry(key)
            entries[key] = entry
            entry.previous = last
            val previous = last
            if (previous == null) first = entry else previous.next = entry
            last = entry
        }
        entry.seenFrame = frame
    }

    fun reconcile(added: Consumer<in K>, removed: Consumer<in K>) {
        check(!reconciling) { "Membership callbacks must not mutate membership" }
        reconciling = true
        try {
            var entry = first
            while (entry != null) {
                if (entry.seenFrame == frame && !entry.active) {
                    added.accept(entry.key)
                    entry.active = true
                }
                entry = entry.next
            }
            entry = first
            while (entry != null) {
                val next = entry.next
                if (entry.seenFrame != frame) {
                    if (entry.active) removed.accept(entry.key)
                    entries.remove(entry.key)
                    val previous = entry.previous
                    if (previous == null) first = next else previous.next = next
                    if (next == null) last = previous else next.previous = previous
                }
                entry = next
            }
            frame++
        } finally {
            reconciling = false
        }
    }

    fun clear() {
        check(!reconciling) { "Membership callbacks must not mutate membership" }
        entries.clear()
        first = null
        last = null
        frame = 0L
    }

    private class Entry<K>(val key: K) {
        var seenFrame = 0L
        var active = false
        var previous: Entry<K>? = null
        var next: Entry<K>? = null
    }
}
