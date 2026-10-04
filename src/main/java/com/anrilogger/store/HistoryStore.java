package com.anrilogger.store;

import org.h2.mvstore.*;
import org.h2.mvstore.type.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Single owner thread; blocks, indexes and metadata commit atomically in one MVStore. */
public final class HistoryStore implements AutoCloseable {
    private final MVStore store;
    private final MVMap<String, String> metadata;
    private final MVMap<Long, byte[]> blocks, legacyEvents;
    private final MVMap<Integer, String> legacyDictionary;
    private final MVMap<String, Long> legacyPositions, legacyPlayers, players;
    private final MVMap<String, Integer> keyStrings;
    private final MVMap<PositionKey, Long> positions;
    private final long legacyLastId;
    private final List<LogEvent> tail = new ArrayList<>();
    private final LinkedHashMap<Long, List<LogEvent>> cache = new LinkedHashMap<>(8, 0.75f, true);
    private long nextId, tailBytes, cacheBytes, pendingBytes;
    private int pendingEvents;
    private boolean tailDirty;
    private long lastMaintenance = System.nanoTime();

    public HistoryStore(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        MVStore.Builder builder = new MVStore.Builder().fileName(file.toString()).compressHigh()
                // H2 2.3.232 autoCommitDisabled() disables only the timer, not memory-triggered
                // commits. An implicit commit between index and tail updates would break atomicity.
                .autoCommitDisabled().autoCommitBufferSize(0).cacheSize(32);
        store = builder.open();
        try {
            metadata = store.openMap("meta");
            String schema = metadata.get("schema");
            if (schema != null && !schema.equals("1") && !schema.equals("2"))
                throw new IOException("Unsupported history schema: " + schema);
            if (schema == null && !store.getMapNames().equals(Set.of("meta")))
                throw new IOException("Unrecognized database without a history schema");
            legacyEvents = store.hasMap("events") ? store.openMap("events", bytesMap()) : null;
            legacyLastId = legacyEvents == null || legacyEvents.isEmpty() ? 0 : legacyEvents.lastKey();
            legacyDictionary = store.hasMap("dictionary") ? store.openMap("dictionary") : null;
            legacyPositions = store.hasMap("positions") ? store.openMap("positions") : null;
            legacyPlayers = store.hasMap("players") ? store.openMap("players") : null;
            // Open the old reverse dictionary too, so MVStore can reclaim all old map pages.
            if (store.hasMap("strings")) store.openMap("strings");
            blocks = store.openMap("blocks", bytesMap());
            positions = store.openMap("positionHeads",
                    new MVMap.Builder<PositionKey, Long>().keyType(PositionKey.TYPE).valueType(LongDataType.INSTANCE));
            players = store.openMap("playerHeads",
                    new MVMap.Builder<String, Long>().keyType(StringDataType.INSTANCE).valueType(LongDataType.INSTANCE));
            keyStrings = store.openMap("keyStrings");
            long lastId = legacyLastId;
            if (!blocks.isEmpty()) {
                tail.addAll(EventBlock.decode(blocks.lastKey(), blocks.get(blocks.lastKey())));
                tailBytes = tail.stream().mapToLong(EventBlock::estimate).sum();
                lastId = tail.getLast().id();
            }
            nextId = Math.addExact(lastId, 1);
            metadata.put("schema", "2");
        } catch (Throwable e) { store.closeImmediately(); throw e; }
    }

    private static MVMap.Builder<Long, byte[]> bytesMap() {
        return new MVMap.Builder<Long, byte[]>().keyType(LongDataType.INSTANCE).valueType(ByteArrayDataType.INSTANCE);
    }

    public String worldId(String absoluteSavePath) {
        String key = "world:" + absoluteSavePath;
        String id = metadata.get(key);
        if (id == null) { id = UUID.randomUUID().toString(); metadata.put(key, id); }
        return id;
    }
    public void session(String id, String description) { metadata.put("session:" + id, description); }
    public Map<String, String> sessions() {
        Map<String, String> result = new TreeMap<>();
        metadata.forEach((k, v) -> { if (k.startsWith("session:")) result.put(k.substring(8), v); });
        return result;
    }

