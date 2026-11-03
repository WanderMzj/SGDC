package sgdc.dc.util;

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Heap use observed at garbage collections: the largest used heap right before a collection (close to the real
 * peak, <= -Xmx) and the largest used heap right after one (live data plus not yet collected old objects).
 * Summing the per-pool peaks of MemoryPoolMXBean instead over-estimates, because the pools peak at different times.
 */
public final class HeapWatch {
    private static final AtomicLong maxBefore = new AtomicLong(), maxAfter = new AtomicLong();
    private static volatile boolean started;

    private HeapWatch() {
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        Set<String> heapPools = new HashSet<>();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == MemoryType.HEAP) heapPools.add(p.getName());
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(gc instanceof NotificationEmitter)) continue;
            ((NotificationEmitter) gc).addNotificationListener((n, h) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) return;
                GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
                maxBefore.accumulateAndGet(sum(info.getGcInfo().getMemoryUsageBeforeGc(), heapPools), Math::max);
                maxAfter.accumulateAndGet(sum(info.getGcInfo().getMemoryUsageAfterGc(), heapPools), Math::max);
            }, null, null);
        }
    }

    private static long sum(Map<String, MemoryUsage> m, Set<String> heapPools) {
        long s = 0;
        for (Map.Entry<String, MemoryUsage> e : m.entrySet()) if (heapPools.contains(e.getKey())) s += e.getValue().getUsed();
        return s;
    }

    /** "peakBeforeGcMB=.. peakAfterGcMB=.. maxHeapMB=.." (the current use counts as well, in case no GC ran). */
    public static String report() {
        Runtime rt = Runtime.getRuntime();
        long now = rt.totalMemory() - rt.freeMemory();
        return String.format("peakBeforeGcMB=%d peakAfterGcMB=%d maxHeapMB=%d",
                Math.max(maxBefore.get(), now) >> 20, maxAfter.get() >> 20, rt.maxMemory() >> 20);
    }
}
