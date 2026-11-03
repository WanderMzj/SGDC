package sgdc.dc.helpers;

import ch.javasoft.bitset.IBitSet;
import ch.javasoft.bitset.LongBitSet;

/**
 * Compact hash set / map keyed by bit sets of at most {@code 64 * words} bits (predicate sets of the search).
 *
 * <p>Replaces {@code HashSet<IBitSet>} and {@code Map<IBitSet, Double>} for the large tables of the search
 * (visited, pruned, coverage cache): a key is stored inline as {@code words} longs and the values as
 * {@code values} doubles per slot, in open-addressing arrays (linear probing, load factor <= 0.6). With 2-word
 * keys an entry takes about 16 bytes (set) / 32 bytes (map with two values) divided by the load factor, instead of
 * about 90 / 165 bytes with boxed objects. The table is split into 64 segments selected by the high hash bits; each
 * segment grows on its own, so a resize only copies 1/64 of the table (no transient doubling of the whole table).
 *
 * <p>Equality is the set equality of {@link LongBitSet#equals} (trailing zero words are irrelevant). The empty set
 * (all words 0) is the slot marker "free" and is kept in separate fields. Keys with a bit at index
 * {@code >= 64 * words} are rejected. Not thread-safe.
 */
public final class BitSetTable {

    private static final int SEG_BITS = 6, SEGS = 1 << SEG_BITS;
    private static final double MAX_LOAD = 0.6;
    private static final int INITIAL_CAPACITY = 1 << 10;    // slots per segment

    private final int w;            // words per key
    private final int nv;           // doubles per entry (0 = set)
    private final long[][] keys = new long[SEGS][];
    private final double[][] vals = new double[SEGS][];
    private final int[] used = new int[SEGS];
    private boolean emptyPresent;
    private final double[] emptyVals;
    private long size;

    /**
     * @param words  words per key: 1 + (largest predicate bit index) / 64
     * @param values doubles stored per key (0 for a set)
     */
    public BitSetTable(int words, int values) {
        if (words < 1) throw new IllegalArgumentException("words must be >= 1: " + words);
        this.w = words;
        this.nv = values;
        this.emptyVals = new double[values];
        for (int s = 0; s < SEGS; s++) {
            keys[s] = new long[INITIAL_CAPACITY * w];
            if (nv > 0) vals[s] = new double[INITIAL_CAPACITY * nv];
        }
    }

    /** Words needed for bit indices 0 .. maxBitIndex. */
    public static int wordsFor(int maxBitIndex) {
        return 1 + Math.max(0, maxBitIndex) / 64;
    }

    public long size() {
        return size;
    }

    public boolean contains(IBitSet s) {
        LongBitSet b = check(s);
        if (isEmpty(b)) return emptyPresent;
        long h = hash(b);
        int seg = (int) (h >>> (64 - SEG_BITS));
        return find(seg, b, h) >= 0;
    }

    /** Adds s; returns false if it was present already. */
    public boolean add(IBitSet s) {
        return slotFor(check(s), true) != NEW_EMPTY_PRESENT_GUARD;
    }

    /** Value k of the entry of s, or {@code missing} if s is not present. */
    public double get(IBitSet s, int k, double missing) {
        LongBitSet b = check(s);
        if (isEmpty(b)) return emptyPresent ? emptyVals[k] : missing;
        long h = hash(b);
        int seg = (int) (h >>> (64 - SEG_BITS));
        int slot = find(seg, b, h);
        return slot < 0 ? missing : vals[seg][slot * nv + k];
    }

    /** Inserts or overwrites the entry of s with the given values (length = values per key). */
    public void put(IBitSet s, double... v) {
        LongBitSet b = check(s);
        if (isEmpty(b)) {
            if (!emptyPresent) { emptyPresent = true; size++; }
            System.arraycopy(v, 0, emptyVals, 0, nv);
            return;
        }
        long h = hash(b);
        int seg = (int) (h >>> (64 - SEG_BITS));
        int slot = find(seg, b, h);
        if (slot < 0) slot = insert(seg, b, h);
        System.arraycopy(v, 0, vals[seg], slot * nv, nv);
    }

    public void clear() {
        for (int s = 0; s < SEGS; s++) {
            keys[s] = new long[INITIAL_CAPACITY * w];
            if (nv > 0) vals[s] = new double[INITIAL_CAPACITY * nv];
            used[s] = 0;
        }
        emptyPresent = false;
        size = 0;
    }

