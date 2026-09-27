package io.github.linlunaire.transitcore.collection

import java.lang.management.ManagementFactory
import java.util.ArrayList
import java.util.HashSet
import java.util.Random
import java.util.function.Consumer

/** Exercises the real Kotlin policy, including the legacy set-difference contract. */
object FrameMembershipCheck {
    private val NO_CHANGE = Consumer<Any?> { throw AssertionError("Stable scene changed") }

    @JvmStatic
    fun main(args: Array<String>) {
        equalityDuplicatesAndCanonicalKeys()
        clearAndEmptyFrames()
        callbackFailuresRemainRetryable()
        callbacksCannotReenter()
        randomizedLegacyComparison()
        stableSceneAllocation(4096, 200)
        stableSceneAllocation(20000, 100)
        println("PASS: Kotlin frame membership, equals/hashCode and canonical keys, duplicate marks, add-before-remove, callback retries, clear/empty frames, 2000 randomized legacy comparisons, 4096/20000-member allocation checks (no GPU/runtime claim)")
    }

    private fun equalityDuplicatesAndCanonicalKeys() {
        val membership = FrameMembership<EqualKey>()
        val added = ArrayList<EqualKey>()
        val removed = ArrayList<EqualKey>()
        val first = EqualKey(1)
        val collision = EqualKey(2)
        membership.mark(first)
        membership.mark(EqualKey(1))
        membership.mark(first)
        membership.mark(collision)
        membership.reconcile(added::add, removed::add)
        require(added.size == 2 && added.contains(collision), "Equal keys duplicated or hash collision merged keys")
        require(added.first { it.id == 1 } === first, "First canonical key was replaced")
        added.clear()
        membership.mark(EqualKey(1))
        membership.mark(EqualKey(2))
        membership.reconcile(added::add, removed::add)
        require(added.isEmpty() && removed.isEmpty(), "Equal replacement instances churned retained members")
        membership.reconcile(added::add, removed::add)
        require(removed.size == 2 && removed.any { it === first }, "Removal did not retain canonical keys")
        membership.reconcile(added::add, removed::add)
        require(removed.size == 2, "Empty frame repeated removals")
    }

    private fun clearAndEmptyFrames() {
        val membership = FrameMembership<Any>()
        val retained = Any()
        val pending = Any()
        val added = ArrayList<Any>()
        val removed = ArrayList<Any>()
        membership.reconcile(added::add, removed::add)
        membership.mark(retained)
        membership.reconcile(added::add, removed::add)
        membership.mark(pending)
        membership.clear()
        membership.clear()
        membership.reconcile(added::add, removed::add)
        require(added.size == 1 && removed.isEmpty(), "Clear invoked resource callbacks or preserved pending marks")
        membership.mark(retained)
        membership.reconcile(added::add, removed::add)
        require(added.size == 2, "Clear did not allow the same member to be rebuilt")
        membership.reconcile(added::add, removed::add)
        require(removed.size == 1 && removed.first() === retained, "Post-clear lifecycle was not independent")
        val nullable = FrameMembership<Any?>()
        nullable.mark(null)
        nullable.mark(null)
        var nullAdds = 0
        var nullRemoves = 0
        nullable.reconcile({ require(it == null, "Null member changed"); nullAdds++ }, { nullRemoves++ })
        nullable.reconcile({ nullAdds++ }, { require(it == null, "Null removal changed"); nullRemoves++ })
        require(nullAdds == 1 && nullRemoves == 1, "HashSet-compatible null membership failed")
    }

    private fun callbackFailuresRemainRetryable() {
        val membership = FrameMembership<Any>()
        val active = HashSet<Any>()
        val prior = Any()
        membership.mark(prior)
        membership.reconcile(active::add, active::remove)
        val incoming = Array(3) { Any() }
        for (key in incoming) membership.mark(key)
        val addFailure = IllegalStateException("simulated add failure")
        var attempts = 0
        try {
            membership.reconcile({
                if (++attempts == 2) throw addFailure
                require(active.add(it), "Repeated a successful add")
            }, { throw AssertionError("Removal ran before all additions succeeded") })
            throw AssertionError("Add failure swallowed")
        } catch (expected: IllegalStateException) {
            require(expected === addFailure, "Add failure identity changed")
        }
        require(active.contains(prior) && active.size == 2, "Failed addition corrupted retained state")
        membership.reconcile({ require(active.add(it), "Retry repeated a successful add") }, { require(active.remove(it), "Unknown removal") })
        require(active.size == incoming.size && !active.contains(prior), "Add retry did not finish the same frame")
        val removeFailure = AssertionError("simulated remove failure")
        attempts = 0
        try {
            membership.reconcile(NO_CHANGE, {
                if (++attempts == 2) throw removeFailure
                require(active.remove(it), "Repeated a successful removal")
            })
            throw AssertionError("Remove failure swallowed")
        } catch (expected: AssertionError) {
            require(expected === removeFailure, "Remove failure identity changed")
        }
        require(active.size == 2, "Failed removal corrupted retained state")
        membership.reconcile(NO_CHANGE, { require(active.remove(it), "Retry repeated a successful removal") })
        require(active.isEmpty(), "Removal retry leaked members")
        membership.reconcile(NO_CHANGE, NO_CHANGE)
        membership.mark(prior)
        try {
            membership.reconcile({ throw addFailure }, NO_CHANGE)
            throw AssertionError("Add failure swallowed before clear")
        } catch (expected: IllegalStateException) { require(expected === addFailure, "Wrong pending failure") }
        membership.clear()
        membership.reconcile(NO_CHANGE, NO_CHANGE)
    }

