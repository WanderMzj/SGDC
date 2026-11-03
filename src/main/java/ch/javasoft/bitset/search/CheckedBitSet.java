package ch.javasoft.bitset.search;

import ch.javasoft.bitset.IBitSet;

public class CheckedBitSet {
    IBitSet bitset;
    boolean checked;

    public CheckedBitSet(IBitSet bitset, boolean checked) {
        this.bitset = bitset;
        this.checked = checked;
    }
    public IBitSet getBitset() {
        return bitset;
    }
    public void setBitset(IBitSet bitset) {
        this.bitset = bitset;
    }
    public boolean isChecked() {
        return checked;
    }
    public void setChecked(boolean checked) {
        this.checked = checked;
    }
}
