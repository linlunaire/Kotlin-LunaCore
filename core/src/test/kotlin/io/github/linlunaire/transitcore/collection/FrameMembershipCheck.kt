package io.github.linlunaire.transitcore.collection;


import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/** Java callers exercise the real Kotlin policy, including the legacy set-difference contract. */
public final class FrameMembershipCheck {

	private static final Consumer<Object> NO_CHANGE = ignored -> { throw new AssertionError("Stable scene changed"); };
	public static void main(String[] args) {
		equalityDuplicatesAndCanonicalKeys();
		clearAndEmptyFrames();
		callbackFailuresRemainRetryable();
		callbacksCannotReenter();
		randomizedLegacyComparison();
		stableSceneAllocation(4096, 200);
		stableSceneAllocation(20000, 100);
		System.out.println("PASS: Java-to-Kotlin frame membership, equals/hashCode and canonical keys, duplicate marks, add-before-remove, callback retries, clear/empty frames, 2000 randomized legacy comparisons, 4096/20000-member allocation checks (no GPU/runtime claim)");
	}

	private static void equalityDuplicatesAndCanonicalKeys() {
		final FrameMembership<EqualKey> membership = new FrameMembership<>();
		final List<EqualKey> added = new ArrayList<>(), removed = new ArrayList<>();
		final EqualKey first = new EqualKey(1), collision = new EqualKey(2);
		membership.mark(first);
		membership.mark(new EqualKey(1));
		membership.mark(first);
		membership.mark(collision);
		membership.reconcile(added::add, removed::add);
		require(added.size() == 2 && added.contains(collision), "Equal keys duplicated or hash collision merged keys");
		require(added.stream().filter(key -> key.id == 1).findFirst().orElseThrow() == first, "First canonical key was replaced");
		added.clear();
		membership.mark(new EqualKey(1));
		membership.mark(new EqualKey(2));
		membership.reconcile(added::add, removed::add);
		require(added.isEmpty() && removed.isEmpty(), "Equal replacement instances churned retained members");
		membership.reconcile(added::add, removed::add);
		require(removed.size() == 2 && removed.stream().anyMatch(key -> key == first), "Removal did not retain canonical keys");
		membership.reconcile(added::add, removed::add);
		require(removed.size() == 2, "Empty frame repeated removals");
	}

	private static void clearAndEmptyFrames() {
		final FrameMembership<Object> membership = new FrameMembership<>();
		final Object retained = new Object(), pending = new Object();
		final List<Object> added = new ArrayList<>(), removed = new ArrayList<>();
		membership.reconcile(added::add, removed::add);
		membership.mark(retained);
		membership.reconcile(added::add, removed::add);
		membership.mark(pending);
		membership.clear();
		membership.clear();
		membership.reconcile(added::add, removed::add);
		require(added.size() == 1 && removed.isEmpty(), "Clear invoked resource callbacks or preserved pending marks");
		membership.mark(retained);
		membership.reconcile(added::add, removed::add);
		require(added.size() == 2, "Clear did not allow the same member to be rebuilt");
		membership.reconcile(added::add, removed::add);
		require(removed.size() == 1 && removed.getFirst() == retained, "Post-clear lifecycle was not independent");
		final FrameMembership<Object> nullable = new FrameMembership<>();
		nullable.mark(null);
		nullable.mark(null);
		final int[] nullAdds = {0}, nullRemoves = {0};
		nullable.reconcile(key -> { require(key == null, "Null member changed"); nullAdds[0]++; }, key -> nullRemoves[0]++);
		nullable.reconcile(key -> nullAdds[0]++, key -> { require(key == null, "Null removal changed"); nullRemoves[0]++; });
		require(nullAdds[0] == 1 && nullRemoves[0] == 1, "HashSet-compatible null membership failed");
	}

	private static void callbackFailuresRemainRetryable() {
		final FrameMembership<Object> membership = new FrameMembership<>();
		final Set<Object> active = new HashSet<>();
		final Object prior = new Object();
		membership.mark(prior);
		membership.reconcile(active::add, active::remove);
		final Object[] incoming = {new Object(), new Object(), new Object()};
		for (Object key : incoming) membership.mark(key);
		final IllegalStateException addFailure = new IllegalStateException("simulated add failure");
		final int[] attempts = {0};
		try {
			membership.reconcile(key -> {
				if (++attempts[0] == 2) throw addFailure;
				require(active.add(key), "Repeated a successful add");
			}, key -> { throw new AssertionError("Removal ran before all additions succeeded"); });
			throw new AssertionError("Add failure swallowed");
		} catch (IllegalStateException expected) {
			require(expected == addFailure, "Add failure identity changed");
		}
		require(active.contains(prior) && active.size() == 2, "Failed addition corrupted retained state");
		membership.reconcile(key -> require(active.add(key), "Retry repeated a successful add"), key -> require(active.remove(key), "Unknown removal"));
		require(active.size() == incoming.length && !active.contains(prior), "Add retry did not finish the same frame");
		final AssertionError removeFailure = new AssertionError("simulated remove failure");
		attempts[0] = 0;
		try {
			membership.reconcile(NO_CHANGE, key -> {
				if (++attempts[0] == 2) throw removeFailure;
				require(active.remove(key), "Repeated a successful removal");
			});
			throw new AssertionError("Remove failure swallowed");
		} catch (AssertionError expected) {
			require(expected == removeFailure, "Remove failure identity changed");
		}
		require(active.size() == 2, "Failed removal corrupted retained state");
		membership.reconcile(NO_CHANGE, key -> require(active.remove(key), "Retry repeated a successful removal"));
		require(active.isEmpty(), "Removal retry leaked members");
		membership.reconcile(NO_CHANGE, NO_CHANGE);
		membership.mark(prior);
		try {
			membership.reconcile(key -> { throw addFailure; }, NO_CHANGE);
			throw new AssertionError("Add failure swallowed before clear");
		} catch (IllegalStateException expected) { require(expected == addFailure, "Wrong pending failure"); }
		membership.clear();
		membership.reconcile(NO_CHANGE, NO_CHANGE);
	}