    private fun callbacksCannotReenter() {
        val membership = FrameMembership<Any>()
        val key = Any()
        membership.mark(key)
        membership.reconcile({
            expectReentryFailure { membership.mark(Any()) }
            expectReentryFailure(membership::clear)
            expectReentryFailure { membership.reconcile(NO_CHANGE, NO_CHANGE) }
        }, NO_CHANGE)
        membership.reconcile(NO_CHANGE, {})
    }

    private fun expectReentryFailure(action: Runnable) {
        try {
            action.run()
            throw AssertionError("Reentrant mutation was accepted")
        } catch (_: IllegalStateException) { }
    }

    private fun randomizedLegacyComparison() {
        val membership = FrameMembership<EqualKey>()
        val legacyRetained = HashSet<EqualKey>()
        val actualRetained = HashSet<EqualKey>()
        val random = Random(0x262L)
        repeat(2000) { frame ->
            if (random.nextInt(31) == 0) {
                membership.clear()
                legacyRetained.clear()
                actualRetained.clear() // The owner releases resources independently of membership.clear().
            }
            val current = HashSet<EqualKey>()
            repeat(random.nextInt(300)) {
                val key = EqualKey(random.nextInt(200))
                current.add(key)
                membership.mark(key)
                if (random.nextBoolean()) membership.mark(EqualKey(key.id))
            }
            val expectedAdds = HashSet(current)
            expectedAdds.removeAll(legacyRetained)
            legacyRetained.addAll(expectedAdds)
            val expectedRemoves = HashSet(legacyRetained)
            expectedRemoves.removeAll(current)
            legacyRetained.removeAll(expectedRemoves)
            val actualAdds = HashSet<EqualKey>()
            val actualRemoves = HashSet<EqualKey>()
            var removing = false
            membership.reconcile({
                require(!removing, "Addition happened after removal")
                require(actualAdds.add(it) && actualRetained.add(it), "Duplicate addition")
            }, {
                removing = true
                require(actualRemoves.add(it) && actualRetained.remove(it), "Duplicate/unknown removal")
            })
            require(actualAdds == expectedAdds && actualRemoves == expectedRemoves, "Legacy transitions differ at frame $frame")
            require(actualRetained == legacyRetained, "Legacy final membership differs at frame $frame")
        }
    }

    private fun stableSceneAllocation(members: Int, measuredFrames: Int) {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        require(bean.isThreadAllocatedMemorySupported, "Allocation accounting unavailable on this JVM")
        bean.isThreadAllocatedMemoryEnabled = true
        val membership = FrameMembership<Any>()
        val keys = Array(members) { Any().also(membership::mark) }
        membership.reconcile({}, NO_CHANGE)
        stableFrames(membership, keys, 300)
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        stableFrames(membership, keys, measuredFrames)
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        require(allocated < 16384, "Stable membership introduced per-member allocation: $allocated bytes")
        val legacyCurrent = HashSet<Any>()
        val legacyRetained = HashSet<Any>()
        for (key in keys) legacyRetained.add(key)
        legacyFrames(legacyCurrent, legacyRetained, keys, 10)
        val legacyBefore = bean.getThreadAllocatedBytes(thread)
        legacyFrames(legacyCurrent, legacyRetained, keys, measuredFrames)
        val legacyAllocated = bean.getThreadAllocatedBytes(thread) - legacyBefore
        require(legacyAllocated > members.toLong() * measuredFrames * 32, "Legacy comparison did not exercise copied sets")
        println("MEMBERSHIP_ALLOCATION: members=$members, frames=$measuredFrames, Kotlin=$allocated bytes, legacy=$legacyAllocated bytes")
        membership.clear()
    }

    private fun stableFrames(membership: FrameMembership<Any>, keys: Array<Any>, frames: Int) {
        repeat(frames) {
            for (key in keys) membership.mark(key)
            membership.reconcile(NO_CHANGE, NO_CHANGE)
        }
    }

    private fun legacyFrames(current: MutableSet<Any>, retained: MutableSet<Any>, keys: Array<Any>, frames: Int) {
        repeat(frames) {
            for (key in keys) current.add(key)
            val added = HashSet(current)
            added.removeAll(retained)
            for (key in added) retained.add(key)
            val removed = HashSet(retained)
            removed.removeAll(current)
            for (key in removed) retained.remove(key)
            current.clear()
            require(added.isEmpty() && removed.isEmpty() && retained.size == keys.size, "Legacy stable scene changed")
        }
    }

    private data class EqualKey(val id: Int) {
        override fun hashCode(): Int = 7
    }

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
