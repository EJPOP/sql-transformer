package cli;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Progress / heartbeat logger for long-running CLI jobs.
 */
public final class CliProgressMonitor {

    private static final AtomicInteger currentIndex = new AtomicInteger(0);
    private static volatile String currentKey = "";

    private CliProgressMonitor() {
    }

    public static void setCurrent(String key, int index1Based) {
        currentKey = (key == null) ? "" : key;
        currentIndex.set(Math.max(0, index1Based));
    }

    public static void startHeartbeat(int total) {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(30_000L);
                    int done = currentIndex.get();
                    System.out.println("[HEARTBEAT] running... " + done + "/" + total + " last=" + currentKey);
                }
            } catch (InterruptedException ignored) {
            }
        }, "alias-sql-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    public static void logProgress(int done, int total, int success, int skip,
                                   long loopStartNs, String lastKey) {
        long elapsed = (System.nanoTime() - loopStartNs) / 1_000_000L;

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        long usedMb = heap.getUsed() / (1024 * 1024);
        long maxMb = heap.getMax() / (1024 * 1024);

        System.out.printf("[PROGRESS] %d/%d success=%d skip=%d elapsed=%dms heap=%d/%dMB last=%s%n",
                done, total, success, skip, elapsed, usedMb, maxMb, lastKey);
    }
}
