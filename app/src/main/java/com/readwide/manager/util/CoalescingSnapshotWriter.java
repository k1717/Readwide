package com.readwide.manager.util;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * One pending background task, with revision-ordered synchronous checkpoints.
 * Background capture may briefly lock the model, releasing it before returning.
 * Encoding then uses only owned snapshot data. A sink must not acquire the model
 * lock while holding this writer's I/O lock; callers may hold it for writeNow.
 * The supplied executor must enqueue asynchronously, never run on the caller.
 * Source snapshots and requests use the same monotonically increasing revision.
 */
final class CoalescingSnapshotWriter<T> {
    interface Source<T> { Snapshot<T> capture() throws Exception; }
    interface Encoder<T> { String encode(T value) throws Exception; }
    interface Sink { void write(String value) throws Exception; }

    static final class Snapshot<T> {
        final long revision;
        final T value;

        Snapshot(long revision, T value) {
            this.revision = revision;
            this.value = value;
        }
    }

    private final Executor executor;
    private final Source<T> source;
    private final Encoder<T> encoder;
    private final Sink sink;
    private final Consumer<Exception> onFailure;
    private final Object requestLock = new Object();
    private final Object writeLock = new Object();
    private volatile long requestedRevision;
    private volatile long writtenRevision;
    private Ticket active;

    CoalescingSnapshotWriter(Executor executor, Source<T> source, Encoder<T> encoder,
                             Sink sink, Consumer<Exception> onFailure) {
        this.executor = executor;
        this.source = source;
        this.encoder = encoder;
        this.sink = sink;
        this.onFailure = onFailure;
    }

    /** Nonblocking: never acquires the file-write lock or serializes a snapshot. */
    void request(long revision) {
        Ticket submit;
        synchronized (requestLock) {
            requestedRevision = Math.max(requestedRevision, revision);
            if (active != null || requestedRevision <= writtenRevision) return;
            active = submit = new Ticket();
        }
        try {
            executor.execute(submit);
        } catch (RuntimeException rejected) {
            synchronized (requestLock) {
                if (active == submit) active = null;
            }
            onFailure.accept(rejected);
        }
    }

    /** Retains existing synchronous durability for lifecycle/explicit operations. */
    void writeNow(Snapshot<T> snapshot) throws Exception {
        synchronized (requestLock) {
            requestedRevision = Math.max(requestedRevision, snapshot.revision);
        }
        encodeAndWrite(snapshot);
    }

    private void encodeAndWrite(Snapshot<T> snapshot) throws Exception {
        if (snapshot.revision <= writtenRevision || snapshot.revision < requestedRevision) return;
        String encoded = encoder.encode(snapshot.value);
        synchronized (writeLock) {
            // Encoding runs outside the lock. A newer save/delete/import may
            // have already committed while this older snapshot was being encoded.
            if (snapshot.revision <= writtenRevision || snapshot.revision < requestedRevision) return;
            sink.write(encoded);
            writtenRevision = snapshot.revision;
        }
    }

    private final class Ticket implements Runnable {
        @Override public void run() {
            try {
                while (true) {
                    long observed = requestedRevision;
                    try {
                        if (observed > writtenRevision) encodeAndWrite(source.capture());
                    } catch (Exception failure) {
                        onFailure.accept(failure);
                    }
                    synchronized (requestLock) {
                        if (requestedRevision > observed && requestedRevision > writtenRevision) continue;
                        // No retry loop on an unchanged failed snapshot. A later
                        // request or synchronous checkpoint can retry it.
                        if (active == this) active = null;
                        return;
                    }
                }
            } finally {
                // Unexpected errors must not permanently retain a queued token.
                // Identity protects a newer task scheduled after normal completion.
                synchronized (requestLock) {
                    if (active == this) active = null;
                }
            }
        }
    }
}
