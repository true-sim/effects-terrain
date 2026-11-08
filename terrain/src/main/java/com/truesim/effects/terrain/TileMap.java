package com.truesim.effects.terrain;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * Tiles by their packed key ({@link TerrainGrid#key}): open addressing with linear probing over a power-of-two table,
 * so looking a tile up allocates nothing (a {@code HashMap<Long, ...>} boxes every key).
 */
final class TileMap {

    private static final long EMPTY = Long.MIN_VALUE;

    private long[] keys = new long[256];
    private Tile[] values = new Tile[256];
    private int size;

    TileMap() {
        Arrays.fill(keys, EMPTY);
    }

    int size() {
        return size;
    }

    /** The tile of {@code key}, or null. */
    Tile get(long key) {
        int mask = keys.length - 1;
        for (int slot = slot(key, mask); ; slot = (slot + 1) & mask) {
            long k = keys[slot];
            if (k == key) {
                return values[slot];
            }
            if (k == EMPTY) {
                return null;
            }
        }
    }

    @AllowAllocation("growing, as tiles come")
    void put(long key, Tile tile) {
        if (2 * (size + 1) > keys.length) {
            grow();
        }
        int mask = keys.length - 1;
        int slot = slot(key, mask);
        while (keys[slot] != EMPTY && keys[slot] != key) {
            slot = (slot + 1) & mask;
        }
        if (keys[slot] == EMPTY) {
            size++;
        }
        keys[slot] = key;
        values[slot] = tile;
    }

    void remove(long key) {
        int mask = keys.length - 1;
        int slot = slot(key, mask);
        while (keys[slot] != key) {
            if (keys[slot] == EMPTY) {
                return;
            }
            slot = (slot + 1) & mask;
        }
        keys[slot] = EMPTY;
        values[slot] = null;
        size--;
        // Re-place the run after it, so probes still find every key
        for (int next = (slot + 1) & mask; keys[next] != EMPTY; next = (next + 1) & mask) {
            long k = keys[next];
            Tile v = values[next];
            keys[next] = EMPTY;
            values[next] = null;
            size--;
            put(k, v);
        }
    }

    /** The table's capacity: slots to iterate with {@link #at}. */
    int capacity() {
        return keys.length;
    }

    /** The tile in slot {@code slot}, or null. */
    Tile at(int slot) {
        return values[slot];
    }

    private void grow() {
        long[] oldKeys = keys;
        Tile[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new Tile[oldKeys.length * 2];
        Arrays.fill(keys, EMPTY);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }
}
