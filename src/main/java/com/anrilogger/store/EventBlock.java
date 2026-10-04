package com.anrilogger.store;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Self-contained compressed blocks: no permanent dictionary of unique causes or NBT. */
final class EventBlock {
    static final int MAX_EVENTS = 2048;
    static final long TARGET_BYTES = 1024 * 1024;

    private EventBlock() {}

    static long estimate(LogEvent e) {
        return 160L + 2L * (e.world().length() + e.session().length() + e.dimension().length()
                + e.playerId().length() + e.playerName().length() + e.action().length()
                + e.object().length() + e.detail().length() + e.cause().length());
    }

    static byte[] encode(List<LogEvent> events) {
        Deflater compressor = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, compressor))) {
                out.writeByte(1); // Block codec version, independent of the database schema.
                unsigned(out, events.size());
                Map<String, Integer> strings = new HashMap<>();
                long time = 0, tick = 0;
                int x = 0, y = 0, z = 0;
                for (LogEvent e : events) {
                    signed(out, e.time() - time); time = e.time();
                    signed(out, e.tick() - tick); tick = e.tick();
                    string(out, strings, e.world()); string(out, strings, e.session()); string(out, strings, e.dimension());
                    signed(out, (long)e.x() - x); x = e.x();
                    signed(out, (long)e.y() - y); y = e.y();
                    signed(out, (long)e.z() - z); z = e.z();
                    string(out, strings, e.playerId()); string(out, strings, e.playerName());
                    string(out, strings, e.action()); string(out, strings, e.object());
                    signed(out, e.count()); string(out, strings, e.detail()); string(out, strings, e.cause());
                    unsigned(out, e.id() - e.previousPosition());
                    unsigned(out, e.id() - e.previousPlayer());
                }
            }
            return bytes.toByteArray();
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
        finally { compressor.end(); }
    }

    static List<LogEvent> decode(long firstId, byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
            if (in.readUnsignedByte() != 1) throw new IOException("Unsupported event block codec");
            int count = Math.toIntExact(unsigned(in));
            if (count < 1 || count > MAX_EVENTS) throw new IOException("Invalid event block length");
            List<LogEvent> result = new ArrayList<>(count);
            List<String> strings = new ArrayList<>();
            long time = 0, tick = 0;
            int x = 0, y = 0, z = 0;
            for (int n = 0; n < count; n++) {
                long id = firstId + n;
                time += signed(in); tick += signed(in);
                String world = string(in, strings), session = string(in, strings), dimension = string(in, strings);
                x = Math.toIntExact(x + signed(in)); y = Math.toIntExact(y + signed(in)); z = Math.toIntExact(z + signed(in));
                result.add(new LogEvent(id, time, tick, world, session, dimension, x, y, z,
                        string(in, strings), string(in, strings), string(in, strings), string(in, strings),
                        Math.toIntExact(signed(in)), string(in, strings), string(in, strings),
                        id - unsigned(in), id - unsigned(in)));
            }
            if (in.read() != -1) throw new IOException("Trailing data in event block");
            return result;
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    private static void string(DataOutputStream out, Map<String, Integer> strings, String value) throws IOException {
        Integer reference = strings.get(value);
        if (reference != null) { unsigned(out, reference + 1); return; }
        unsigned(out, 0);
        strings.put(value, strings.size());
        // Preserve arbitrary/uppercase strings exactly; pack only canonical UUIDs.
        UUID uuid = null;
        if (value.length() == 36) {
            try { UUID parsed = UUID.fromString(value); if (parsed.toString().equals(value)) uuid = parsed; }
            catch (IllegalArgumentException ignored) {}
        }
        if (uuid != null) {
            unsigned(out, 0); out.writeLong(uuid.getMostSignificantBits()); out.writeLong(uuid.getLeastSignificantBits());
        } else {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            unsigned(out, (long)utf8.length + 1); out.write(utf8);
        }
    }

    private static String string(DataInputStream in, List<String> strings) throws IOException {
        int reference = Math.toIntExact(unsigned(in));
        if (reference != 0) {
            if (reference < 1 || reference > strings.size()) throw new IOException("Invalid block dictionary reference");
            return strings.get(reference - 1);
        }
        int length = Math.toIntExact(unsigned(in));
        String value;
        if (length == 0) value = new UUID(in.readLong(), in.readLong()).toString();
        else {
            if (length < 0) throw new IOException("Invalid string length");
            byte[] utf8 = in.readNBytes(length - 1);
            if (utf8.length != length - 1) throw new EOFException("Truncated block string");
            value = new String(utf8, StandardCharsets.UTF_8);
        }
        strings.add(value);
        return value;
    }

    static void signed(DataOutputStream out, long n) throws IOException { unsigned(out, (n << 1) ^ (n >> 63)); }
    static long signed(DataInputStream in) throws IOException { long n = unsigned(in); return (n >>> 1) ^ -(n & 1); }
    static void unsigned(DataOutputStream out, long n) throws IOException {
        while ((n & ~0x7fL) != 0) { out.writeByte((int)(n & 0x7f) | 0x80); n >>>= 7; }
        out.writeByte((int)n);
    }
    static long unsigned(DataInputStream in) throws IOException {
        long n = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.readUnsignedByte();
            if (shift == 63 && (b & 0xfe) != 0) throw new IOException("Invalid varint");
            n |= (long)(b & 127) << shift;
            if ((b & 128) == 0) return n;
        }
        throw new IOException("Invalid varint");
    }
}
