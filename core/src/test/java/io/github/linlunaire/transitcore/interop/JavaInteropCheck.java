package io.github.linlunaire.transitcore.interop;

import io.github.linlunaire.transitcore.collection.FrameGeometryCache;
import io.github.linlunaire.transitcore.collection.FrameMembership;
import io.github.linlunaire.transitcore.concurrent.BoundedTaskDispatcher;
import java.lang.reflect.Modifier;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Deliberately Java: javac must keep accepting the public SAM and JVM interfaces. */
public final class JavaInteropCheck {
    public static void main(String[] args) throws Exception {
        int[] created = {0}, disposed = {0};
        FrameGeometryCache<Object, Object> cache = new FrameGeometryCache<>(1, 120, value -> 2, value -> disposed[0]++);
        Object key = new Object();
        for (int frame = 0; frame < 3; frame++) {
            cache.beginFrame();
            cache.get(key, ignored -> { created[0]++; return new Object(); });
            cache.finishFrame();
        }
        cache.close();
        require(created[0] == 1 && disposed[0] == 1, "Java cache lifecycle changed");

        FrameMembership<Object> members = new FrameMembership<>();
        int[] added = {0}, removed = {0};
        members.mark(null);
        members.mark(null);
        Consumer<Object> add = value -> { require(value == null, "Java null member changed"); added[0]++; };
        Consumer<Object> remove = value -> { require(value == null, "Java null removal changed"); removed[0]++; };
        members.reconcile(add, remove);
        members.reconcile(add, remove);
        members.clear();
        require(added[0] == 1 && removed[0] == 1, "Java nullable membership changed");

        require(Modifier.isStatic(BoundedTaskDispatcher.class.getMethod("newWorkerPool", int.class, String.class).getModifiers()), "Worker factory is not Java-static");
        require(BoundedTaskDispatcher.Upload.class.getMethod("close").isDefault(), "Upload.close is not a JVM default method");
        require(BoundedTaskDispatcher.class.getMethod("trySchedule", Runnable.class, Supplier.class,
                BooleanSupplier.class, Runnable.class, Consumer.class).getReturnType() == boolean.class,
                "Java interface introduced Kotlin function types or a boxed result");
        var pool = BoundedTaskDispatcher.newWorkerPool(1, "Java interop ");
        pool.shutdownNow();
        BoundedTaskDispatcher dispatcher = new BoundedTaskDispatcher((Executor) Runnable::run, 1);
        int[] uploaded = {0}, finished = {0};
        BoundedTaskDispatcher.Upload upload = () -> uploaded[0]++;
        upload.close(); // Must compile without implementing close or declaring a checked exception.
        require(dispatcher.trySchedule(() -> {}, () -> upload, () -> true, () -> finished[0]++, error -> { throw new AssertionError(error); }), "Java SAM work rejected");
        require(dispatcher.uploadOne() && !dispatcher.uploadOne(), "Java SAM work lost or duplicated");
        require(uploaded[0] == 1 && finished[0] == 1, "Java SAM completion changed");
        System.out.println("PASS: javac callers, nullable generics, primitive result, Java-static factory and Upload JVM default method");
    }

    // An invalid Java supplier can return null despite Kotlin's non-null generic contract.
    public static Supplier<BoundedTaskDispatcher.Upload> nullUploadSupplier() { return () -> null; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
