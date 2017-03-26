

package io.norberg.http2;

import com.koloboke.collect.hash.HashConfig;
import com.koloboke.collect.impl.IntArrays;
import com.koloboke.collect.impl.Maths;
import com.koloboke.collect.impl.PrimitiveConstants;
import com.koloboke.collect.impl.UnsafeConstants;
import com.koloboke.collect.impl.hash.HashConfigWrapper;
import com.koloboke.collect.impl.hash.LHash;
import com.koloboke.collect.impl.hash.LHashCapacities;

import java.util.ConcurrentModificationException;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Generated;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Generated(value = "com.koloboke.compile.processor.KolobokeCollectionProcessor")
@SuppressFBWarnings(value = { "IA_AMBIGUOUS_INVOCATION_OF_INHERITED_OR_OUTER_METHOD" })
@SuppressWarnings(value = { "all" , "unsafe" , "deprecation" , "overloads" , "rawtypes" })
final class CustomKolobokeIntIntIndex {
    CustomKolobokeIntIntIndex(int expectedSize) {
        this.init(DEFAULT_CONFIG_WRAPPER, expectedSize);
    }

    static void verifyConfig(HashConfig config) {
        if ((config.getGrowthFactor()) != 2.0) {
            throw new IllegalArgumentException(((((((config + " passed, HashConfig for a hashtable\n") + "implementation with linear probing must have growthFactor of 2.0.\n") + "A Koloboke Compile-generated hashtable implementation could have\n") + "a different growth factor, if the implemented type is annotated with\n") + "@com.koloboke.compile.hash.algo.openaddressing.QuadraticProbing or\n") + "@com.koloboke.compile.hash.algo.openaddressing.DoubleHashing"));
        }
    }

    int freeValue;

    long[] table;

    public final boolean isEmpty() {
        return (size()) == 0;
    }

    private HashConfigWrapper configWrapper;

    int size;

    private int maxSize;

    private int modCount = 0;

    public int capacity() {
        return table.length;
    }

    final void init(HashConfigWrapper configWrapper, int size, int freeValue) {
        this.freeValue = freeValue;
        init(configWrapper, size);
    }

    public final int size() {
        return size;
    }

    public final int modCount() {
        return modCount;
    }

    final void incrementModCount() {
        (modCount)++;
    }

    public int defaultValue() {
        return 0;
    }

    int index(int key) {
        int free;
        if (key != (free = freeValue)) {
            long[] tab = table;
            int capacityMask;
            int index;
            int cur;
            long entry;
            if ((cur = ((int) (entry = tab[(index = (LHash.ParallelKVIntKeyMixing.mix(key)) & (capacityMask = (tab.length) - 1))]))) == key) {
                return index;
            } else {
                if (cur == free) {
                    return -1;
                } else {
                    while (true) {
                        if ((cur = ((int) (entry = tab[(index = (index - 1) & capacityMask)]))) == key) {
                            return index;
                        } else if (cur == free) {
                            return -1;
                        }
                    }
                }
            }
        } else {
            return -1;
        }
    }

    final void init(HashConfigWrapper configWrapper, int size) {
        verifyConfig(configWrapper.config());
        this.configWrapper = configWrapper;
        this.size = 0;
        internalInit(targetCapacity(size));
    }

    private void internalInit(int capacity) {
        assert Maths.isPowerOf2(capacity);
        maxSize = maxSize(capacity);
        allocateArrays(capacity);
    }

    private int maxSize(int capacity) {
        return !(isMaxCapacity(capacity)) ? configWrapper.maxSize(capacity) : capacity - 1;
    }

    private int findNewFreeOrRemoved() {
        int free = this.freeValue;
        Random random = ThreadLocalRandom.current();
        int newFree;
        {
            do {
                newFree = ((int) (random.nextInt()));
            } while ((newFree == free) || ((index(newFree)) >= 0) );
        }
        return newFree;
    }

    int changeFree() {
        int mc = modCount();
        int newFree = findNewFreeOrRemoved();
        incrementModCount();
        mc++;
        IntArrays.replaceAllKeys(table, freeValue, newFree);
        this.freeValue = newFree;
        if (mc != (modCount()))
            throw new ConcurrentModificationException();

        return newFree;
    }

    final void initForRehash(int newCapacity) {
        (modCount)++;
        internalInit(newCapacity);
    }

    void allocateArrays(int capacity) {
        table = new long[capacity];
        if ((freeValue) != 0)
            IntArrays.fillKeys(table, freeValue);

    }

    public int get(int key) {
        int free;
        if (key != (free = freeValue)) {
            long[] tab = table;
            int capacityMask;
            int index;
            int cur;
            long entry;
            if ((cur = ((int) (entry = tab[(index = (LHash.ParallelKVIntKeyMixing.mix(key)) & (capacityMask = (tab.length) - 1))]))) == key) {
                return ((int) (entry >>> 32));
            } else {
                if (cur == free) {
                    return defaultValue();
                } else {
                    while (true) {
                        if ((cur = ((int) (entry = tab[(index = (index - 1) & capacityMask)]))) == key) {
                            return ((int) (entry >>> 32));
                        } else if (cur == free) {
                            return defaultValue();
                        }
                    }
                }
            }
        } else {
            return defaultValue();
        }
    }