    public long append(LogEvent raw) {
        long followingId = Math.addExact(nextId, 1);
        long estimate = EventBlock.estimate(raw);
        if (!tail.isEmpty() && (tail.size() >= EventBlock.MAX_EVENTS || tailBytes + estimate > EventBlock.TARGET_BYTES)) {
            saveTail(); tail.clear(); tailBytes = 0;
        }
        PositionKey key = positionKey(raw.world(), raw.dimension(), raw.x(), raw.y(), raw.z(), true);
        LogEvent event = raw.withStorage(nextId, positionHead(key, raw.positionKey()), playerHead(raw.playerKey()));
        tail.add(event); tailBytes += estimate; tailDirty = true;
        positions.put(key, nextId); players.put(event.playerKey(), nextId);
        pendingBytes += estimate; pendingEvents++;
        nextId = followingId;
        return event.id();
    }

    /** Limits transaction memory without fsync on every 512 queue tasks. */
    boolean shouldFlush() { return pendingEvents >= 16384 || pendingBytes >= 16L * 1024 * 1024; }

    private void saveTail() {
        if (tailDirty) { blocks.put(tail.getFirst().id(), EventBlock.encode(tail)); tailDirty = false; }
    }

    public long size() { return nextId - 1; }
    public long lastId() { return nextId - 1; }

    public LogEvent get(long id) {
        if (id < 1 || id >= nextId) return null;
        if (id <= legacyLastId) return decodeLegacy(id, legacyEvents.get(id));
        if (!tail.isEmpty() && id >= tail.getFirst().id()) return tail.get(Math.toIntExact(id - tail.getFirst().id()));
        Long first = blocks.floorKey(id);
        if (first == null) throw new IllegalStateException("Missing event block for " + id);
        List<LogEvent> decoded = cache.get(first);
        if (decoded == null) {
            decoded = EventBlock.decode(first, blocks.get(first));
            long bytes = decoded.stream().mapToLong(EventBlock::estimate).sum();
            while (!cache.isEmpty() && (cache.size() >= 8 || cacheBytes + bytes > 8L * 1024 * 1024)) {
                List<LogEvent> removed = cache.pollFirstEntry().getValue();
                cacheBytes -= removed.stream().mapToLong(EventBlock::estimate).sum();
            }
            if (bytes <= 8L * 1024 * 1024) { cache.put(first, decoded); cacheBytes += bytes; }
        }
        int offset = Math.toIntExact(id - first);
        if (offset >= decoded.size()) throw new IllegalStateException("Missing event " + id);
        return decoded.get(offset);
    }

    public void flush() {
        saveTail(); store.commit(); store.sync(); pendingEvents = 0; pendingBytes = 0;
        // autoCommitDisabled also disables MVStore's background housekeeping. Keep its default
        // retention window; reclaim fragmented old chunks incrementally on the owner thread.
        if (System.nanoTime() - lastMaintenance >= 60_000_000_000L) {
            store.compact(90, 4 * 1024 * 1024);
            store.commit(); store.sync(); lastMaintenance = System.nanoTime();
        }
    }