    /** Approximate memory of the arrays in bytes. */
    public long bytes() {
        long b = 0;
        for (int s = 0; s < SEGS; s++) b += 8L * keys[s].length + (nv > 0 ? 8L * vals[s].length : 0);
        return b;
    }

    // ------------------------------------------------------------------

    private static final int NEW_EMPTY_PRESENT_GUARD = Integer.MIN_VALUE;

    /** add(): returns the slot, or NEW_EMPTY_PRESENT_GUARD if the key was present already. */
    private int slotFor(LongBitSet b, boolean insertIfAbsent) {
        if (isEmpty(b)) {
            if (emptyPresent) return NEW_EMPTY_PRESENT_GUARD;
            emptyPresent = true;
            size++;
            return -1;
        }
        long h = hash(b);
        int seg = (int) (h >>> (64 - SEG_BITS));
        if (find(seg, b, h) >= 0) return NEW_EMPTY_PRESENT_GUARD;
        return insertIfAbsent ? insert(seg, b, h) : -1;
    }

    private LongBitSet check(IBitSet s) {
        LongBitSet b = (LongBitSet) s;
        for (int i = w; i < b.allocatedUnits(); i++)
            if (b.unitAt(i) != 0L) throw new IllegalStateException("bit index beyond " + (64 * w - 1) + ": " + s);
        return b;
    }

    private boolean isEmpty(LongBitSet b) {
        for (int i = 0; i < w; i++) if (b.unitAt(i) != 0L) return false;
        return true;
    }

    private long hash(LongBitSet b) {
        long h = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < w; i++) {
            long z = b.unitAt(i) + 0x9E3779B97F4A7C15L * (i + 1);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            h = (h ^ (z ^ (z >>> 31))) * 0xFF51AFD7ED558CCDL;
        }
        return h ^ (h >>> 29);
    }

    /** Slot of b in segment seg, or -1. */
    private int find(int seg, LongBitSet b, long h) {
        long[] k = keys[seg];
        int mask = k.length / w - 1;
        int slot = (int) h & mask;
        while (true) {
            int o = slot * w;
            boolean free = true, eq = true;
            for (int i = 0; i < w; i++) {
                long x = k[o + i];
                if (x != 0L) free = false;
                if (x != b.unitAt(i)) eq = false;
            }
            if (free) return -1;
            if (eq) return slot;
            slot = (slot + 1) & mask;
        }
    }

    private int insert(int seg, LongBitSet b, long h) {
        if (used[seg] + 1 > MAX_LOAD * (keys[seg].length / w)) grow(seg);
        long[] k = keys[seg];
        int mask = k.length / w - 1;
        int slot = (int) h & mask;
        while (true) {
            int o = slot * w;
            boolean free = true;
            for (int i = 0; i < w; i++) if (k[o + i] != 0L) { free = false; break; }
            if (free) break;
            slot = (slot + 1) & mask;
        }
        for (int i = 0; i < w; i++) k[slot * w + i] = b.unitAt(i);
        used[seg]++;
        size++;
        return slot;
    }

    private void grow(int seg) {
        long[] ok = keys[seg];
        double[] ov = vals[seg];
        int oldCap = ok.length / w, cap = oldCap * 2;
        long[] nk = new long[cap * w];
        double[] nvArr = nv > 0 ? new double[cap * nv] : null;
        int mask = cap - 1;
        long[] word = new long[w];
        for (int s = 0; s < oldCap; s++) {
            boolean free = true;
            for (int i = 0; i < w; i++) { word[i] = ok[s * w + i]; if (word[i] != 0L) free = false; }
            if (free) continue;
            long h = hashWords(word);
            int slot = (int) h & mask;
            while (true) {
                boolean f = true;
                for (int i = 0; i < w; i++) if (nk[slot * w + i] != 0L) { f = false; break; }
                if (f) break;
                slot = (slot + 1) & mask;
            }
            System.arraycopy(word, 0, nk, slot * w, w);
            if (nv > 0) System.arraycopy(ov, s * nv, nvArr, slot * nv, nv);
        }
        keys[seg] = nk;
        if (nv > 0) vals[seg] = nvArr;
    }

    private long hashWords(long[] word) {
        long h = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < w; i++) {
            long z = word[i] + 0x9E3779B97F4A7C15L * (i + 1);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            h = (h ^ (z ^ (z >>> 31))) * 0xFF51AFD7ED558CCDL;
        }
        return h ^ (h >>> 29);
    }
}
