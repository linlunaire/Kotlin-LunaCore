package io.github.linlunaire.transitcore.collection;


import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Exercises the real renderer cache policy without allocating native/GPU buffers. */
public final class FrameGeometryCacheCheck {

	public static void main(String[] args) {
		activeSceneDoesNotThrash();
		largeWorkingSetDoesNotThrash();
		inactiveEntriesRetireInAccessOrder();
		idleEntriesExpire();
		geometryUsesIdentity();
		failedUploadDoesNotPoisonCache();
		failedDisposalDoesNotLeakRemainingBuffers();
		allThrowableDisposalDrainsWithoutSelfSuppression();
		cachedFramesDoNotAllocate();
		System.out.println("PASS: Java-to-Kotlin interface, oversized active-scene reuse, 4096 meshes over 200 frames, inactive LRU retirement, idle expiry, identity keys, upload failure, complete/idempotent disposal, allocation-free cached frames (no GPU/runtime claim)");
	}

	private static void activeSceneDoesNotThrash() {
		final List<Buffer> allocated = new ArrayList<>();
		final FrameGeometryCache<Object, Buffer> cache = new FrameGeometryCache<>(256, 120, buffer -> buffer.bytes, Buffer::close);
		final Object[] geometry = {new Object(), new Object(), new Object(), new Object()};
		for (int frame = 0; frame < 10; frame++) {
			cache.beginFrame();
			for (Object key : geometry) {
				final Buffer buffer = cache.get(key, ignored -> allocate(allocated, 80));
				require(!buffer.closed, "Prepared draw references a closed buffer");
			}
			cache.finishFrame();
		}
		require(allocated.size() == geometry.length,
			"Active scene over budget repeatedly uploads meshes: expected " + geometry.length + " allocations, got " + allocated.size());
		cache.close();
		cache.close();
		allocated.forEach(buffer -> require(buffer.closed, "Buffer leaked on resource reload"));
	}

	private static void largeWorkingSetDoesNotThrash() {
		final List<Buffer> allocated = new ArrayList<>();
		final FrameGeometryCache<Object, Buffer> cache = new FrameGeometryCache<>(1024, 120, buffer -> buffer.bytes, Buffer::close);
		final Object[] geometry = new Object[4096];
		for (int index = 0; index < geometry.length; index++) geometry[index] = new Object();
		for (int frame = 0; frame < 200; frame++) {
			cache.beginFrame();
			for (Object key : geometry) require(!cache.get(key, ignored -> allocate(allocated, 80)).closed, "Active mesh retired");
			cache.finishFrame();
		}
		require(allocated.size() == geometry.length, "Large stable scene re-uploaded geometry");
		cache.beginFrame();
		final Buffer replacement = cache.get(new Object(), ignored -> allocate(allocated, 2048));
		allocated.forEach(buffer -> require(!buffer.closed, "Preparation prematurely disposed a buffer"));
		cache.finishFrame();
		for (int index = 0; index < geometry.length; index++) require(allocated.get(index).closed, "Invisible large scene leaked beyond the soft budget");
		require(!replacement.closed, "Replacement active mesh was evicted");
		cache.close();
	}

	private static void inactiveEntriesRetireInAccessOrder() {
		final FrameGeometryCache<Object, Buffer> cache = new FrameGeometryCache<>(20, 120, buffer -> buffer.bytes, Buffer::close);
		final Object a = new Object(), b = new Object(), c = new Object();
		cache.beginFrame();
		final Buffer first = cache.get(a, ignored -> new Buffer(10));
		final Buffer second = cache.get(b, ignored -> new Buffer(10));
		final Buffer third = cache.get(c, ignored -> new Buffer(10));
		cache.finishFrame();
		cache.beginFrame();
		require(cache.get(a, ignored -> { throw new AssertionError("Warm buffer missed"); }) == first, "Identity cache miss");
		cache.finishFrame();
		require(second.closed && !third.closed && !first.closed, "Least-recently-used inactive entry was not retired");
		cache.beginFrame();
		final Buffer fourth = cache.get(new Object(), ignored -> new Buffer(10));
		cache.get(a, ignored -> { throw new AssertionError("Warm buffer missed"); });
		require(!third.closed, "Retired an inactive buffer before prepared draws completed");
		cache.finishFrame();
		require(third.closed && !fourth.closed && !first.closed, "Soft budget did not retire remaining inactive entry");
		cache.close();
	}

	private static void idleEntriesExpire() {
		final FrameGeometryCache<Object, Buffer> cache = new FrameGeometryCache<>(256, 120, buffer -> buffer.bytes, Buffer::close);
		cache.beginFrame();
		final Buffer buffer = cache.get(new Object(), ignored -> new Buffer(80));
		cache.finishFrame();
		for (int frame = 0; frame < 120; frame++) {
			cache.beginFrame();
			cache.finishFrame();
		}
		require(!buffer.closed, "Idle mesh expired before its grace period");
		cache.beginFrame();
		require(buffer.closed, "Idle mesh never expired");
		cache.finishFrame();
		cache.close();
	}

	private static void geometryUsesIdentity() {
		final FrameGeometryCache<Object, Buffer> cache = new FrameGeometryCache<>(256, 120, buffer -> buffer.bytes, Buffer::close);
		cache.beginFrame();
		final Buffer first = cache.get(new EqualGeometry(1), ignored -> new Buffer(80));
		final Buffer second = cache.get(new EqualGeometry(1), ignored -> new Buffer(80));
		require(first != second, "Rebuilt/equal geometry reused the old GPU buffer");
		cache.finishFrame();
		cache.close();
	}

