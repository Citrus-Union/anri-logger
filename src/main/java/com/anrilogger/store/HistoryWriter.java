package com.anrilogger.store;
import java.util.concurrent.*;
import java.util.function.Function;
/** Bounded FIFO; disk failure is surfaced to the server, never silently discarded. */
public final class HistoryWriter implements AutoCloseable {
    private final HistoryStore store;
    private final ArrayBlockingQueue<Runnable> queue;
    private final Thread worker;
    private final int flushMillis;
    private volatile Throwable failure;
    private volatile boolean closing;
    public HistoryWriter(HistoryStore store, int capacity, int flushMillis) {
        this.store=store; this.queue=new ArrayBlockingQueue<>(capacity); this.flushMillis=flushMillis;
        worker=new Thread(this::run,"anri-logger-storage"); worker.setDaemon(true); worker.start();
    }
    private void run() {
        long lastFlush=System.nanoTime(); int pending=0;
        try {
            while(!closing || !queue.isEmpty()) {
                Runnable task=queue.poll(Math.max(1,flushMillis-(System.nanoTime()-lastFlush)/1_000_000),TimeUnit.MILLISECONDS);
                if(task!=null) { task.run(); pending++; }
                if(store.shouldFlush() || System.nanoTime()-lastFlush>=flushMillis*1_000_000L) {
                    if(pending>0)store.flush(); pending=0; lastFlush=System.nanoTime();
                }
            }
            store.close();
        } catch(Throwable ex) { failure=ex; }
    }
    private synchronized void submit(Runnable task) {
        if(closing) throw new IllegalStateException("History writer is closing");
        try {
            while(failure==null && worker.isAlive()) if(queue.offer(task,100,TimeUnit.MILLISECONDS))return;
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        throw new IllegalStateException("History storage failed",failure);
    }
    public void append(LogEvent event) { submit(() -> store.append(event)); }
    public <T> CompletableFuture<T> query(Function<HistoryStore,T> operation) {
        CompletableFuture<T> future=new CompletableFuture<>();
        // FIFO gives read-your-writes without waiting for fsync. Durability stays on the writer's commit schedule.
        try { submit(() -> { try { future.complete(operation.apply(store)); } catch(Throwable ex) { future.completeExceptionally(ex); throw ex; } }); }
        catch(Throwable ex) { future.completeExceptionally(ex); }
        return future.orTimeout(30,TimeUnit.SECONDS);
    }
    public int queued() { return queue.size(); }
    public Throwable failure() { return failure; }
    @Override public void close() {
        synchronized(this) {
            closing=true;
            // Wake an idle poll even when the configured flush interval exceeds the shutdown timeout.
            // A full queue already guarantees work is available; never interrupt an active disk write.
            queue.offer(() -> {});
        }
        try { worker.join(30000); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        if(worker.isAlive() || failure!=null)throw new IllegalStateException("History did not shut down cleanly",failure);
    }
}
