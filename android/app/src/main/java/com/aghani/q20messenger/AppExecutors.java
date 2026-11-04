package com.aghani.q20messenger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/** Calls and foreground navigation never wait behind thumbnail downloads. */
final class AppExecutors {
    static final ExecutorService CALLS = Executors.newFixedThreadPool(2);
    static final ExecutorService SYNC = Executors.newSingleThreadExecutor();
    static final ExecutorService THUMBNAILS = Executors.newFixedThreadPool(2);
    static final ExecutorService NETWORK = Executors.newFixedThreadPool(3);
    static final ExecutorService DATABASE = Executors.newFixedThreadPool(2);
    static final ExecutorService CHAT_PAGES = Executors.newFixedThreadPool(2);
    static final ExecutorService MEDIA_CLEANUP = Executors.newSingleThreadExecutor();
    static final ExecutorService PRELOAD = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory(){public Thread newThread(final Runnable task){return new Thread(new Runnable(){public void run(){android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);task.run();}},"Q20-preload");}});
    private AppExecutors() { }
}
