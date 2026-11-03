package sgdc.dc.algorithms.sgdc;

import ch.javasoft.bitset.IBitSet;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.util.concurrent.AtomicLongMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sgdc.dc.denialcontraints.DenialConstraint;
import sgdc.dc.evidenceset.HashEvidenceSet;
import sgdc.dc.evidenceset.IEvidenceSet;
import sgdc.dc.evidenceset.build.PartitionEvidenceSetBuilder;
import sgdc.dc.helpers.IndexProvider;
import sgdc.dc.helpers.SuperSetWalker;
import sgdc.dc.input.Input;
import sgdc.dc.paritions.ClusterPair;
import sgdc.dc.paritions.IEJoin;
import sgdc.dc.paritions.StrippedPartition;
import sgdc.dc.predicates.PartitionRefiner;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.PredicateBuilder;
import sgdc.dc.predicates.PredicatePair;
import sgdc.dc.predicates.sets.PredicateBitSet;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Exact validation of a batch of candidate DCs on the full relation (Hydra-style refinement with
 * partitions / IEJoin). For every candidate, all tuple pairs that satisfy all of its predicates
 * (= violations) are enumerated and their evidences are returned. A candidate is valid iff none of the
 * returned evidences contains its predicate set.
 *
 * <p>Fixes compared to the original: no static state (#13), no CoverageProvider instance (#13), the result
 * set is either written single-threaded or through task-local buffers merged into a concurrent set (#11),
 * task completion is tracked with a counter that starts at 1 for the root walk so the latch can neither open
 * early nor stay closed (#12), exceptions of worker tasks are rethrown, and a timeout marks the result as
 * incomplete instead of silently treating unchecked DCs as valid (#12).</p>
 */
public class Verification {

    private static final Logger log = LoggerFactory.getLogger(Verification.class);

    /** Result of one batch validation. */
    public static final class Result {
        /** evidences of all violating tuple pairs found (each contains the predicate set of a violated DC) */
        public final Set<PredicateBitSet> violationEvidence;
        /** false iff the time budget ran out; then the batch must not be treated as validated */
        public final boolean complete;
        /** number of partition refinements executed */
        public final long refinements;

        Result(Set<PredicateBitSet> violationEvidence, boolean complete, long refinements) {
            this.violationEvidence = violationEvidence;
            this.complete = complete;
            this.refinements = refinements;
        }
    }

    private final Input input;
    private final PredicateBuilder predicates;
    private final int threads;
    private final long budgetNanos;
    private long usedNanos = 0L;

    // built lazily once, re-used for every batch
    private int[][] values;
    private IEJoin iejoin;
    private PartitionEvidenceSetBuilder builder;
    private ClusterPair startPartition;

    public Verification(Input input, PredicateBuilder predicates) {
        this(input, predicates,
                Integer.getInteger("sgdc.verify.threads",
                        Math.max(1, Runtime.getRuntime().availableProcessors() - 1)),
                TimeUnit.MINUTES.toNanos(Long.getLong("sgdc.verify.timeoutMin", 120L)));
    }

    public Verification(Input input, PredicateBuilder predicates, int threads, long budgetNanos) {
        this.input = input;
        this.predicates = predicates;
        this.threads = Math.max(1, threads);
        this.budgetNanos = budgetNanos;
    }

    /** true once the cumulative verification time budget is used up */
    public boolean isExhausted() {
        return usedNanos >= budgetNanos;
    }

    private void init() {
        if (values != null) return;
        values = input.getInts();
        iejoin = new IEJoin(values);
        builder = new PartitionEvidenceSetBuilder(predicates, values);
        startPartition = StrippedPartition.getFullParition(input.getLineCount());
    }

    public Result verify(Collection<DenialConstraint> dcs, IEvidenceSet sampleEvidence) {
        if (dcs.isEmpty()) return new Result(Collections.emptySet(), true, 0);
        init();
        final long start = System.nanoTime();
        final long deadline = start + Math.max(0L, budgetNanos - usedNanos);

        Multiset<PredicatePair> paircountDC = frequencyEstimationForPredicatePairs(dcs);
        long[] evidenceCount = new long[1];
        AtomicLongMap<PartitionRefiner> selectivityCount =
                createSelectivityEstimation(sampleEvidence, paircountDC.elementSet(), evidenceCount);
        ArrayList<PredicatePair> sortedPredicatePairs = getSortedPredicatePairs(paircountDC, selectivityCount);

        IndexProvider<PartitionRefiner> indexProvider = new IndexProvider<>();
        Map<IBitSet, List<DenialConstraint>> predicateDCMap = groupDCs(dcs, sortedPredicatePairs, indexProvider);
        int[] refinerPriorities = getRefinerPriorities(selectivityCount, indexProvider, predicateDCMap);

        SuperSetWalker walker = new SuperSetWalker(predicateDCMap.keySet(), refinerPriorities, selectivityCount,
                indexProvider, (double) evidenceCount[0]);

        final boolean[] timedOut = {false};
        final long[] refinements = {0};
        Result result;
        if (threads == 1) {
            HashEvidenceSet out = new HashEvidenceSet();
            final long wall0 = System.nanoTime(), cpu0 = threadCpu();
            walker.walk(inter -> {
                if (System.nanoTime() > deadline) { timedOut[0] = true; return; }
                refinements[0]++;
                PartitionRefiner refiner = indexProvider.getObject(inter.newRefiner);
                ClusterPair partition = (inter.clusterPair != null) ? inter.clusterPair : startPartition;
                partition.refine(refiner, iejoin, consumerFor(inter, predicateDCMap, out));
            });
            record(System.nanoTime() - wall0, threadCpu() - cpu0, 0);
            result = new Result(out.getSetOfPredicateSets(), !timedOut[0], refinements[0]);
        } else {
            result = verifyParallel(walker, indexProvider, predicateDCMap, deadline);
        }
        usedNanos += System.nanoTime() - start;
        if (!result.complete) {
            log.warn("verification time budget exhausted ({} min): batch of {} DCs NOT fully validated",
                    TimeUnit.NANOSECONDS.toMinutes(budgetNanos), dcs.size());
        }
        return result;
    }

    /**
     * Pairs that reach the refiner set of a DC violate it -> add their evidences. Refiner sets that are
     * supersets of this one select a subset of these pairs, so their evidences are already included and the
     * walk does not need to descend further.
     */
    private Consumer<ClusterPair> consumerFor(SuperSetWalker.InterResult inter,
                                              Map<IBitSet, List<DenialConstraint>> predicateDCMap,
                                              IEvidenceSet out) {
        return clusterPair -> {
            if (clusterPair == null || clusterPair.getLinePairCount() == 0) return;
            if (predicateDCMap.containsKey(inter.currentBits)) {
                builder.addEvidences(clusterPair, out);
            } else {
                inter.nextRefiner.accept(clusterPair);
            }
        };
    }

    private Result verifyParallel(SuperSetWalker walker, IndexProvider<PartitionRefiner> indexProvider,
                                  Map<IBitSet, List<DenialConstraint>> predicateDCMap, long deadline) {
        Set<PredicateBitSet> out = ConcurrentHashMap.newKeySet();
        List<Thread> poolThreads = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            poolThreads.add(t);
            return t;
        });
        AtomicInteger pending = new AtomicInteger(1);          // 1 = the root walk itself
        AtomicInteger maxPending = new AtomicInteger(1);
        // Bounded queue: every queued task holds the partition of its parent. Unbounded, the walk produces
        // tasks much faster than the pool consumes them and the queued partitions exhaust the heap (tax500k,
        // tau = 0.45). When all permits are taken the refinement runs inline in the calling thread, i.e.
        // depth-first and synchronously as in the single-threaded walk. The evidence set is the same either way.
        Semaphore permits = new Semaphore(MAX_QUEUED_PER_THREAD > 0 ? MAX_QUEUED_PER_THREAD * threads : Integer.MAX_VALUE);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        final boolean[] timedOut = {false};
        java.util.concurrent.atomic.AtomicLong refinements = new java.util.concurrent.atomic.AtomicLong();
        final long wall0 = System.nanoTime(), main0 = threadCpu();

        Consumer<SuperSetWalker.InterResult> visit = new Consumer<SuperSetWalker.InterResult>() {
            @Override
            public void accept(SuperSetWalker.InterResult inter) {
                if (error.get() != null) return;
                if (System.nanoTime() > deadline) { synchronized (timedOut) { timedOut[0] = true; } return; }
                PartitionRefiner refiner = indexProvider.getObject(inter.newRefiner);
                ClusterPair partition = (inter.clusterPair != null) ? inter.clusterPair : startPartition;
                // queue full or heap above the guard: refine here (depth-first); otherwise queue the task
                if (BOUNDED && (!heapBelowGuard() || !permits.tryAcquire())) {
                    HashEvidenceSet local = new HashEvidenceSet();
                    try {
                        refinements.incrementAndGet();
                        partition.refine(refiner, iejoin, consumerFor(inter, predicateDCMap, local));
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        out.addAll(local.getSetOfPredicateSets());
                    }
                    return;
                }
                maxPending.accumulateAndGet(pending.incrementAndGet(), Math::max);
                try {
                    pool.execute(() -> {
                        HashEvidenceSet local = new HashEvidenceSet();
                        try {
                            refinements.incrementAndGet();
                            partition.refine(refiner, iejoin, consumerFor(inter, predicateDCMap, local));
                        } catch (Throwable t) {
                            error.compareAndSet(null, t);
                        } finally {
                            out.addAll(local.getSetOfPredicateSets());
                            if (BOUNDED) permits.release();
                            if (pending.decrementAndGet() == 0) done.countDown();
                        }
                    });
                } catch (RejectedExecutionException e) {
                    error.compareAndSet(null, e);
                    if (BOUNDED) permits.release();
                    if (pending.decrementAndGet() == 0) done.countDown();
                }
            }
        };
        try {
            walker.walk(visit);
        } catch (Throwable t) {
            error.compareAndSet(null, t);
        } finally {
            if (pending.decrementAndGet() == 0) done.countDown();
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error.compareAndSet(null, e);
        } finally {
            long cpu = threadCpu() - main0;                   // pool threads are idle but alive: read before shutdown
            synchronized (poolThreads) { for (Thread t : poolThreads) cpu += Math.max(0L, THREADS.getThreadCpuTime(t.getId())); }
            record(System.nanoTime() - wall0, cpu, maxPending.get());
            pool.shutdown();
        }
        if (error.get() != null) throw new IllegalStateException("verification failed", error.get());
        boolean to;
        synchronized (timedOut) { to = timedOut[0]; }
        return new Result(out, !to, refinements.get());
    }

    /* -Dsgdc.verify.bounded (default true): bound the tasks queued by the parallel walk; false = original behaviour */
    static final boolean BOUNDED = Boolean.parseBoolean(System.getProperty("sgdc.verify.bounded", "true"));
    /* -Dsgdc.verify.maxQueuedPerThread: queued tasks allowed per worker thread */
    static final int MAX_QUEUED_PER_THREAD = Integer.getInteger("sgdc.verify.maxQueuedPerThread", 64);   // <= 0: no limit
    /* -Dsgdc.verify.memGuard: no new task is queued while the used heap exceeds this fraction of -Xmx (0 = off) */
    static final double MEM_GUARD = Double.parseDouble(System.getProperty("sgdc.verify.memGuard", "0.7"));
    private static final long MAX_HEAP = Runtime.getRuntime().maxMemory();

    private static boolean heapBelowGuard() {
        if (MEM_GUARD <= 0) return true;
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory() < MEM_GUARD * MAX_HEAP;
    }

    /** Sum of the peak usages of the heap memory pools since JVM start (an upper bound of the peak heap use). */
    public static long peakHeapBytes() {
        long s = 0;
        for (java.lang.management.MemoryPoolMXBean b : java.lang.management.ManagementFactory.getMemoryPoolMXBeans())
            if (b.getType() == java.lang.management.MemoryType.HEAP && b.getPeakUsage() != null) s += b.getPeakUsage().getUsed();
        return s;
    }

    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
    private long statBatches, statWallNs, statCpuNs;
    private int statMaxPending;

    private static long threadCpu() {
        return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : 0L;
    }

    private void record(long wallNs, long cpuNs, int maxPending) {
        statBatches++; statWallNs += wallNs; statCpuNs += cpuNs; statMaxPending = Math.max(statMaxPending, maxPending);
        log.debug("verify batch: wall={}ms cpu={}ms cores={} maxPending={}", wallNs / 1_000_000, cpuNs / 1_000_000,
                String.format("%.1f", wallNs == 0 ? 0.0 : (double) cpuNs / wallNs), maxPending);
    }

    /** Cumulative statistics of the parallel validation (wall time, CPU time of all threads, average busy cores). */
    public String parallelStats() {
        return String.format("verifyBatches=%d verifyWall=%dms verifyCpu=%dms busyCores=%.2f maxPending=%d bounded=%b maxQueued=%d memGuard=%.2f peakHeapMB=%d threads=%d",
                statBatches, statWallNs / 1_000_000, statCpuNs / 1_000_000,
                statWallNs == 0 ? 0.0 : (double) statCpuNs / statWallNs, statMaxPending, BOUNDED,
                MAX_QUEUED_PER_THREAD > 0 ? MAX_QUEUED_PER_THREAD * threads : -1, MEM_GUARD, peakHeapBytes() >> 20, threads);
    }

    // ------------------------------------------------------------------ refiner planning (as in Hydra)

    private int[] getRefinerPriorities(AtomicLongMap<PartitionRefiner> selectivityCount,
                                       IndexProvider<PartitionRefiner> indexProvider,
                                       Map<IBitSet, List<DenialConstraint>> predicateDCMap) {
        int[] counts = new int[indexProvider.size()];
        Arrays.fill(counts, 1);
        for (IBitSet bitset : predicateDCMap.keySet()) {
            for (int i = bitset.nextSetBit(0); i >= 0; i = bitset.nextSetBit(i + 1)) counts[i]++;
        }
        List<PartitionRefiner> refiners = new ArrayList<>();
        for (int i = 0; i < indexProvider.size(); ++i) refiners.add(indexProvider.getObject(i));
        // most selective first (information gain per use); ties broken by index for determinism
        refiners.sort((r2, r1) -> {
            if (EQ_FIRST) {
                int t = Integer.compare(tier(r2), tier(r1));
                if (t != 0) return t;
            }
            int c = Double.compare(1.0d * counts[indexProvider.getIndex(r1)] / Math.max(1, selectivityCount.get(r1)),
                    1.0d * counts[indexProvider.getIndex(r2)] / Math.max(1, selectivityCount.get(r2)));
            return c != 0 ? c : Integer.compare(indexProvider.getIndex(r2), indexProvider.getIndex(r1));
        });
        int[] priorityIndex = new int[indexProvider.size()];
        int rank = 0;
        for (PartitionRefiner refiner : refiners) priorityIndex[indexProvider.getIndex(refiner)] = rank++;
        return priorityIndex;
    }

    /** -Dsgdc.eqFirst (default true): equality partitions (linear cost, bounded output) are applied before
     *  anti-joins and IEJoins, whose ClusterPair output can be quadratic on large clusters. Only the order of the
     *  refinements changes; the set of violating pairs (and thus the evidence) is identical. */
    static final boolean EQ_FIRST = Boolean.parseBoolean(System.getProperty("sgdc.eqFirst", "true"));

    private static int tier(PartitionRefiner r) {
        if (r instanceof Predicate) {
            Predicate p = (Predicate) r;
            if (p.getOperand1().getIndex() == p.getOperand2().getIndex()) return 0;      // single-tuple filter
            if (p.getOperator() == de.metanome.algorithm_integration.Operator.EQUAL) return 0;
            return 1;
        }
        return 1;
    }

    private Map<IBitSet, List<DenialConstraint>> groupDCs(Collection<DenialConstraint> dcs,
                                                          List<PredicatePair> sortedPredicatePairs,
                                                          IndexProvider<PartitionRefiner> indexProvider) {
        Map<IBitSet, List<DenialConstraint>> predicateDCMap = new HashMap<>();
        HashMap<PredicatePair, Integer> prios = new HashMap<>();
        for (int i = 0; i < sortedPredicatePairs.size(); ++i) prios.put(sortedPredicatePairs.get(i), i);
        for (DenialConstraint dc : dcs) {
            Set<PartitionRefiner> refinerSet = getRefinerSet(prios, dc);
            predicateDCMap.computeIfAbsent(indexProvider.getBitSet(refinerSet), k -> new ArrayList<>()).add(dc);
        }
        return predicateDCMap;
    }

    private Set<PartitionRefiner> getRefinerSet(Map<PredicatePair, Integer> prios, DenialConstraint dc) {
        Set<PartitionRefiner> refinerSet = new LinkedHashSet<>();
        Set<Predicate> pairSet = new LinkedHashSet<>();
        dc.getPredicateSet().forEach(p -> {
            if (StrippedPartition.isSingleSupported(p)) refinerSet.add(p);
            else pairSet.add(p);
        });
        while (pairSet.size() > 1) {
            PredicatePair bestP = getBest(prios, pairSet);
            refinerSet.add(bestP);
            pairSet.remove(bestP.getP1());
            pairSet.remove(bestP.getP2());
        }
        if (!pairSet.isEmpty()) refinerSet.add(pairSet.iterator().next());
        return refinerSet;
    }

    /** highest-priority pair; falls back to the first two predicates if no pair has a priority */
    private PredicatePair getBest(Map<PredicatePair, Integer> prios, Set<Predicate> pairSet) {
        int best = -1;
        PredicatePair bestP = null;
        for (Predicate p1 : pairSet) {
            for (Predicate p2 : pairSet) {
                if (p1 == p2) continue;
                PredicatePair pair = new PredicatePair(p1, p2);
                Integer score = prios.get(pair);
                if (score != null && score > best) {
                    best = score;
                    bestP = pair;
                }
            }
        }
        if (bestP == null) {
            Iterator<Predicate> it = pairSet.iterator();
            bestP = new PredicatePair(it.next(), it.next());
        }
        return bestP;
    }

    private ArrayList<PredicatePair> getSortedPredicatePairs(Multiset<PredicatePair> paircountDC,
                                                             AtomicLongMap<PartitionRefiner> selectivityCount) {
        ArrayList<PredicatePair> sorted = new ArrayList<>(paircountDC.elementSet());
        sorted.sort(Comparator.comparingDouble(
                (PredicatePair o) -> -1.0d * selectivityCount.get(o) / Math.max(1, paircountDC.count(o)))
                .thenComparing(PredicatePair::toString));
        return sorted;
    }

    private Multiset<PredicatePair> frequencyEstimationForPredicatePairs(Collection<DenialConstraint> dcs) {
        Multiset<PredicatePair> paircountDC = HashMultiset.create();
        for (DenialConstraint dc : dcs) {
            dc.getPredicateSet().forEach(p1 -> {
                if (StrippedPartition.isPairSupported(p1)) {
                    dc.getPredicateSet().forEach(p2 -> {
                        if (!p1.equals(p2) && StrippedPartition.isPairSupported(p2)) {
                            paircountDC.add(new PredicatePair(p1, p2));
                        }
                    });
                }
            });
        }
        return paircountDC;
    }

    /** selectivity counts from the (weighted) random sample; evidenceCount[0] receives the total weight */
    private AtomicLongMap<PartitionRefiner> createSelectivityEstimation(IEvidenceSet sampleEvidence,
                                                                        Set<PredicatePair> predicatePairs,
                                                                        long[] evidenceCount) {
        AtomicLongMap<PartitionRefiner> selectivityCount = AtomicLongMap.create();
        long total = 0L;
        for (PredicateBitSet ps : sampleEvidence) {
            long count = Math.max(1L, sampleEvidence.getCount(ps));
            total += count;
            ps.forEach(p -> selectivityCount.addAndGet(p, count));
            for (PredicatePair pair : predicatePairs) {
                if (pair.bothContainedIn(ps)) selectivityCount.addAndGet(pair, count);
            }
        }
        evidenceCount[0] = total;
        return selectivityCount;
    }
}
