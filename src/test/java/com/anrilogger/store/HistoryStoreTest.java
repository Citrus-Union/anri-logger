package com.anrilogger.store;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class HistoryStoreTest {
    @TempDir Path dir;
    static LogEvent event(int i,String session,long tick) {
        return new LogEvent(0,1_800_000_000_000L+i,tick,"world",session,"minecraft:overworld",-123,64,-456,
                "12345678-1234-1234-1234-123456789012","玩家Alice","item-insert","minecraft:diamond",i%64+1,
                "{id:\"minecraft:diamond\",components:{\"minecraft:custom_name\":\"珍贵物品\"}}","cause",0,0);
    }
    static Query all() { return new Query("world","minecraft:overworld",0,0,0,-1,"","","","","",0,Long.MAX_VALUE); }
    @Test void singleFileWhileOpenAndAfterClose() throws Exception {
        try(HistoryStore store=new HistoryStore(dir.resolve("history.db"))) {
            for(int i=0;i<1200;i++)store.append(event(i,"session",i)); store.flush();
            try(var files=Files.list(dir)) { assertEquals(List.of("history.db"),files.map(p->p.getFileName().toString()).toList()); }
        }
        try(var files=Files.list(dir)) { assertEquals(1,files.count()); }
    }
    @Test void querySeesWritesWithoutForcingDiskFlush() throws Exception {
        Path file=dir.resolve("history.db"); HistoryStore store=new HistoryStore(file); store.flush();
        long initialSize=Files.size(file);
        try(HistoryWriter writer=new HistoryWriter(store,512,60000)) {
            writer.append(event(1,"s",1));
            assertEquals(1L,writer.query(HistoryStore::size).get(10,TimeUnit.SECONDS));
            assertEquals(initialSize,Files.size(file),"A read must not force a commit to disk");
        }
        try(HistoryStore reopened=new HistoryStore(file)) { assertEquals(1,reopened.size()); }
    }
    @Test void worldRollbackAndRestartDoNotOverwriteHistory() throws Exception {
        Path file=dir.resolve("history.db"); String world;
        try(HistoryStore store=new HistoryStore(file)) {
            world=store.worldId("/server/world"); store.append(event(1,"old",9000)); store.flush();
        }
        try(HistoryStore store=new HistoryStore(file)) {
            assertEquals(world,store.worldId("/server/world"));
            assertNotEquals(world,store.worldId("/server/other-world"));
            assertEquals(2,store.append(event(0,"new",20)));
            var events=store.search(all(),store.lastId(),Long.MAX_VALUE,10,100).events();
            assertEquals(List.of("new","old"),events.stream().map(LogEvent::session).toList());
            assertEquals(List.of(20L,9000L),events.stream().map(LogEvent::tick).toList());
            assertEquals(-123,events.getFirst().x()); assertTrue(events.getFirst().detail().contains("珍贵物品"));
        }
    }
    @Test void snapshotPaginationIgnoresNewAppends() throws Exception {
        try(HistoryStore s=new HistoryStore(dir.resolve("history.db"))) {
            for(int i=0;i<50;i++)s.append(event(i,"s",i));
            var first=s.search(all(),50,Long.MAX_VALUE,10,100);
            s.append(event(51,"s",51));
            var second=s.search(all(),50,first.nextCursor(),10,100);
            assertEquals(50,first.events().getFirst().id()); assertEquals(40,second.events().getFirst().id());
            assertEquals(31,second.events().getLast().id());
        }
    }
    @Test void boundedPositionScanResumesWithoutLooping() throws Exception {
        try(HistoryStore s=new HistoryStore(dir.resolve("history.db"))) {
            for(int i=0;i<100;i++)s.append(event(i,"s",i));
            Query q=new Query("world","minecraft:overworld",-123,64,-456,0,"","block-break","","","",0,Long.MAX_VALUE);
            var first=s.search(q,100,Long.MAX_VALUE,10,10);
            var second=s.search(q,100,first.nextCursor(),10,10);
            assertTrue(first.limited()); assertEquals(90,first.nextCursor()); assertEquals(80,second.nextCursor());
        }
    }
    private static LogEvent atPosition(int i,int x,String action) {
        LogEvent e=event(i,"s",i);
        return new LogEvent(0,e.time(),e.tick(),e.world(),e.session(),e.dimension(),x,e.y(),e.z(),
                e.playerId(),e.playerName(),action,e.object(),e.count(),e.detail(),e.cause(),0,0);
    }
    private static List<Query> chestPositions(String action) {
        return List.of(-123,-122).stream().map(x->new Query("world","minecraft:overworld",x,64,-456,0,"",action,"","","",0,Long.MAX_VALUE)).toList();
    }
    @Test void pairedPositionsMergeIndexesAndKeepSnapshotAcrossPages() throws Exception {
        try(HistoryStore s=new HistoryStore(dir.resolve("history.db"))) {
            for(int i=0;i<8;i++)s.append(atPosition(i,-123+i%2,i%2==0?"item-insert":"item-remove"));
            for(int i=0;i<100;i++)s.append(atPosition(i,-121,"item-insert"));
            long snapshot=s.lastId();
            var first=s.searchPositions(chestPositions("container"),snapshot,List.of(Long.MAX_VALUE,Long.MAX_VALUE),3,10);
            assertEquals(List.of(8L,7L,6L),first.page().events().stream().map(LogEvent::id).toList());
            assertEquals(3,first.page().scanned(),"Only the two exact position indexes should be scanned");
            s.append(atPosition(110,-123,"item-insert")); s.append(atPosition(111,-122,"item-remove"));
            var second=s.searchPositions(chestPositions("container"),snapshot,first.nextCursors(),3,10);
            assertEquals(List.of(5L,4L,3L),second.page().events().stream().map(LogEvent::id).toList());
            var last=s.searchPositions(chestPositions("container"),snapshot,second.nextCursors(),3,10);
            assertEquals(List.of(2L,1L),last.page().events().stream().map(LogEvent::id).toList());
            assertEquals(List.of(0L,0L),last.nextCursors()); assertFalse(last.page().limited());
            var repeat=s.searchPositions(chestPositions("container"),snapshot,List.of(Long.MAX_VALUE,Long.MAX_VALUE),3,10);
            assertEquals(first.page().events(),repeat.page().events(),"New events must not enter the original snapshot");
        }
    }
    @Test void pairedPositionBudgetResumesWithoutRepeatingOrSkipping() throws Exception {
        try(HistoryStore s=new HistoryStore(dir.resolve("history.db"))) {
            for(int i=0;i<6;i++)s.append(atPosition(i,-123+i%2,i<2?"item-insert":"container-open"));
            var first=s.searchPositions(chestPositions("container"),6,List.of(Long.MAX_VALUE,Long.MAX_VALUE),10,2);
            assertTrue(first.page().limited()); assertTrue(first.page().events().isEmpty()); assertEquals(4,first.page().nextCursor());
            var second=s.searchPositions(chestPositions("container"),6,first.nextCursors(),10,2);
            assertTrue(second.page().limited()); assertTrue(second.page().events().isEmpty()); assertEquals(2,second.page().nextCursor());
            var last=s.searchPositions(chestPositions("container"),6,second.nextCursors(),10,2);
            assertEquals(List.of(2L,1L),last.page().events().stream().map(LogEvent::id).toList());
            assertEquals(0,last.page().nextCursor()); assertFalse(last.page().limited());
        }
    }
    @Test void writerDrainsAndReadSeesPendingEvents() throws Exception {
        Path file=dir.resolve("history.db");
        try(HistoryWriter writer=new HistoryWriter(new HistoryStore(file),512,500)) {
            for(int i=0;i<2500;i++)writer.append(event(i,"s",i));
            assertEquals(2500L,writer.query(HistoryStore::size).get(10,TimeUnit.SECONDS));
            writer.append(event(2500,"s",0));
        }
        try(HistoryStore s=new HistoryStore(file)) { assertEquals(2501,s.size()); }
    }
    @Test void compressionKeepsRepetitiveHistoryCompact() throws Exception {
        Path file=dir.resolve("history.db");
        try(HistoryStore s=new HistoryStore(file)) { for(int i=0;i<10000;i++)s.append(event(i,"s",i)); s.flush(); }
        long bytes=Files.size(file);
        System.out.println("10,000 repetitive events: "+bytes+" bytes ("+(bytes/10000.0)+" bytes/event including indexes)");
        assertTrue(bytes<1_000_000,"Compressed indexed database too large: "+bytes);
    }
    @Test void fileLockPreventsConcurrentWriters() throws Exception {
        try(HistoryStore s=new HistoryStore(dir.resolve("history.db"))) {
            assertThrows(RuntimeException.class,()->new HistoryStore(dir.resolve("history.db")));
        }
    }
    @Test void mixedEventStorageMeasurement() throws Exception {
        Path file=dir.resolve("history.db");
        try(HistoryStore s=new HistoryStore(file)) {
            for(int i=0;i<10000;i++)s.append(new LogEvent(0,1_800_000_000_000L+i*50L,i,"world","session","minecraft:overworld",
                i%200-100,64+(i%5),i/200-25,"12345678-1234-1234-1234-123456789012","Alice","block-break","minecraft:stone",1,
                "Block{minecraft:stone} -> Block{minecraft:air}",UUID.nameUUIDFromBytes(("cause-"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),0,0));
            s.flush();
        }
        long bytes=Files.size(file); System.out.println("10,000 distinct-position, distinct-cause events: "+bytes+" bytes ("+(bytes/10000.0)+" bytes/event including indexes)");
        assertTrue(bytes<3_000_000);
    }
    @Test void abruptExitKeepsLastDurableCommit() throws Exception {
        String cp=Path.of(HistoryStore.class.getProtectionDomain().getCodeSource().getLocation().toURI())+java.io.File.pathSeparator
                +Path.of(org.h2.mvstore.MVStore.class.getProtectionDomain().getCodeSource().getLocation().toURI())+java.io.File.pathSeparator
                +Path.of(CrashWriter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,CrashWriter.class.getName(),dir.resolve("history.db").toString()).inheritIO().start();
        assertTrue(child.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,child.exitValue());
        try(HistoryStore s=new HistoryStore(dir.resolve("history.db"))) {
            assertEquals(100,s.size()); assertEquals(101,s.append(event(1,"after-crash",0)));
        }
    }
}