    final void postInsertHook() {
        if ((++(size)) > (maxSize)) {
            int capacity = capacity();
            if (!(isMaxCapacity(capacity))) {
                rehash((capacity << 1));
            }
        }
    }

    boolean doubleSizedArrays() {
        return false;
    }

    private int targetCapacity(int size) {
        return LHashCapacities.capacity(configWrapper, size, doubleSizedArrays());
    }

    private boolean isMaxCapacity(int capacity) {
        return LHashCapacities.isMaxCapacity(capacity, doubleSizedArrays());
    }

    @SuppressFBWarnings(value = "EC_UNRELATED_TYPES_USING_POINTER_EQUALITY")
    @Override
    public String toString() {
        if (this.isEmpty())
            return "{}";

        StringBuilder sb = new StringBuilder();
        int elementCount = 0;
        int mc = modCount();
        int free = freeValue;
        long[] tab = table;
        long entry;
        for (int i = (tab.length) - 1; i >= 0; i--) {
            int key;
            if ((key = ((int) (entry = tab[i]))) != free) {
                sb.append(' ');
                sb.append(key);
                sb.append('=');
                sb.append(((int) (entry >>> 32)));
                sb.append(',');
                if ((++elementCount) == 8) {
                    int expectedLength = (sb.length()) * ((size()) / 8);
                    sb.ensureCapacity((expectedLength + (expectedLength / 2)));
                }
            }
        }
        if (mc != (modCount()))
            throw new ConcurrentModificationException();

        sb.setCharAt(0, '{');
        sb.setCharAt(((sb.length()) - 1), '}');
        return sb.toString();
    }

    void rehash(int newCapacity) {
        int mc = modCount();
        int free = freeValue;
        long[] tab = table;
        long entry;
        initForRehash(newCapacity);
        mc++;
        long[] newTab = table;
        int capacityMask = (newTab.length) - 1;
        for (int i = (tab.length) - 1; i >= 0; i--) {
            int key;
            if ((key = ((int) (entry = tab[i]))) != free) {
                int index;
                if ((UnsafeConstants.U.getInt(newTab, (((UnsafeConstants.LONG_BASE) + (UnsafeConstants.INT_KEY_OFFSET)) + (((long) (index = (LHash.ParallelKVIntKeyMixing.mix(key)) & capacityMask)) << (UnsafeConstants.LONG_SCALE_SHIFT))))) != free) {
                    while (true) {
                        if ((UnsafeConstants.U.getInt(newTab, (((UnsafeConstants.LONG_BASE) + (UnsafeConstants.INT_KEY_OFFSET)) + (((long) (index = (index - 1) & capacityMask)) << (UnsafeConstants.LONG_SCALE_SHIFT))))) == free) {
                            break;
                        }
                    }
                }
                newTab[index] = entry;
            }
        }
        if (mc != (modCount()))
            throw new ConcurrentModificationException();

    }

    public int put(int key, int value) {
        int free;
        if (key == (free = freeValue)) {
            free = changeFree();
        }
        long[] tab = table;
        int capacityMask;
        int index;
        int cur;
        long entry;
        if ((cur = ((int) (entry = tab[(index = (LHash.ParallelKVIntKeyMixing.mix(key)) & (capacityMask = (tab.length) - 1))]))) == free) {
            incrementModCount();
            tab[index] = (((long) (key)) & (PrimitiveConstants.INT_MASK)) | (((long) (value)) << 32);
            postInsertHook();
            return defaultValue();
        } else {
            keyPresent : if (cur != key) {
                while (true) {
                    if ((cur = ((int) (entry = tab[(index = (index - 1) & capacityMask)]))) == free) {
                        incrementModCount();
                        tab[index] = (((long) (key)) & (PrimitiveConstants.INT_MASK)) | (((long) (value)) << 32);
                        postInsertHook();
                        return defaultValue();
                    } else if (cur == key) {
                        break keyPresent;
                    }
                }
            }
            int prevValue = ((int) (entry >>> 32));
            UnsafeConstants.U.putInt(tab, (((UnsafeConstants.LONG_BASE) + (UnsafeConstants.INT_VALUE_OFFSET)) + (((long) (index)) << (UnsafeConstants.LONG_SCALE_SHIFT))), value);
            return prevValue;
        }
    }

    CustomKolobokeIntIntIndex(HashConfig hashConfig, int expectedSize) {
        this.init(new HashConfigWrapper(hashConfig), expectedSize);
    }

    static class Support {    }

    static final HashConfigWrapper DEFAULT_CONFIG_WRAPPER = new HashConfigWrapper(HashConfig.getDefault());
}