	private static void callbacksCannotReenter() {
		final FrameMembership<Object> membership = new FrameMembership<>();
		final Object key = new Object();
		membership.mark(key);
		membership.reconcile(ignored -> {
			expectReentryFailure(() -> membership.mark(new Object()));
			expectReentryFailure(membership::clear);
			expectReentryFailure(() -> membership.reconcile(NO_CHANGE, NO_CHANGE));
		}, NO_CHANGE);
		membership.reconcile(NO_CHANGE, ignored -> {});
	}

	private static void expectReentryFailure(Runnable action) {
		try {
			action.run();
			throw new AssertionError("Reentrant mutation was accepted");
		} catch (IllegalStateException expected) { }
	}

	private static void randomizedLegacyComparison() {
		final FrameMembership<EqualKey> membership = new FrameMembership<>();
		final Set<EqualKey> legacyRetained = new HashSet<>(), actualRetained = new HashSet<>();
		final Random random = new Random(0x262L);
		for (int frame = 0; frame < 2000; frame++) {
			if (random.nextInt(31) == 0) {
				membership.clear();
				legacyRetained.clear();
				actualRetained.clear(); // The owner releases resources independently of membership.clear().
			}
			final Set<EqualKey> current = new HashSet<>();
			for (int index = random.nextInt(300); index > 0; index--) {
				final EqualKey key = new EqualKey(random.nextInt(200));
				current.add(key);
				membership.mark(key);
				if (random.nextBoolean()) membership.mark(new EqualKey(key.id));
			}
			final Set<EqualKey> expectedAdds = new HashSet<>(current);
			expectedAdds.removeAll(legacyRetained);
			legacyRetained.addAll(expectedAdds);
			final Set<EqualKey> expectedRemoves = new HashSet<>(legacyRetained);
			expectedRemoves.removeAll(current);
			legacyRetained.removeAll(expectedRemoves);
			final Set<EqualKey> actualAdds = new HashSet<>(), actualRemoves = new HashSet<>();
			final boolean[] removing = {false};
			membership.reconcile(key -> {
				require(!removing[0], "Addition happened after removal");
				require(actualAdds.add(key) && actualRetained.add(key), "Duplicate addition");
			}, key -> {
				removing[0] = true;
				require(actualRemoves.add(key) && actualRetained.remove(key), "Duplicate/unknown removal");
			});
			require(actualAdds.equals(expectedAdds) && actualRemoves.equals(expectedRemoves), "Legacy transitions differ at frame " + frame);
			require(actualRetained.equals(legacyRetained), "Legacy final membership differs at frame " + frame);
		}
	}

	private static void stableSceneAllocation(int members, int measuredFrames) {
		final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		require(bean.isThreadAllocatedMemorySupported(), "Allocation accounting unavailable on this JVM");
		bean.setThreadAllocatedMemoryEnabled(true);
		final FrameMembership<Object> membership = new FrameMembership<>();
		final Object[] keys = new Object[members];
		for (int index = 0; index < members; index++) { keys[index] = new Object(); membership.mark(keys[index]); }
		membership.reconcile(ignored -> {}, NO_CHANGE);
		stableFrames(membership, keys, 300);
		final long thread = Thread.currentThread().threadId();
		final long before = bean.getThreadAllocatedBytes(thread);
		stableFrames(membership, keys, measuredFrames);
		final long allocated = bean.getThreadAllocatedBytes(thread) - before;
		require(allocated < 16384, "Stable membership introduced per-member allocation: " + allocated + " bytes");
		final Set<Object> legacyCurrent = new HashSet<>(), legacyRetained = new HashSet<>();
		for (Object key : keys) legacyRetained.add(key);
		legacyFrames(legacyCurrent, legacyRetained, keys, 10);
		final long legacyBefore = bean.getThreadAllocatedBytes(thread);
		legacyFrames(legacyCurrent, legacyRetained, keys, measuredFrames);
		final long legacyAllocated = bean.getThreadAllocatedBytes(thread) - legacyBefore;
		require(legacyAllocated > (long) members * measuredFrames * 32, "Legacy comparison did not exercise copied sets");
		System.out.println("MEMBERSHIP_ALLOCATION: members=" + members + ", frames=" + measuredFrames + ", Kotlin=" + allocated + " bytes, legacy=" + legacyAllocated + " bytes");
		membership.clear();
	}

	private static void stableFrames(FrameMembership<Object> membership, Object[] keys, int frames) {
		for (int frame = 0; frame < frames; frame++) {
			for (Object key : keys) membership.mark(key);
			membership.reconcile(NO_CHANGE, NO_CHANGE);
		}
	}

	private static void legacyFrames(Set<Object> current, Set<Object> retained, Object[] keys, int frames) {
		for (int frame = 0; frame < frames; frame++) {
			for (Object key : keys) current.add(key);
			final Set<Object> added = new HashSet<>(current);
			added.removeAll(retained);
			for (Object key : added) retained.add(key);
			final Set<Object> removed = new HashSet<>(retained);
			removed.removeAll(current);
			for (Object key : removed) retained.remove(key);
			current.clear();
			require(added.isEmpty() && removed.isEmpty() && retained.size() == keys.length, "Legacy stable scene changed");
		}
	}

	private record EqualKey(int id) {
		@Override public int hashCode() { return 7; }
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
