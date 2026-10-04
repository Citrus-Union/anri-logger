package com.anrilogger.store;

import org.h2.mvstore.DataUtils;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;
import java.nio.ByteBuffer;

/** Full signed coordinates, without UUID/resource-name strings or coordinate packing limits. */
record PositionKey(int world, int dimension, int x, int y, int z) {
    static final Type TYPE = new Type();
    static final class Type extends BasicDataType<PositionKey> {
        @Override public int compare(PositionKey a, PositionKey b) {
            int c = Integer.compare(a.world, b.world);
            if (c == 0) c = Integer.compare(a.dimension, b.dimension);
            if (c == 0) c = Integer.compare(a.x, b.x);
            if (c == 0) c = Integer.compare(a.z, b.z);
            if (c == 0) c = Integer.compare(a.y, b.y);
            return c;
        }
        @Override public int getMemory(PositionKey key) { return 40; }
        @Override public void write(WriteBuffer out, PositionKey key) {
            out.putVarInt(key.world).putVarInt(key.dimension);
            coordinate(out, key.x); coordinate(out, key.y); coordinate(out, key.z);
        }
        @Override public PositionKey read(ByteBuffer in) {
            return new PositionKey(DataUtils.readVarInt(in), DataUtils.readVarInt(in), coordinate(in), coordinate(in), coordinate(in));
        }
        private static void coordinate(WriteBuffer out, int n) { out.putVarInt((n << 1) ^ (n >> 31)); }
        private static int coordinate(ByteBuffer in) { int n = DataUtils.readVarInt(in); return (n >>> 1) ^ -(n & 1); }
        @Override public PositionKey[] createStorage(int size) { return new PositionKey[size]; }
    }
}
