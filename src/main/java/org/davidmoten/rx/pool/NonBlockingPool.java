package org.davidmoten.rx.pool;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import com.github.davidmoten.guavamini.Preconditions;

import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Predicate;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.Schedulers;

public final class NonBlockingPool<T> implements Pool<T> {

    final Callable<? extends T> factory;
    final Predicate<? super T> healthy;
    final long idleTimeBeforeHealthCheckMs;
    final Consumer<? super T> disposer;
    final int maxSize;
    final long maxIdleTimeMs;
    final long checkoutRetryIntervalMs;
    final long returnToPoolDelayAfterHealthCheckFailureMs;
    final BiFunction<? super T, ? super Checkin, ? extends T> checkinDecorator;
    final Scheduler scheduler;
    final Action closeAction;

    private final AtomicReference<MemberSingle<T>> member = new AtomicReference<>();
    private volatile boolean closed;

    NonBlockingPool(Callable<? extends T> factory, Predicate<? super T> healthy,
            Consumer<? super T> disposer, int maxSize,
            long returnToPoolDelayAfterHealthCheckFailureMs, long idleTimeBeforeHealthCheckMs,
            long maxIdleTimeMs, long checkoutRetryIntervalMs,
            BiFunction<? super T, ? super Checkin, ? extends T> checkinDecorator,
            Scheduler scheduler, Action closeAction) {
        Preconditions.checkNotNull(factory);
        Preconditions.checkNotNull(healthy);
        Preconditions.checkNotNull(disposer);
        Preconditions.checkArgument(maxSize > 0);
        Preconditions.checkArgument(returnToPoolDelayAfterHealthCheckFailureMs >= 0);
        Preconditions.checkNotNull(checkinDecorator);
        Preconditions.checkNotNull(scheduler);
        Preconditions.checkArgument(checkoutRetryIntervalMs >= 0,
                "checkoutRetryIntervalMs must be >=0");
        Preconditions.checkNotNull(closeAction);
        Preconditions.checkArgument(maxIdleTimeMs >= 0, "maxIdleTime must be >=0");
        this.factory = factory;
        this.healthy = healthy;
        this.disposer = disposer;
        this.maxSize = maxSize;
        this.returnToPoolDelayAfterHealthCheckFailureMs = returnToPoolDelayAfterHealthCheckFailureMs;
        this.idleTimeBeforeHealthCheckMs = idleTimeBeforeHealthCheckMs;
        this.maxIdleTimeMs = maxIdleTimeMs;
        this.checkoutRetryIntervalMs = checkoutRetryIntervalMs;
        this.checkinDecorator = checkinDecorator;
        this.scheduler = scheduler;// schedules retries
        this.closeAction = closeAction;
    }

    private MemberSingle<T> createMember() {
        return new MemberSingle<T>(this);
    }

    @Override
    public Single<Member<T>> member() {
        while (true) {
            MemberSingle<T> m = member.get();
            if (m != null)
                return m;
            else {
                m = createMember();
                if (member.compareAndSet(null, m)) {
                    return m;
                }
            }
        }
    }

    public void checkin(Member<T> m) {
        MemberSingle<T> mem = member.get();
        if (mem != null) {
            mem.checkin(m);
        }
    }

    @Override
    public void close() {
        closed = true;
        while (true) {
            MemberSingle<T> m = member.get();
            if (m == null) {
                return;
            } else if (member.compareAndSet(m, null)) {
                m.close();
                break;
            }
        }
        try {
            closeAction.run();
        } catch (Exception e) {
            RxJavaPlugins.onError(e);
        }
    }

    boolean isClosed() {
        return closed;
    }

    public static <T> Builder<T> factory(Callable<T> factory) {
        return new Builder<T>().factory(factory);
    }

    public static class Builder<T> {

        private static final BiFunction<Object, Checkin, Object> DEFAULT_CHECKIN_DECORATOR = (x,
                y) -> x;
        private Callable<? extends T> factory;
        private Predicate<? super T> healthy = x -> true;
        private long idleTimeBeforeHealthCheckMs = 1000;
        private Consumer<? super T> disposer = Consumers.doNothing();
        private int maxSize = 10;
        private long returnToPoolDelayAfterHealthCheckFailureMs = 30000;
        private long checkoutRetryIntervalMs = 30000;
        private Scheduler scheduler = Schedulers.computation();
        private long maxIdleTimeMs;
        @SuppressWarnings("unchecked")
        private BiFunction<? super T, ? super Checkin, ? extends T> checkinDecorator = (BiFunction<T, Checkin, T>) DEFAULT_CHECKIN_DECORATOR;
        private Action closeAction = () -> {
        };

        private Builder() {
        }

        public Builder<T> factory(Callable<? extends T> factory) {
            Preconditions.checkNotNull(factory);
            this.factory = factory;
            return this;
        }

        public Builder<T> healthy(Predicate<? super T> healthy) {
            Preconditions.checkNotNull(healthy);
            this.healthy = healthy;
            return this;
        }

        public Builder<T> idleTimeBeforeHealthCheckMs(long value) {
            Preconditions.checkArgument(value >= 0);
            this.idleTimeBeforeHealthCheckMs = value;
            return this;
        }

        public Builder<T> idleTimeBeforeHealthCheck(long value, TimeUnit unit) {
            return idleTimeBeforeHealthCheckMs(unit.toMillis(value));
        }

        public Builder<T> maxIdleTimeMs(long value) {
            this.maxIdleTimeMs = value;
            return this;
        }

        public Builder<T> maxIdleTime(long value, TimeUnit unit) {
            return maxIdleTimeMs(unit.toMillis(value));
        }

        public Builder<T> checkoutRetryIntervalMs(long value) {
            checkoutRetryIntervalMs = value;
            return this;
        }

        public Builder<T> checkoutRetryInterval(long value, TimeUnit unit) {
            return checkoutRetryIntervalMs(unit.toMillis(value));
        }

        public Builder<T> disposer(Consumer<? super T> disposer) {
            Preconditions.checkNotNull(disposer);
            this.disposer = disposer;
            return this;
        }

        public Builder<T> maxSize(int maxSize) {
            Preconditions.checkArgument(maxSize > 0);
            this.maxSize = maxSize;
            return this;
        }

        public Builder<T> returnToPoolDelayAfterHealthCheckFailureMs(long retryDelayMs) {
            Preconditions.checkArgument(retryDelayMs >= 0);
            this.returnToPoolDelayAfterHealthCheckFailureMs = retryDelayMs;
            return this;
        }

        public Builder<T> returnToPoolDelayAfterHealthCheckFailure(long value, TimeUnit unit) {
            Preconditions.checkNotNull(unit);
            return returnToPoolDelayAfterHealthCheckFailureMs(unit.toMillis(value));
        }

        public Builder<T> scheduler(Scheduler scheduler) {
            Preconditions.checkNotNull(scheduler);
            this.scheduler = scheduler;
            return this;
        }

        public Builder<T> checkinDecorator(BiFunction<? super T, ? super Checkin, ? extends T> f) {
            this.checkinDecorator = f;
            return this;
        }

        public Builder<T> onClose(Action closeAction) {
            this.closeAction = closeAction;
            return this;
        }

        public NonBlockingPool<T> build() {
            return new NonBlockingPool<T>(factory, healthy, disposer, maxSize,
                    returnToPoolDelayAfterHealthCheckFailureMs, idleTimeBeforeHealthCheckMs,
                    maxIdleTimeMs, checkoutRetryIntervalMs, checkinDecorator, scheduler,
                    closeAction);
        }

    }

}