	private static void failedUploadDoesNotPoisonCache() {
		final FrameGeometryCache<Object, Buffer> cache = new FrameGeometryCache<>(256, 120, buffer -> buffer.bytes, Buffer::close);
		final Object key = new Object();
		cache.beginFrame();
		try {
			cache.get(key, ignored -> { throw new IllegalStateException("simulated upload failure"); });
			throw new AssertionError("Upload failure swallowed");
		} catch (IllegalStateException expected) { }
		final Buffer retry = cache.get(key, ignored -> new Buffer(80));
		require(!retry.closed, "Failed upload prevented retry");
		cache.close();
		require(retry.closed, "Retried buffer leaked on close");
	}

	private static void failedDisposalDoesNotLeakRemainingBuffers() {
		final FrameGeometryCache<Object, Buffer> cache = new FrameGeometryCache<>(256, 120, buffer -> buffer.bytes, buffer -> {
			buffer.close();
			throw new IllegalStateException("simulated disposal failure");
		});
		cache.beginFrame();
		final Buffer first = cache.get(new Object(), ignored -> new Buffer(80));
		final Buffer second = cache.get(new Object(), ignored -> new Buffer(80));
		try {
			cache.close();
			throw new AssertionError("Disposal failure swallowed");
		} catch (IllegalStateException expected) {
			require(expected.getSuppressed().length == 1, "Secondary disposal failure lost");
		}
		require(first.closed && second.closed, "One disposal failure leaked remaining buffers");
		cache.close();
	}

	private static void cachedFramesDoNotAllocate() {
		final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		require(bean.isThreadAllocatedMemorySupported(), "Allocation accounting unavailable on this JVM");
		bean.setThreadAllocatedMemoryEnabled(true);
		final FrameGeometryCache<Object, Buffer> cache = new FrameGeometryCache<>(1024, 120, buffer -> buffer.bytes, Buffer::close);
		final Object[] geometry = new Object[1024];
		final Function<Object, Buffer> create = ignored -> new Buffer(80);
		for (int index = 0; index < geometry.length; index++) geometry[index] = new Object();
		// Warm the same Java-callable interface used by the renderer, including identity-map lookup
		// and current-frame pinning when the active working set exceeds the soft budget.
		cachedFrames(cache, geometry, create, 2000);
		final long thread = Thread.currentThread().threadId();
		final long before = bean.getThreadAllocatedBytes(thread);
		cachedFrames(cache, geometry, create, 2000);
		final long allocated = bean.getThreadAllocatedBytes(thread) - before;
		require(allocated < 16384, "Cached Kotlin frames introduced per-access allocation: " + allocated + " bytes");
		System.out.println("CACHE_ALLOCATION: " + allocated + " bytes for 2048000 warm accesses across 2000 frames");
		cache.close();
	}

	private static void allThrowableDisposalDrainsWithoutSelfSuppression() {
		for (Throwable first : new Throwable[]{new java.io.IOException("checked disposal"), new AssertionError("error disposal"), new IllegalStateException("runtime disposal")}) {
			final Throwable second = new java.io.IOException("secondary disposal");
			final int[] attempts = {0};
			final FrameGeometryCache<Object, Buffer> cache = new FrameGeometryCache<>(256, 120, buffer -> buffer.bytes, buffer -> {
				buffer.close();
				// A callback can reuse the same failure object; suppressing it onto itself must not interrupt cleanup.
				sneakyThrow(attempts[0]++ < 2 ? first : second);
			});
			cache.beginFrame();
			final List<Buffer> buffers = new ArrayList<>();
			for (int index = 0; index < 3; index++) buffers.add(cache.get(new Object(), ignored -> new Buffer(80)));
			Throwable escaped = null;
			try { cache.close(); } catch (Throwable failure) { escaped = failure; }
			require(escaped == first, "Disposal changed the primary Throwable identity");
			require(first.getSuppressed().length == 1 && first.getSuppressed()[0] == second, "Disposal lost suppression or suppressed a failure onto itself");
			require(attempts[0] == 3, "A checked/Error disposal failure stopped cleanup early");
			buffers.forEach(buffer -> require(buffer.closed, "A Throwable disposal failure leaked another buffer"));
			cache.close();
			require(attempts[0] == 3, "Failed close was not idempotent");
		}
	}

	// Kotlin callers may throw checked Java exceptions from a Java Consumer.
	@SuppressWarnings("unchecked")
	private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E { throw (E) failure; }

	private static void cachedFrames(FrameGeometryCache<Object, Buffer> cache, Object[] geometry, Function<Object, Buffer> create, int frames) {
		for (int frame = 0; frame < frames; frame++) {
			cache.beginFrame();
			for (Object key : geometry) require(!cache.get(key, create).closed, "Active mesh retired during allocation probe");
			cache.finishFrame();
		}
	}

	private record EqualGeometry(int value) { }

	private static Buffer allocate(List<Buffer> allocated, long bytes) {
		final Buffer buffer = new Buffer(bytes);
		allocated.add(buffer);
		return buffer;
	}

	private static final class Buffer {
		private final long bytes;
		private boolean closed;
		private Buffer(long bytes) { this.bytes = bytes; }
		private void close() {
			require(!closed, "Buffer was disposed twice");
			closed = true;
		}
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
