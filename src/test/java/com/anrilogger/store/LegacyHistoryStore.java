package com.anrilogger.store;

import org.h2.mvstore.*;
import org.h2.mvstore.type.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Single owner thread. All maps commit atomically in ONE MVStore file, without JDBC/WAL/temp files. */
final class LegacyHistoryStore implements AutoCloseable {
    private final MVStore store;
    private final MVMap<Long, byte[]> events;
    private final MVMap<String, Integer> strings;
    private final MVMap<Integer, String> dictionary;
    private final MVMap<String, Long> positions, players;
    private final MVMap<String, String> metadata;
    private long nextId;
    public LegacyHistoryStore(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        store = new MVStore.Builder().fileName(file.toString()).compressHigh().autoCommitDisabled().cacheSize(32).open();
        try {
            metadata = store.openMap("meta");
            String schema = metadata.get("schema");
            if (schema != null && !schema.equals("1")) throw new IOException("Unsupported history schema: " + schema);
            metadata.put("schema", "1");
            events = store.openMap("events", new MVMap.Builder<Long, byte[]>().keyType(LongDataType.INSTANCE).valueType(ByteArrayDataType.INSTANCE));
            strings = store.openMap("strings"); dictionary = store.openMap("dictionary");
            positions = store.openMap("positions"); players = store.openMap("players");
            nextId = events.isEmpty() ? 1 : Math.addExact(events.lastKey(), 1);
        } catch (Throwable e) { store.closeImmediately(); throw e; }
    }
    public String worldId(String absoluteSavePath) {
        String key = "world:" + absoluteSavePath;
        String id = metadata.get(key);
        if (id == null) { id = UUID.randomUUID().toString(); metadata.put(key, id); }
        return id;
    }
    public void session(String id, String description) { metadata.put("session:" + id, description); }
    public Map<String,String> sessions() {
        Map<String,String> result = new TreeMap<>();
        metadata.forEach((k,v) -> { if (k.startsWith("session:")) result.put(k.substring(8),v); });
        return result;
    }
    public long append(LogEvent raw) {
        LogEvent e = raw.withStorage(nextId++, positions.getOrDefault(raw.positionKey(), 0L), players.getOrDefault(raw.playerKey(), 0L));
        events.put(e.id(), encode(e)); positions.put(e.positionKey(), e.id()); players.put(e.playerKey(), e.id());
        return e.id();
    }
    public long size() { return events.sizeAsLong(); }
    public long lastId() { return nextId - 1; }
    public LogEvent get(long id) { byte[] bytes=events.get(id); return bytes==null?null:decode(id,bytes); }
    public void flush() { store.commit(); store.sync(); }
    public record Page(List<LogEvent> events, long nextCursor, boolean limited, int scanned) {}
    public Page search(Query q, long snapshot, long cursor, int size, int scanLimit) {
        boolean byPosition = q.range() == 0 && !q.dimension().equals("@all");
        boolean byPlayer = !byPosition && q.source().matches("[0-9a-fA-F-]{36}");
        Long id = byPosition ? positions.get(q.positionKey()) : byPlayer ? players.get(q.world()+"|"+q.source().toLowerCase(Locale.ROOT)) : events.floorKey(Math.min(snapshot, cursor));
        if ((byPosition || byPlayer) && cursor != Long.MAX_VALUE) id = cursor == 0 ? null : cursor;
        List<LogEvent> found = new ArrayList<>(); int scanned = 0;
        long deadline = System.nanoTime() + 1_500_000_000L;
        while (id != null && id > 0 && scanned < scanLimit && System.nanoTime() < deadline) {
            LogEvent e = decode(id, events.get(id)); scanned++;
            long previous = byPosition ? e.previousPosition() : byPlayer ? e.previousPlayer() : id - 1;
            if (id <= snapshot && id <= cursor && q.matches(e)) found.add(e);
            id = previous == 0 ? null : previous;
            if (found.size() == size) break;
        }
        return new Page(List.copyOf(found), id == null ? 0 : id, id != null && found.size() < size, scanned);
    }
    private int intern(String value) {
        Integer id = strings.get(value);
        if (id == null) { id = dictionary.size() + 1; strings.put(value,id); dictionary.put(id,value); }
        return id;
    }
    private void str(DataOutputStream out, String value) throws IOException { var(out, intern(value)); }
    private String str(DataInputStream in) throws IOException {
        String value = dictionary.get(Math.toIntExact(var(in)));
        if (value == null) throw new IOException("Missing dictionary entry"); return value;
    }
    private byte[] encode(LogEvent e) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(96); DataOutputStream out = new DataOutputStream(bytes);
            var(out,e.time()); signed(out,e.tick());
            str(out,e.world()); str(out,e.session()); str(out,e.dimension());
            signed(out,e.x()); signed(out,e.y()); signed(out,e.z());
            str(out,e.playerId()); str(out,e.playerName()); str(out,e.action()); str(out,e.object());
            var(out,e.count()); str(out,e.detail()); str(out,e.cause());
            var(out,e.previousPosition()); var(out,e.previousPlayer());
            return bytes.toByteArray();
        } catch(IOException ex) { throw new UncheckedIOException(ex); }
    }
    private LogEvent decode(long id, byte[] data) {
        if(data == null) throw new IllegalStateException("Missing event " + id);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            return new LogEvent(id,var(in),signed(in),str(in),str(in),str(in),(int)signed(in),(int)signed(in),(int)signed(in),
                    str(in),str(in),str(in),str(in),Math.toIntExact(var(in)),str(in),str(in),var(in),var(in));
        } catch(IOException ex) { throw new UncheckedIOException(ex); }
    }
    private static void signed(DataOutputStream o,long n) throws IOException { var(o,(n<<1)^(n>>63)); }
    private static long signed(DataInputStream i) throws IOException { long n=var(i); return (n>>>1)^-(n&1); }
    private static void var(DataOutputStream out,long n) throws IOException {
        while((n & ~0x7fL)!=0) { out.writeByte((int)(n & 0x7f)|0x80); n >>>=7; } out.writeByte((int)n);
    }
    private static long var(DataInputStream in) throws IOException {
        long n=0; for(int shift=0;shift<64;shift+=7) { int b=in.readUnsignedByte(); n|=(long)(b&127)<<shift; if((b&128)==0)return n; }
        throw new IOException("Invalid varint");
    }
    @Override public void close() { flush(); store.close(); }
}