    public record Page(List<LogEvent> events, long nextCursor, boolean limited, int scanned) {}
    public record PositionPage(Page page, List<Long> nextCursors) {}
    /** Merge exact position indexes in event order, retaining a cursor for each physical container. */
    public PositionPage searchPositions(List<Query> queries, long snapshot, List<Long> cursors, int size, int scanLimit) {
        if (queries.isEmpty() || queries.size() != cursors.size()) throw new IllegalArgumentException("Position cursor count mismatch");
        if (queries.size() == 1) {
            Page page = search(queries.getFirst(), snapshot, cursors.getFirst(), size, scanLimit);
            return new PositionPage(page, List.of(page.nextCursor()));
        }
        long[] heads = new long[queries.size()];
        for (int i = 0; i < heads.length; i++) {
            Query q = queries.get(i);
            if (q.range() != 0 || q.dimension().equals("@all")) throw new IllegalArgumentException("Expected exact positions");
            heads[i] = cursors.get(i) == Long.MAX_VALUE
                    ? positionHead(positionKey(q.world(), q.dimension(), q.x(), q.y(), q.z(), false), q.positionKey())
                    : cursors.get(i);
        }
        List<LogEvent> found = new ArrayList<>(); int scanned = 0;
        long deadline = System.nanoTime() + 1_500_000_000L;
        while (scanned < scanLimit && System.nanoTime() < deadline && found.size() < size) {
            int newest = 0;
            for (int i = 1; i < heads.length; i++) if (heads[i] > heads[newest]) newest = i;
            long id = heads[newest];
            if (id <= 0) break;
            LogEvent event = get(id);
            if (event == null) throw new IllegalStateException("Missing event " + id);
            scanned++;
            // Advance equal heads together so repeated positions cannot duplicate an event.
            for (int i = 0; i < heads.length; i++) if (heads[i] == id) heads[i] = event.previousPosition();
            if (id <= snapshot && queries.get(newest).matches(event)) found.add(event);
        }
        List<Long> next = Arrays.stream(heads).boxed().toList();
        long nextId = next.stream().mapToLong(Long::longValue).max().orElse(0);
        return new PositionPage(new Page(List.copyOf(found), nextId, nextId > 0 && found.size() < size, scanned), next);
    }
    public Page search(Query q, long snapshot, long cursor, int size, int scanLimit) {
        boolean byPosition = q.range() == 0 && !q.dimension().equals("@all");
        boolean byPlayer = !byPosition && q.source().matches("[0-9a-fA-F-]{36}");
        long id = byPosition ? positionHead(positionKey(q.world(), q.dimension(), q.x(), q.y(), q.z(), false), q.positionKey())
                : byPlayer ? playerHead(q.world() + "|" + q.source().toLowerCase(Locale.ROOT))
                : Math.min(lastId(), Math.min(snapshot, cursor));
        if ((byPosition || byPlayer) && cursor != Long.MAX_VALUE) id = cursor;
        List<LogEvent> found = new ArrayList<>(); int scanned = 0;
        long deadline = System.nanoTime() + 1_500_000_000L;
        while (id > 0 && scanned < scanLimit && System.nanoTime() < deadline) {
            LogEvent e = get(id);
            if (e == null) throw new IllegalStateException("Missing event " + id);
            scanned++;
            long previous = byPosition ? e.previousPosition() : byPlayer ? e.previousPlayer() : id - 1;
            if (id <= snapshot && id <= cursor && q.matches(e)) found.add(e);
            id = previous;
            if (found.size() == size) break;
        }
        return new Page(List.copyOf(found), Math.max(0, id), id > 0 && found.size() < size, scanned);
    }

    private PositionKey positionKey(String world, String dimension, int x, int y, int z, boolean create) {
        Integer w = keyString(world, create), d = keyString(dimension, create);
        return w == null || d == null ? null : new PositionKey(w, d, x, y, z);
    }
    private Integer keyString(String value, boolean create) {
        Integer id = keyStrings.get(value);
        if (id == null && create) { id = Math.addExact(keyStrings.size(), 1); keyStrings.put(value, id); }
        return id;
    }
    private long positionHead(PositionKey key, String legacyKey) {
        Long id = key == null ? null : positions.get(key);
        return id != null ? id : legacyPositions == null ? 0 : legacyPositions.getOrDefault(legacyKey, 0L);
    }
    private long playerHead(String key) {
        Long id = players.get(key);
        return id != null ? id : legacyPlayers == null ? 0 : legacyPlayers.getOrDefault(key, 0L);
    }

    private String legacyString(DataInputStream in) throws IOException {
        String value = legacyDictionary.get(Math.toIntExact(EventBlock.unsigned(in)));
        if (value == null) throw new IOException("Missing legacy dictionary entry");
        return value;
    }
    private LogEvent decodeLegacy(long id, byte[] data) {
        if (data == null) throw new IllegalStateException("Missing legacy event " + id);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return new LogEvent(id, EventBlock.unsigned(in), EventBlock.signed(in), legacyString(in), legacyString(in), legacyString(in),
                    (int)EventBlock.signed(in), (int)EventBlock.signed(in), (int)EventBlock.signed(in), legacyString(in), legacyString(in),
                    legacyString(in), legacyString(in), Math.toIntExact(EventBlock.unsigned(in)), legacyString(in), legacyString(in),
                    EventBlock.unsigned(in), EventBlock.unsigned(in));
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    @Override public void close() {
        flush(); store.close(1000);
    }
}
