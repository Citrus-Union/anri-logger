package com.anrilogger.command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.anrilogger.*;
import com.anrilogger.compat.CarpetTisCompat;
import com.anrilogger.store.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.*;
import static net.minecraft.commands.Commands.*;

public final class AnriLoggerCommands {
    private static final Set<UUID> INSPECT=new HashSet<>();
    private static final Map<String,Search> SEARCHES=new HashMap<>();
    private static final Map<String,Long> LAST=new HashMap<>();
    private static final Map<String,Object> PENDING=new HashMap<>();
    private static final Map<String,Object> INSPECT_SESSIONS=new HashMap<>();
    private static final class Search {
        final Query query; long snapshot; int page;
        final List<Query> positions;
        final String pairedLabel;
        final List<List<Long>> cursors=new ArrayList<>();
        Search(Query query) { this(query,new Inspection(List.of(query),"")); }
        Search(Query query,Inspection inspection) {
            List<Query> positions=inspection.positions();
            this.query=query; this.positions=List.copyOf(positions);
            this.pairedLabel=inspection.pairedLabel();
            cursors.add(Collections.nCopies(positions.size(),Long.MAX_VALUE));
        }
    }
    public static boolean allowed(CommandSourceStack source) {
        if(AnriLogger.config.permissionMode==AnriLoggerConfig.PermissionMode.EVERYONE)return true;
        if(source.getEntity() instanceof ServerPlayer player)
            return source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
        return source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
    }
    private static String key(CommandSourceStack s) { return s.getEntity() instanceof ServerPlayer p?p.getUUID().toString():"console:"+s.getTextName(); }
    public static void disconnect(UUID id) { INSPECT.remove(id); SEARCHES.remove(id.toString()); LAST.remove(id.toString()); PENDING.remove(id.toString()); INSPECT_SESSIONS.remove(id.toString()); }
    public static void clear() { INSPECT.clear(); SEARCHES.clear(); LAST.clear(); PENDING.clear(); INSPECT_SESSIONS.clear(); }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root("al"));
        if(AnriLogger.config.enableLgAlias) {
            if(dispatcher.getRoot().getChild("lg")==null)dispatcher.register(root("lg"));
            else AnriLogger.LOGGER.warn("/lg already exists; alias not registered");
        }
    }
    private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return literal(name).requires(AnriLoggerCommands::allowed).executes(c -> help(c.getSource()))
            .then(literal("help").executes(c -> help(c.getSource())))
            .then(literal("inspect").executes(c -> toggle(c.getSource(),null))
                .then(literal("on").executes(c -> toggle(c.getSource(),true)))
                .then(literal("off").executes(c -> toggle(c.getSource(),false)))
                .then(argument("pos",BlockPosArgument.blockPos()).executes(c -> inspect(c.getSource(),BlockPosArgument.getBlockPos(c,"pos")))))
            .then(literal("i").executes(c -> toggle(c.getSource(),null)))
            .then(literal("container").executes(c -> container(c.getSource()))
                .then(argument("pos",BlockPosArgument.blockPos()).executes(c -> inspect(c.getSource(),BlockPosArgument.getBlockPos(c,"pos"),true))))
            .then(literal("search").executes(c -> search(c.getSource(),""))
                .then(argument("filters",StringArgumentType.greedyString()).executes(c -> search(c.getSource(),StringArgumentType.getString(c,"filters")))))
            .then(literal("s").then(argument("filters",StringArgumentType.greedyString()).executes(c -> search(c.getSource(),StringArgumentType.getString(c,"filters")))))
            .then(literal("page").executes(c -> page(c.getSource(),"next"))
                .then(argument("page",StringArgumentType.word()).suggests((c,b) -> SharedSuggestionProvider.suggest(List.of("next","prev"),b))
                    .executes(c -> page(c.getSource(),StringArgumentType.getString(c,"page")))))
            .then(literal("status").executes(c -> status(c.getSource())))
            .then(literal("sessions").executes(c -> sessions(c.getSource())))
            .then(literal("show").then(argument("id",LongArgumentType.longArg(1)).executes(c -> show(c.getSource(),LongArgumentType.getLong(c,"id")))));
    }
    private static void say(CommandSourceStack s,String text) { s.sendSuccess(() -> LogDisplay.prefix().append(LogDisplay.text(text,net.minecraft.ChatFormatting.GRAY)),false); }
    private static int help(CommandSourceStack s) {
        LogDisplay.help().forEach(line -> s.sendSuccess(() -> line,false)); return 1;
    }
    private static int toggle(CommandSourceStack source,Boolean on) {
        if(!(source.getEntity() instanceof ServerPlayer player)) { source.sendFailure(Component.literal("此命令需要玩家；控制台可用 /al inspect x y z")); return 0; }
        boolean enable=on==null?!INSPECT.contains(player.getUUID()):on;
        if(enable)INSPECT.add(player.getUUID());else INSPECT.remove(player.getUUID());
        source.sendSuccess(() -> LogDisplay.prefix().append(LogDisplay.text(enable?"检查模式已开启":"检查模式已关闭",enable?net.minecraft.ChatFormatting.GREEN:net.minecraft.ChatFormatting.GOLD))
            .append(LogDisplay.text(enable?" · 左/右键点击查历史，连续点击无需冷却。":"",net.minecraft.ChatFormatting.GRAY)),false); return 1;
    }
    public static boolean inspectClick(ServerPlayer player,BlockPos pos) {
        if(!INSPECT.contains(player.getUUID()))return false;
        CommandSourceStack source=player.createCommandSourceStack();
        if(!allowed(source)) { INSPECT.remove(player.getUUID()); return false; }
        inspect(source,pos); return true;
    }
    private static int inspect(CommandSourceStack s,BlockPos p) {
        return inspect(s,p,false);
    }
    private static int container(CommandSourceStack s) {
        if(s.getEntity() instanceof ServerPlayer player && player.pick(6,0,false) instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK)return inspect(s,hit.getBlockPos(),true);
        s.sendFailure(Component.literal("请对准 6 格内的容器，或使用 /al container <x y z>。")); return 0;
    }
    private static int inspect(CommandSourceStack s,BlockPos p,boolean containersOnly) {
        if(AnriLogger.writer==null) { s.sendFailure(Component.literal("记录器尚未就绪")); return 0; }
        Query q=new Query(AnriLogger.worldId,s.getLevel().dimension().identifier().toString(),p.getX(),p.getY(),p.getZ(),0,"",containersOnly?"container":"","","","",0,Long.MAX_VALUE);
        // Every inspection gets its own result. Never supersede a previous click or share search cooldown.
        String key=key(s); Object session=INSPECT_SESSIONS.computeIfAbsent(key,ignored -> new Object());
        Search search=new Search(q,inspectionPositions(s,p,q));
        AnriLogger.writer.query(store -> {
            long snapshot=store.lastId();
            var result=store.searchPositions(search.positions,snapshot,search.cursors.getFirst(),AnriLogger.config.pageSize,AnriLogger.config.maxQueryScan);
            return new Result(snapshot,result.page(),result.nextCursors());
        }).whenComplete((result,error) -> s.getServer().execute(() -> {
            if(INSPECT_SESSIONS.get(key)!=session || !allowed(s))return;
            if(error!=null) { failed(s,error); return; }
            SEARCHES.put(key,search); displayPage(s,search,0,result);
        }));
        return 1;
    }
    private record Inspection(List<Query> positions,String pairedLabel) {}
    private static Inspection inspectionPositions(CommandSourceStack source,BlockPos pos,Query query) {
        Inspection single=new Inspection(List.of(query),"");
        var level=source.getLevel();
        if(!level.hasChunkAt(pos))return single;
        var state=level.getBlockState(pos);
        BlockPos other; String label;
        if(state.getBlock() instanceof BarrelBlock) {
            other=CarpetTisCompat.otherBarrel(level,pos,state); label="大木桶";
            if(other==null)return single;
        } else {
            if(!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)return single;
            other=pos.relative(ChestBlock.getConnectedDirection(state)); label="双箱";
            if(!level.hasChunkAt(other))return single;
            var partner=level.getBlockState(other);
            if(partner.getBlock()!=state.getBlock()
                    || partner.getValue(ChestBlock.TYPE)==ChestType.SINGLE
                    || partner.getValue(ChestBlock.FACING)!=state.getValue(ChestBlock.FACING)
                    || !other.relative(ChestBlock.getConnectedDirection(partner)).equals(pos))return single;
        }
        Query second=new Query(query.world(),query.dimension(),other.getX(),other.getY(),other.getZ(),0,
                query.source(),query.action(),query.object(),query.session(),query.cause(),query.after(),query.before());
        return new Inspection(List.of(query,second),label);
    }
    private static int search(CommandSourceStack s,String text) {
        try {
            BlockPos pos=BlockPos.containing(s.getPosition());
            return start(s,Query.parse(text,AnriLogger.worldId,s.getLevel().dimension().identifier().toString(),pos.getX(),pos.getY(),pos.getZ(),System.currentTimeMillis()));
        } catch(IllegalArgumentException|ArithmeticException ex) { s.sendFailure(Component.literal("查询参数错误："+ex.getMessage())); return 0; }
    }
    private static boolean acquire(CommandSourceStack s) {
        String key=key(s); long now=System.currentTimeMillis();
        if(AnriLogger.writer==null) { s.sendFailure(Component.literal("记录器尚未就绪")); return false; }
        if(PENDING.containsKey(key) || now-LAST.getOrDefault(key,0L)<AnriLogger.config.queryCooldownMillis) { say(s,"查询处理中或过于频繁，请稍候。"); return false; }
        LAST.put(key,now); PENDING.put(key,new Object()); return true;
    }
    private static int start(CommandSourceStack s,Query q) {
        if(!acquire(s))return 0;
        Search search=new Search(q); SEARCHES.put(key(s),search); fetch(s,search,0); return 1;
    }
    private static int page(CommandSourceStack s,String value) {
        Search search=SEARCHES.get(key(s));
        if(search==null) { say(s,"请先执行查询。"); return 0; }
        int target;
        try { target=value.equals("next")?search.page+1:value.equals("prev")?search.page-1:Integer.parseInt(value)-1; }
        catch(NumberFormatException ex) { say(s,"页码应为正整数、next 或 prev。"); return 0; }
        if(target<0 || target>=search.cursors.size() || search.cursors.get(target).stream().allMatch(id->id==0)) { say(s,"没有该页。请按 next 顺序加载后续页。"); return 0; }
        if(!acquire(s))return 0; fetch(s,search,target); return 1;
    }
    private record Result(long snapshot,HistoryStore.Page page,List<Long> cursors) {}
    private static void fetch(CommandSourceStack s,Search search,int page) {
        String key=key(s); List<Long> cursor=search.cursors.get(page);
        Object request=PENDING.get(key);
        AnriLogger.writer.query(store -> {
            long snapshot=search.snapshot==0?store.lastId():search.snapshot;
            var result=store.searchPositions(search.positions,snapshot,cursor,AnriLogger.config.pageSize,AnriLogger.config.maxQueryScan);
            return new Result(snapshot,result.page(),result.nextCursors());
        }).whenComplete((result,error) -> s.getServer().execute(() -> {
            if(!PENDING.remove(key,request))return;
            if(SEARCHES.get(key)!=search || !allowed(s))return;
            if(error!=null) { failed(s,error); return; }
            displayPage(s,search,page,result);
        }));
    }
    private static void displayPage(CommandSourceStack s,Search search,int page,Result result) {
        search.snapshot=result.snapshot(); search.page=page;
        if(search.cursors.size()==page+1 && page<999)search.cursors.add(result.cursors());
        s.sendSuccess(() -> {
            var header=LogDisplay.header(search.query,page,result.page().events().size());
            if(search.positions.size()>1)header.append(LogDisplay.text(" · 包含"+search.pairedLabel+"两侧",net.minecraft.ChatFormatting.GRAY));
            return header;
        },false);
        result.page().events().forEach(e -> print(s,e));
        if(result.page().events().isEmpty() && !result.page().limited())say(s,"此条件下暂无记录。");
        if(result.page().limited())say(s,"本次扫描达到预算，点击下一页继续；尚未扫描完毕。");
        s.sendSuccess(() -> LogDisplay.navigation(page,result.page().nextCursor()!=0 && page<999),false);
        if(search.query.range()==0 && !search.query.action().equals("container")) {
            String command="/al container "+search.query.x()+" "+search.query.y()+" "+search.query.z();
            s.sendSuccess(() -> LogDisplay.button("  [只看此处物品存取]",command,true),false);
        }
    }
    private static void print(CommandSourceStack s,LogEvent e) {
        s.sendSuccess(() -> LogDisplay.entry(e),false);
    }
    private static void failed(CommandSourceStack s,Throwable ex) { AnriLogger.LOGGER.error("History query failed",ex); s.sendFailure(Component.literal("查询失败，详见服务端日志。")); }
    private static int status(CommandSourceStack s) {
        if(!acquire(s))return 0;
        int queued=AnriLogger.writer.queued();
        Object request=PENDING.get(key(s));
        AnriLogger.writer.query(HistoryStore::size).whenComplete((size,error)->s.getServer().execute(()-> {
            if(!PENDING.remove(key(s),request) || !allowed(s))return;
            if(error!=null)failed(s,error);else say(s,"记录数="+size+"，排队="+queued+"，单文件=config/anri-logger/history.db，会话="+AnriLogger.session);
        })); return 1;
    }
    private static int sessions(CommandSourceStack s) {
        if(!acquire(s))return 0;
        Object request=PENDING.get(key(s));
        AnriLogger.writer.query(HistoryStore::sessions).whenComplete((sessions,error)->s.getServer().execute(()-> {
            if(!PENDING.remove(key(s),request) || !allowed(s))return;
            if(error!=null)failed(s,error);else sessions.entrySet().stream().filter(e -> e.getValue().startsWith(AnriLogger.worldId))
                .sorted(Map.Entry.<String,String>comparingByValue().reversed()).limit(20).forEach(e -> say(s,e.getKey()+" | "+e.getValue()));
        })); return 1;
    }
    private static int show(CommandSourceStack s,long id) {
        if(!acquire(s))return 0;
        Object request=PENDING.get(key(s));
        AnriLogger.writer.query(store -> store.get(id)).whenComplete((e,error)->s.getServer().execute(()-> {
            if(!PENDING.remove(key(s),request) || !allowed(s))return;
            if(error!=null)failed(s,error);else if(e==null || !e.world().equals(AnriLogger.worldId))say(s,"记录不存在。");
            else { print(s,e); say(s,"会话="+e.session()+"，因果链="+e.cause()+"，UUID="+e.playerId()); say(s,e.detail()); }
        })); return 1;
    }
}
