package ru.localdoc.domain;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Future<?>> current = new AtomicReference<>();
    public void cancel() {
        cancelled.set(true);
        Future<?> future = current.getAndSet(null);
        if (future != null) future.cancel(true);
    }
    public void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("Задача отменена.");
    }
    public void attach(Future<?> future) {
        current.set(future);
        if (cancelled.get()) { future.cancel(true); check(); }
    }
    public void detach(Future<?> future) { current.compareAndSet(future, null); }
}
