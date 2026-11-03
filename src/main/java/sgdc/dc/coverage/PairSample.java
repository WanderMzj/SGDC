package sgdc.dc.coverage;

import sgdc.dc.input.Input;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.sets.PredicateBitSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * A fixed, seeded stream of uniformly random ordered tuple pairs (t, s) with t != s,
 * shared by every coverage estimate (paper Sec. IV-B: the uniform guarantee over the
 * hypothesis space H requires one shared sample).
 *
 * For every predicate that is actually used, the sample keeps a bitmap whose j-th bit
 * says whether the j-th pair satisfies the predicate. K(t,s) for any DC is then obtained
 * with word-level AND / popcount, i.e. O(m) per 64 pairs instead of O(2^m) subset scans.
 *
 * The stream is extended lazily (doubling) when a DC needs more draws to collect n
 * accepted samples; already computed bitmaps are extended accordingly. Pair j is always
 * the same pair, so all estimates are deterministic for a fixed seed.
 *
 * Not thread-safe by itself; {@link CoverageProvider} serialises access.
 */
final class PairSample {

    private final Input input;
    private final int rows;
    private final Random random;
    private final int maxPairs;

    private int[] left = new int[0];
    private int[] right = new int[0];
    private int size = 0;                       // always a multiple of 64

    /** bitmaps indexed by predicate bit index (PredicateBitSet.indexProvider). */
    private final List<long[]> bitmaps = new ArrayList<>();

    PairSample(Input input, long seed, int initialPairs, int maxPairs) {
        this.input = input;
        this.rows = input.getLineCount();
        this.random = new Random(seed);
        this.maxPairs = roundUp64(Math.max(64, maxPairs));
        if (rows >= 2) {
            grow(roundUp64(Math.max(64, Math.min(initialPairs, this.maxPairs))));
        }
    }

    boolean isEmpty() {
        return size == 0;
    }

    int size() {
        return size;
    }

    int words() {
        return size >>> 6;
    }

    /** Doubles the stream (bounded by maxPairs). Returns false if it cannot grow. */
    boolean extend() {
        if (size == 0 || size >= maxPairs) return false;
        grow(Math.min(maxPairs, size * 2));
        return true;
    }

    /** Bitmap of the predicate with the given bit index over the current stream. */
    long[] bitmap(int predicateIndex) {
        while (bitmaps.size() <= predicateIndex) bitmaps.add(null);
        long[] bm = bitmaps.get(predicateIndex);
        if (bm == null) {
            bm = new long[words()];
            fill(PredicateBitSet.getPredicate(predicateIndex), bm, 0, size);
            bitmaps.set(predicateIndex, bm);
        }
        return bm;
    }

    private void grow(int newSize) {
        int old = size;
        left = Arrays.copyOf(left, newSize);
        right = Arrays.copyOf(right, newSize);
        for (int j = old; j < newSize; j++) {
            int t = random.nextInt(rows);
            int s = random.nextInt(rows - 1);
            if (s >= t) s++;                    // uniform over ordered pairs with t != s
            left[j] = t;
            right[j] = s;
        }
        size = newSize;
        for (int i = 0; i < bitmaps.size(); i++) {
            long[] bm = bitmaps.get(i);
            if (bm == null) continue;
            long[] ext = Arrays.copyOf(bm, newSize >>> 6);
            fill(PredicateBitSet.getPredicate(i), ext, old, newSize);
            bitmaps.set(i, ext);
        }
    }

    private void fill(Predicate p, long[] bm, int from, int to) {
        for (int j = from; j < to; j++) {
            if (p.satisfies(left[j], right[j])) {
                bm[j >>> 6] |= 1L << (j & 63);
            }
        }
    }

    private static int roundUp64(int x) {
        return ((x + 63) >>> 6) << 6;
    }
}
