package com.anrilogger.store;

import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompactStorageTest {
    @TempDir Path dir;

    private static LogEvent varied(int i) {
        Random random = new Random(i);
        return new LogEvent(0, 1_800_000_000_000L + random.nextInt(10000), i % 377,
                "world" + i % 2, "session" + i % 3, "dimension" + i % 3,
                random.nextInt(), random.nextInt(), random.nextInt(), "player" + i % 12,
                "玩家😀" + i % 12, "block-change", "minecraft:stone", i % 64 + 1,
                i == 3000 ? "{中文组件😀}".repeat(20000) : "detail" + i % 11,
                i % 5 == 0 ? "12345678-ABCD-1234-ABCD-123456789012" : new UUID(random.nextLong(), random.nextLong()).toString(), 0, 0);
    }

    @Test void everyFieldSurvivesBlocksFrequentCommitsRestartAndRandomReads() throws Exception {
        Path path = dir.resolve("history.db");
        List<LogEvent> expected = new ArrayList<>();
        Map<String, Long> positions = new HashMap<>(), players = new HashMap<>();
        for (int restart = 0; restart < 3; restart++) {
            try (HistoryStore store = new HistoryStore(path)) {
                for (int i = restart * 2500; i < (restart + 1) * 2500; i++) {
                    LogEvent raw = varied(i);
                    LogEvent saved = raw.withStorage(i + 1, positions.getOrDefault(raw.positionKey(), 0L), players.getOrDefault(raw.playerKey(), 0L));
                    positions.put(raw.positionKey(), saved.id()); players.put(raw.playerKey(), saved.id());
                    expected.add(saved); assertEquals(saved.id(), store.append(raw));
                    if (i % 71 == 0) store.flush();
                }
            }
        }
        Collections.shuffle(expected, new Random(42));
        try (HistoryStore store = new HistoryStore(path)) {
            assertEquals(7500, store.size());
            for (LogEvent event : expected) assertEquals(event, store.get(event.id()));
            assertNull(store.get(0)); assertNull(store.get(7501));
        }
    }

    @Test void legacyUpgradeKeepsPositionAndPlayerChainsAcrossFormatBoundary() throws Exception {
        Path path = dir.resolve("history.db");
        String worldId;
        try (LegacyHistoryStore old = new LegacyHistoryStore(path)) {
            worldId = old.worldId("/world"); old.session("old", "old description");
            for (int i = 0; i < 120; i++) old.append(HistoryStoreTest.event(i, "old", i));
        }
        for (int restart = 0; restart < 2; restart++) {
            try (HistoryStore store = new HistoryStore(path)) {
                assertEquals(worldId, store.worldId("/world"));
                assertEquals("old description", store.sessions().get("old"));
                for (int i = 0; i < 40; i++) store.append(HistoryStoreTest.event(i, "new", i));
                Query at = new Query("world", "minecraft:overworld", -123, 64, -456, 0, "", "", "", "", "", 0, Long.MAX_VALUE);
                Query player = new Query("world", "@all", 0, 0, 0, -1,
                        HistoryStoreTest.event(0, "s", 0).playerId(), "", "", "", "", 0, Long.MAX_VALUE);
                for (Query query : List.of(at, player, HistoryStoreTest.all())) {
                    long cursor = Long.MAX_VALUE, expectedId = store.lastId();
                    do {
                        var page = store.search(query, store.lastId(), cursor, 17, 100);
                        for (LogEvent event : page.events()) assertEquals(expectedId--, event.id());
                        cursor = page.nextCursor();
                    } while (cursor != 0);
                    assertEquals(0, expectedId);
                }
            }
        }
        try (MVStore raw = new MVStore.Builder().fileName(path.toString()).readOnly().open()) {
            assertEquals(120, raw.openMap("events", new org.h2.mvstore.MVMap.Builder<Long, byte[]>()
                    .keyType(org.h2.mvstore.type.LongDataType.INSTANCE).valueType(org.h2.mvstore.type.ByteArrayDataType.INSTANCE)).size());
            assertTrue(raw.hasMap("blocks"));
        }
    }

    @Test void positionKeysAreExactForExtremeCoordinatesAndNeverMixWorlds() throws Exception {
        Path path = dir.resolve("history.db");
        try (HistoryStore store = new HistoryStore(path)) {
            for (int i = 0; i < 500; i++) store.append(varied(i));
            store.flush();
        }
        try (HistoryStore store = new HistoryStore(path)) {
            for (int i = 0; i < 500; i++) {
                LogEvent event = varied(i);
                Query query = new Query(event.world(), event.dimension(), event.x(), event.y(), event.z(), 0,
                        "", "", "", "", "", 0, Long.MAX_VALUE);
                assertEquals(List.of((long)i + 1), store.search(query, 500, Long.MAX_VALUE, 10, 100).events().stream().map(LogEvent::id).toList());
            }
            Query missing = new Query("missing", "dimension", Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0,
                    "", "", "", "", "", 0, Long.MAX_VALUE);
            assertTrue(store.search(missing, 500, Long.MAX_VALUE, 10, 100).events().isEmpty());
        }
    }

    @Test void localDictionariesDoNotGrowWithUniqueCausesOrDetails() throws Exception {
        Path path = dir.resolve("history.db");
        try (HistoryStore store = new HistoryStore(path)) {
            for (int i = 0; i < 10000; i++) store.append(varied(i));
        }
        try (MVStore raw = new MVStore.Builder().fileName(path.toString()).readOnly().open()) {
            assertFalse(raw.hasMap("strings")); assertFalse(raw.hasMap("dictionary"));
            assertEquals(5, raw.openMap("keyStrings").size()); // Only 2 worlds + 3 dimensions.
        }
    }

    @Test void memoryPressureCannotImplicitlyCommitPartialBlocksAndIndexes() throws Exception {
        Path path=dir.resolve("memory.db");
        try(HistoryStore store=new HistoryStore(path)) {
            store.flush(); long committedSize=Files.size(path);
            for(int i=0;i<200000;i++)store.append(loadEvent(i,true));
            assertEquals(committedSize,Files.size(path),"Only explicit atomic flush may commit event blocks and heads");
        }
        try(HistoryStore store=new HistoryStore(path)) { assertEquals(200000,store.size()); assertNotNull(store.get(199999)); }
    }

    @Test void crashWithSealedBlocksAndRewrittenTailPreservesAtomicIndexes() throws Exception {
        Path path=dir.resolve("crash.db");
        String cp=Path.of(HistoryStore.class.getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator
                +Path.of(MVStore.class.getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator
                +Path.of(CrashWriter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,
                CrashWriter.class.getName(),path.toString(),"10000","5000").inheritIO().start();
        assertTrue(child.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0,child.exitValue());
        try (HistoryStore store=new HistoryStore(path)) {
            assertEquals(10000,store.size());
            Query at=new Query("w","d",0,0,0,0,"","","","","",0,Long.MAX_VALUE);
            assertEquals(10000,store.search(at,10000,Long.MAX_VALUE,10,100).events().getFirst().id());
            assertEquals(10001,store.append(new LogEvent(0,3000,1,"w","s","d",0,0,0,"p","Alice","block-break","stone",1,"","c",0,0)));
            assertEquals(10000,store.get(10001).previousPosition()); assertEquals(10000,store.get(10001).previousPlayer());
        }
    }

    @Test void highRateCommitPolicyReducesFileGrowth() throws Exception {
        for(boolean unique:List.of(false,true)) {
            Path oldPath=dir.resolve("policy-old-"+unique+".db"), newPath=dir.resolve("policy-new-"+unique+".db");
            long oldOpen,newOpen;
            try(LegacyHistoryStore old=new LegacyHistoryStore(oldPath); HistoryStore compact=new HistoryStore(newPath)) {
                for(int i=0;i<100000;i++) {
                    LogEvent event=loadEvent(i,unique); old.append(event); compact.append(event);
                    if((i+1)%512==0)old.flush();
                    if(compact.shouldFlush())compact.flush();
                }
                old.flush(); compact.flush(); oldOpen=Files.size(oldPath); newOpen=Files.size(newPath);
            }
            System.out.printf("100,000 %s; high-rate commit policy; open old=%d new=%d; closed old=%d new=%d%n",
                    unique?"distinct positions/UUIDs":"redstone events",oldOpen,newOpen,Files.size(oldPath),Files.size(newPath));
            assertTrue(newOpen<oldOpen*0.4);
        }
    }

    @Test void compressionComparedToOriginalFormatUnderRepeatedCommits() throws Exception {
        for (boolean unique : List.of(false, true)) {
            Path oldPath = dir.resolve("old-" + unique + ".db"), newPath = dir.resolve("new-" + unique + ".db");
            long oldOpen, newOpen;
            try (LegacyHistoryStore old = new LegacyHistoryStore(oldPath); HistoryStore compact = new HistoryStore(newPath)) {
                for (int i = 0; i < 100000; i++) {
                    LogEvent event = loadEvent(i, unique);
                    old.append(event); compact.append(event);
                    if ((i + 1) % 512 == 0) { old.flush(); compact.flush(); }
                }
                old.flush(); compact.flush(); oldOpen = Files.size(oldPath); newOpen = Files.size(newPath);
            }
            long oldClosed = Files.size(oldPath), newClosed = Files.size(newPath);
            System.out.printf("100,000 %s; same 512-event commits; open old=%d new=%d; closed old=%d new=%d%n",
                    unique ? "distinct positions/UUIDs" : "redstone events", oldOpen, newOpen, oldClosed, newClosed);
            assertTrue(newOpen < oldOpen, "Same-commit comparison includes unreclaimed pages inside MVStore's retention window");
            assertTrue(newClosed < oldClosed * 0.65);
        }
    }

    static LogEvent loadEvent(int i, boolean unique) {
        Random random = new Random(i);
        return new LogEvent(0, 1_800_000_000_000L + (i / 1000) * 50L + i % 3, i / 1000,
                "b2191f64-f550-49ab-af80-123456789012", "fe2ec387-90da-4c5d-9969-123456789012", "minecraft:overworld",
                unique ? i % 1000 : i % 16, 64, unique ? i / 1000 : (i / 16) % 16,
                new UUID(0, i % 12).toString(), "Player" + i % 12, "block-change", "minecraft:redstone_wire", 1,
                "Block{minecraft:redstone_wire}[power=" + i % 16 + "] -> Block{minecraft:redstone_wire}[power=" + (i + 1) % 16 + "]",
                unique ? new UUID(random.nextLong(), random.nextLong()).toString() : new UUID(0, i % 12).toString(), 0, 0);
    }
}
