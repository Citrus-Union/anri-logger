package com.anrilogger.test;
import com.anrilogger.*;
import com.anrilogger.store.*;
import com.anrilogger.tracking.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.concurrent.*;

public class AnriLoggerGameTests {
    @GameTest(maxTicks=100)
    public void propagationStopsAfterTenAcrossDelayedAndReloadedBranches(GameTestHelper helper) {
        var player=helper.makeMockServerPlayerInLevel(); var level=helper.getLevel();
        BlockPos origin=helper.absolutePos(new BlockPos(1,2,1)), other=origin.east();
        Cause root=Cause.player(player,"place",origin);
        Cause branch=root.indirect();
        try(var scope=Tracking.scope(branch)) {
            for(int i=0;i<7;i++)level.setBlock(other,(i%2==0?Blocks.STONE:Blocks.DIRT).defaultBlockState(),2);
        }
        var tnt=new PrimedTnt(level,other.getX(),other.getY(),other.getZ(),player);
        ((CauseCarrier)tnt).al$setCause(branch);
        var saved=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess());
        tnt.saveWithoutId(saved);
        helper.runAfterDelay(2,()-> {
            var loaded=new PrimedTnt(net.minecraft.world.entity.EntityType.TNT,level);
            loaded.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess(),saved.buildResult()));
            try(var scope=Tracking.scope(((CauseCarrier)loaded).al$getCause())) {
                for(int i=0;i<12;i++)level.setBlock(other,(i%2==0?Blocks.DIRT:Blocks.STONE).defaultBlockState(),2);
            }
            // Earlier queued branches must not get another allowance after a sibling exhausts it.
            try(var scope=Tracking.scope(branch)) { level.setBlock(other,Blocks.GOLD_BLOCK.defaultBlockState(),2); }
            // The player's actual placement is still recorded even if child callbacks ran first.
            try(var scope=Tracking.scope(root)) { level.setBlock(origin,Blocks.DIAMOND_BLOCK.defaultBlockState(),2); }
            var logs=events(player.getUUID().toString()).stream().filter(e->e.cause().equals(root.id())).toList();
            check(logs.stream().filter(e->e.action().equals("block-change")).count()==10,"indirect block allowance must be shared and capped at ten");
            check(logs.stream().anyMatch(e->e.action().equals("block-place")),"direct placement lost after indirect limit");
            check(level.getBlockState(other).is(Blocks.GOLD_BLOCK),"logging cap must not stop actual game updates");
            check(Tracking.current()==null,"leaked context after limited propagation");
            helper.succeed();
        });
    }
    private static final class Output implements net.minecraft.commands.CommandSource {
        final List<net.minecraft.network.chat.Component> lines=new ArrayList<>();
        public void sendSystemMessage(net.minecraft.network.chat.Component line) { lines.add(line); }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }
    @GameTest(maxTicks=200)
    public void everyInspectionRepliesWithoutCooldown(GameTestHelper helper) throws Exception {
        Output output=new Output();
        var source=helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel()).withSource(output);
        var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        com.anrilogger.command.AnriLoggerCommands.register(dispatcher);
        dispatcher.execute("al status",source); // ordinary request is still pending
        for(int i=0;i<6;i++) {
            BlockPos p=helper.absolutePos(new BlockPos(i,2,1));
            check(dispatcher.execute("al inspect "+p.getX()+" "+p.getY()+" "+p.getZ(),source)==1,"inspection rejected during cooldown");
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(output.lines.stream().filter(c->c.getString().contains("历史记录 ·")).count()==6,net.minecraft.network.chat.Component.literal("not every rapid inspection replied"));
            check(output.lines.stream().noneMatch(c->c.getString().contains("过于频繁")),"inspection throttled");
        });
    }
    @GameTest(maxTicks=200)
    public void shiftDepositVisibleInContainerQuery(GameTestHelper helper) throws Exception {
        var player=helper.makeMockServerPlayerInLevel(); var level=helper.getLevel();
        BlockPos pos=helper.absolutePos(new BlockPos(1,2,1));
        level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
        player.gameMode.useItemOn(player,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        var menu=player.containerMenu;
        ItemStack named=new ItemStack(Items.DIAMOND,37);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("测试钻石"));
        player.getInventory().setItem(9,named);
        int inventorySlot=menu.findSlot(player.getInventory(),9).orElseThrow();
        menu.clicked(inventorySlot,0,ContainerInput.QUICK_MOVE,player);
        var logs=events(player.getUUID().toString());
        var insertion=logs.stream().filter(e->e.action().equals("item-insert") && e.count()==37 && e.object().equals("minecraft:diamond")).findFirst().orElseThrow();
        check(insertion.detail().contains("测试钻石"),"item components lost");
        // Query immediately after the click, without waiting for the periodic flush.
        Output output=new Output(); var source=level.getServer().createCommandSourceStack().withLevel(level).withSource(output);
        var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        com.anrilogger.command.AnriLoggerCommands.register(dispatcher);
        dispatcher.execute("al container "+pos.getX()+" "+pos.getY()+" "+pos.getZ(),source);
        helper.succeedWhen(() -> {
            helper.assertTrue(output.lines.stream().anyMatch(c->c.getString().contains("向容器存入 37 个")),net.minecraft.network.chat.Component.literal("deposit absent in rendered command output"));
            check(output.lines.stream().noneMatch(c->c.getString().contains("打开 ")),"container-only view polluted with opens");
        });
    }
    @GameTest(maxTicks=100)
    public void coloredDisplayAndSafeUnknownItem(GameTestHelper helper) {
        var event=new LogEvent(42,System.currentTimeMillis(),1,"world","session","minecraft:overworld",1,2,3,"uuid","Alice","item-insert","missingmod:gem",64,"data","cause",0,0);
        var line=com.anrilogger.command.LogDisplay.entry(event);
        check(line.getString().contains("Alice 向容器存入 64 个 missingmod:gem"),"unclear quantity or missing fallback");
        List<net.minecraft.network.chat.TextColor> colors=new ArrayList<>();
        line.visit((style,text)-> { if(style.getColor()!=null)colors.add(style.getColor()); return Optional.<Void>empty(); },net.minecraft.network.chat.Style.EMPTY);
        check(colors.contains(net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.GREEN)),"insertion not green");
        check(line.getSiblings().getFirst().getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand,"record detail link missing");
        check(com.anrilogger.command.LogDisplay.help().stream().anyMatch(c->c.getString().contains("/al container")),"container command absent from help");
        helper.succeed();
    }
    private static void clickPacket(net.minecraft.server.level.ServerPlayer player,int slot,int button,ContainerInput input) {
        var menu=player.containerMenu;
        player.connection.handleContainerClick(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                menu.containerId,menu.getStateId(),(short)slot,(byte)button,input,
                it.unimi.dsi.fastutil.ints.Int2ObjectMaps.emptyMap(),net.minecraft.network.HashedStack.EMPTY));
    }
    private record BarrelQueryCheck(Output output,int ownCount,int otherCount,boolean merged) {}
    private static void queryBarrel(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher,
                                   net.minecraft.commands.CommandSourceStack source,List<BarrelQueryCheck> checks,
                                   String command,BlockPos pos,int own,int other,boolean merged) throws Exception {
        Output output=new Output();
        dispatcher.execute(command+" "+pos.getX()+" "+pos.getY()+" "+pos.getZ(),source.withSource(output));
        checks.add(new BarrelQueryCheck(output,own,other,merged));
    }
    @GameTest(maxTicks=200)
    public void barrelQueriesRespectOptionalTisAndLiveRule(GameTestHelper helper) throws Exception {
        boolean installed=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("carpet-tis-addition");
        java.lang.reflect.Field rule=installed?Class.forName("carpettisaddition.CarpetTISAdditionSettings").getField("largeBarrel"):null;
        boolean original=rule!=null && rule.getBoolean(null);
        var player=helper.makeMockServerPlayerInLevel(); var level=helper.getLevel();
        var source=level.getServer().createCommandSourceStack().withLevel(level);
        var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        com.anrilogger.command.AnriLoggerCommands.register(dispatcher);
        List<BarrelQueryCheck> checks=new ArrayList<>();
        try {
            if(rule!=null)rule.setBoolean(null,false);
            BlockPos[] first={helper.absolutePos(new BlockPos(1,2,1)),helper.absolutePos(new BlockPos(4,2,1)),helper.absolutePos(new BlockPos(1,2,4))};
            Direction[] facing={Direction.WEST,Direction.NORTH,Direction.DOWN};
            BlockPos[] second=new BlockPos[first.length];
            for(int i=0;i<first.length;i++) {
                second[i]=first[i].relative(facing[i].getOpposite());
                level.setBlockAndUpdate(first[i],Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING,facing[i]));
                level.setBlockAndUpdate(second[i],Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING,facing[i].getOpposite()));
                // With TIS absent or disabled these are two real, independent menus.
                for(int half=0;half<2;half++) {
                    BlockPos pos=half==0?first[i]:second[i];
                    player.setPos(Vec3.atCenterOf(pos));
                    player.gameMode.useItemOn(player,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
                    check(player.containerMenu.slots.size()==63,"inactive rule changed a normal barrel menu");
                    player.containerMenu.setCarried(new ItemStack(half==0?Items.DIAMOND:Items.EMERALD,(half==0?10:20)+i));
                    clickPacket(player,0,0,ContainerInput.PICKUP);
                    player.closeContainer();
                }
                queryBarrel(dispatcher,source,checks,"al container",first[i],10+i,20+i,false);
                queryBarrel(dispatcher,source,checks,"al container",second[i],20+i,10+i,false);
            }
            if(rule!=null)rule.setBoolean(null,true);
            for(int i=0;i<first.length;i++) {
                if(installed) {
                    player.setPos(Vec3.atCenterOf(first[i]));
                    player.gameMode.useItemOn(player,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(first[i]),Direction.UP,first[i],false));
                    var menu=player.containerMenu;
                    check(menu.slots.size()==90,"TIS large barrel did not open a six-row menu");
                    var compound=(com.anrilogger.mixin.CompoundContainerAccessor)menu.slots.getFirst().container;
                    int otherSlot=(compound.al$first()==level.getBlockEntity(second[i])?0:27)+1;
                    menu.setCarried(new ItemStack(Items.IRON_INGOT,30+2*i));
                    clickPacket(player,otherSlot,0,ContainerInput.PICKUP);
                    clickPacket(player,otherSlot,1,ContainerInput.PICKUP);
                    int ownSlot=(compound.al$first()==level.getBlockEntity(first[i])?0:27)+2;
                    menu.setCarried(new ItemStack(Items.DIAMOND,40+i));
                    clickPacket(player,ownSlot,0,ContainerInput.PICKUP);
                    player.closeContainer();
                    var logs=events(player.getUUID().toString());
                    BlockPos pos=second[i]; int removed=15+i;
                    check(logs.stream().anyMatch(e->e.action().equals("item-remove") && e.count()==removed
                            && e.x()==pos.getX() && e.y()==pos.getY() && e.z()==pos.getZ()),"large barrel packet withdrawal not recorded at physical half");
                }
                queryBarrel(dispatcher,source,checks,"al inspect",first[i],installed?40+i:10+i,installed?30+2*i:20+i,installed);
                queryBarrel(dispatcher,source,checks,"al container",first[i],10+i,20+i,installed);
                queryBarrel(dispatcher,source,checks,"al inspect",second[i],installed?30+2*i:20+i,installed?40+i:10+i,installed);
                queryBarrel(dispatcher,source,checks,"al container",second[i],20+i,10+i,installed);
            }
            if(rule!=null)rule.setBoolean(null,false);
            for(int i=0;i<first.length;i++) {
                queryBarrel(dispatcher,source,checks,"al container",first[i],10+i,20+i,false);
                queryBarrel(dispatcher,source,checks,"al container",second[i],20+i,10+i,false);
            }
            if(rule!=null) {
                rule.setBoolean(null,true);
                level.setBlockAndUpdate(second[0],level.getBlockState(second[0]).setValue(BarrelBlock.FACING,facing[0]));
                queryBarrel(dispatcher,source,checks,"al container",first[0],10,20,false);
            }
        } finally {
            if(rule!=null)rule.setBoolean(null,original);
        }
        helper.succeedWhen(() -> {
            for(BarrelQueryCheck test:checks) {
                helper.assertTrue(test.output().lines.stream().anyMatch(c->c.getString().contains("向容器存入 "+test.ownCount()+" 个")),net.minecraft.network.chat.Component.literal("barrel's own history missing"));
                check(test.output().lines.stream().anyMatch(c->c.getString().contains("向容器存入 "+test.otherCount()+" 个"))==test.merged(),"large barrel merge did not follow installation, live rule, or facing");
                check(test.output().lines.stream().anyMatch(c->c.getString().contains("包含大木桶两侧"))==test.merged(),"large barrel scope label incorrect");
            }
            AnriLogger.LOGGER.info("Large barrel query matrix passed: TIS installed={}, {} queries, all three axes and both sides",installed,checks.size());
        });
    }
    @GameTest(maxTicks=200)
    public void doubleChestInspectionIncludesBothHalves(GameTestHelper helper) throws Exception {
        var player=helper.makeMockServerPlayerInLevel(); var level=helper.getLevel();
        BlockPos first=helper.absolutePos(new BlockPos(1,2,1)),second=first.east();
        level.setBlockAndUpdate(first,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT));
        level.setBlockAndUpdate(second,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        player.setPos(Vec3.atCenterOf(first));
        player.gameMode.useItemOn(player,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(first),Direction.UP,first,false));
        var menu=player.containerMenu;
        check(menu.slots.size()==90,"double chest did not open");
        // Use a slot in the half opposite to the block that opened the menu.
        var compound=(com.anrilogger.mixin.CompoundContainerAccessor)menu.slots.get(0).container;
        int slot=compound.al$first()==level.getBlockEntity(second)?0:27;
        menu.setCarried(new ItemStack(Items.DIAMOND,12));
        clickPacket(player,slot,0,ContainerInput.PICKUP);
        clickPacket(player,slot,1,ContainerInput.PICKUP);
        var logs=events(player.getUUID().toString());
        check(logs.stream().anyMatch(e->e.action().equals("item-insert") && e.x()==second.getX()),"test must deposit in the other half");
        Output inspect=new Output(),container=new Output(),otherHalf=new Output();
        var source=level.getServer().createCommandSourceStack().withLevel(level);
        var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        com.anrilogger.command.AnriLoggerCommands.register(dispatcher);
        dispatcher.execute("al inspect "+first.getX()+" "+first.getY()+" "+first.getZ(),source.withSource(inspect));
        dispatcher.execute("al container "+first.getX()+" "+first.getY()+" "+first.getZ(),source.withSource(container));
        dispatcher.execute("al container "+second.getX()+" "+second.getY()+" "+second.getZ(),source.withSource(otherHalf));
        helper.succeedWhen(() -> {
            for(Output output:List.of(inspect,container,otherHalf)) {
                helper.assertTrue(output.lines.stream().anyMatch(c->c.getString().contains("向容器存入 12 个")),net.minecraft.network.chat.Component.literal("other half deposit absent in double chest query"));
                helper.assertTrue(output.lines.stream().anyMatch(c->c.getString().contains("从容器取出 6 个")),net.minecraft.network.chat.Component.literal("other half withdrawal absent in double chest query"));
                check(output.lines.stream().anyMatch(c->c.getString().contains("包含双箱两侧")),"double chest scope absent in query output");
            }
            check(container.lines.stream().noneMatch(c->c.getString().contains("打开 ")),"container query included opens");
        });
    }
    @GameTest(maxTicks=200)
    public void chestPacketsVisibleInInspection(GameTestHelper helper) throws Exception {
        var player=helper.makeMockServerPlayerInLevel(); var level=helper.getLevel();
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        BlockPos pos=helper.absolutePos(new BlockPos(1,2,1));
        level.setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());
        player.setPos(Vec3.atCenterOf(pos));
        player.gameMode.useItemOn(player,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        var menu=player.containerMenu;
        ItemStack named=new ItemStack(Items.DIAMOND,37);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("网络测试钻石"));
        player.getInventory().setItem(9,named);
        clickPacket(player,menu.findSlot(player.getInventory(),9).orElseThrow(),0,ContainerInput.QUICK_MOVE);
        clickPacket(player,0,1,ContainerInput.PICKUP);
        var logs=events(player.getUUID().toString());
        check(logs.stream().filter(e->e.action().equals("item-insert") && e.count()==37 && e.detail().contains("网络测试钻石")).count()==1,"packet deposit missing or duplicated");
        check(logs.stream().filter(e->e.action().equals("item-remove") && e.count()==19 && e.object().equals("minecraft:diamond")).count()==1,"packet withdrawal missing or duplicated");
        Output output=new Output(); var source=level.getServer().createCommandSourceStack().withLevel(level).withSource(output);
        var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        com.anrilogger.command.AnriLoggerCommands.register(dispatcher);
        dispatcher.execute("al inspect "+pos.getX()+" "+pos.getY()+" "+pos.getZ(),source);
        helper.succeedWhen(() -> {
            helper.assertTrue(output.lines.stream().anyMatch(c->c.getString().contains("向容器存入 37 个")),net.minecraft.network.chat.Component.literal("packet deposit absent in inspection"));
            helper.assertTrue(output.lines.stream().anyMatch(c->c.getString().contains("从容器取出 19 个")),net.minecraft.network.chat.Component.literal("packet withdrawal absent in inspection"));
        });
    }
    @GameTest(maxTicks=100)
    public void directPotAndPersistentEntityCause(GameTestHelper helper) {
        var player=helper.makeMockServerPlayerInLevel(); var level=helper.getLevel();
        BlockPos pos=helper.absolutePos(new BlockPos(1,2,1)); level.setBlockAndUpdate(pos,Blocks.DECORATED_POT.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND,7));
        player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        check(events(player.getUUID().toString()).stream().anyMatch(e->e.action().equals("item-insert") && e.count()==1 && e.object().equals("minecraft:diamond")),"direct pot insertion missing");
        Cause cause=Cause.player(player,"use",pos);
        var tnt=new PrimedTnt(level,pos.getX(),pos.getY(),pos.getZ(),player); ((CauseCarrier)tnt).al$setCause(cause);
        var out=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess());
        tnt.saveWithoutId(out);
        var loaded=new PrimedTnt(net.minecraft.world.entity.EntityType.TNT,level);
        loaded.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess(),out.buildResult()));
        check(cause.id().equals(((CauseCarrier)loaded).al$getCause().id()),"entity cause not persisted");
        check(cause.playerId().equals(((CauseCarrier)loaded).al$getCause().playerId()),"entity actor not persisted");
        helper.succeed();
    }
    @GameTest(maxTicks=100)
    public void doubleChestDragAndHotbar(GameTestHelper helper) {
        var player=helper.makeMockServerPlayerInLevel(); var level=helper.getLevel();
        BlockPos first=helper.absolutePos(new BlockPos(1,2,1)),second=first.east();
        level.setBlockAndUpdate(first,Blocks.CHEST.defaultBlockState()); level.setBlockAndUpdate(second,Blocks.CHEST.defaultBlockState());
        var a=(ChestBlockEntity)level.getBlockEntity(first); var b=(ChestBlockEntity)level.getBlockEntity(second);
        var menu=ChestMenu.sixRows(78,player.getInventory(),new CompoundContainer(a,b));
        player.containerMenu=menu;
        menu.setCarried(new ItemStack(Items.DIAMOND,12));
        menu.clicked(-999,0,ContainerInput.QUICK_CRAFT,player);
        menu.clicked(2,1,ContainerInput.QUICK_CRAFT,player);
        menu.clicked(29,1,ContainerInput.QUICK_CRAFT,player);
        menu.clicked(-999,2,ContainerInput.QUICK_CRAFT,player);
        check(a.getItem(2).getCount()==6 && b.getItem(2).getCount()==6,"drag setup failed");
        player.getInventory().setItem(0,new ItemStack(Items.EMERALD,5));
        menu.clicked(29,0,ContainerInput.SWAP,player);
        var logs=events(player.getUUID().toString());
        check(logs.stream().filter(e->e.action().equals("item-insert") && e.object().equals("minecraft:diamond") && e.count()==6).count()==2,"double chest drag not split into physical halves");
        check(logs.stream().anyMatch(e->e.action().equals("item-remove") && e.object().equals("minecraft:diamond") && e.x()==second.getX()),"hotbar removal missing");
        check(logs.stream().anyMatch(e->e.action().equals("item-insert") && e.object().equals("minecraft:emerald") && e.count()==5),"hotbar insertion missing");
        helper.succeed();
    }
    @GameTest(maxTicks=100)
    public void permissionsAliasesAndInspect(GameTestHelper helper) throws Exception {
        var player=helper.makeMockServerPlayerInLevel(); var source=player.createCommandSourceStack();
        var old=AnriLogger.config.permissionMode; boolean oldAlias=AnriLogger.config.enableLgAlias;
        try {
            AnriLogger.config.permissionMode=AnriLoggerConfig.PermissionMode.OP_ONLY;
            check(!com.anrilogger.command.AnriLoggerCommands.allowed(source.withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS)),"non-op allowed");
            var identity=new net.minecraft.server.players.NameAndId(player.getGameProfile());
            helper.getLevel().getServer().getPlayerList().op(identity,Optional.empty(),Optional.empty());
            check(com.anrilogger.command.AnriLoggerCommands.allowed(source.withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS)),"op membership denied");
            helper.getLevel().getServer().getPlayerList().deop(identity);
            AnriLogger.config.permissionMode=AnriLoggerConfig.PermissionMode.EVERYONE;
            check(com.anrilogger.command.AnriLoggerCommands.allowed(source.withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS)),"everyone denied");
            var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
            AnriLogger.config.enableLgAlias=false; com.anrilogger.command.AnriLoggerCommands.register(dispatcher);
            check(dispatcher.getRoot().getChild("lg")==null,"disabled alias registered");
            AnriLogger.config.enableLgAlias=true; com.anrilogger.command.AnriLoggerCommands.register(dispatcher);
            check(dispatcher.getRoot().getChild("lg")!=null,"alias missing");
            check(dispatcher.getRoot().getChild("al").getChild("rollback")==null,"unexpected rollback");
            dispatcher.execute("al inspect on",source);
            BlockPos pos=helper.absolutePos(new BlockPos(1,2,1)); helper.getLevel().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
            player.gameMode.destroyBlock(pos);
            check(helper.getLevel().getBlockState(pos).is(Blocks.STONE),"inspect destroyed block");
            dispatcher.execute("al inspect off",source);
        } finally { AnriLogger.config.permissionMode=old; AnriLogger.config.enableLgAlias=oldAlias; }
        helper.succeed();
    }
    @GameTest(maxTicks=40)
    public void pistonDelayedCompletion(GameTestHelper helper) {
        var player=helper.makeMockServerPlayerInLevel(); var level=helper.getLevel();
        BlockPos piston=helper.absolutePos(new BlockPos(2,2,2));
        level.setBlockAndUpdate(piston,Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING,Direction.EAST));
        level.setBlockAndUpdate(piston.east(),Blocks.STONE.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.REDSTONE_BLOCK));
        BlockPos support=piston.west().below(); level.setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());
        player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(support),Direction.UP,support,false));
        helper.runAfterDelay(15,()-> {
            check(level.getBlockState(piston.east(2)).is(Blocks.STONE),"piston setup did not move block");
            check(events(player.getUUID().toString()).stream().anyMatch(e->e.action().equals("block-change") && e.x()==piston.east(2).getX() && e.object().equals("minecraft:stone")),"piston completion attribution missing");
            helper.succeed();
        });
    }
    private static List<LogEvent> events(String player) {
        return AnriLogger.writer.query(s -> s.search(new Query(AnriLogger.worldId,"@all",0,0,0,-1,player,"","","","",0,Long.MAX_VALUE),s.lastId(),Long.MAX_VALUE,200,100000).events()).join();
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    @GameTest(maxTicks=100)
    public void blockAndContainer(GameTestHelper helper) {
        var player=helper.makeMockServerPlayerInLevel(); player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        var level=helper.getLevel(); BlockPos pos=helper.absolutePos(new BlockPos(1,2,1));
        level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
        player.gameMode.destroyBlock(pos);
        check(events(player.getUUID().toString()).stream().anyMatch(e -> e.action().equals("block-break") && e.object().equals("minecraft:stone")),"missing successful break");
        BlockPos support=pos.below(); level.setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.CHEST));
        player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(support),Direction.UP,support,false));
        check(level.getBlockEntity(pos) instanceof ChestBlockEntity,"chest placement failed");
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        player.gameMode.useItemOn(player,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        var chest=(ChestBlockEntity)level.getBlockEntity(pos); var menu=player.containerMenu;
        menu.setCarried(new ItemStack(Items.DIAMOND,17)); menu.clicked(0,0,ContainerInput.PICKUP,player);
        menu.clicked(0,1,ContainerInput.PICKUP,player); // take ceil(17/2)=9
        menu.clicked(1,0,ContainerInput.PICKUP,player); // put 9 back in same container
        chest.setItem(2,new ItemStack(Items.IRON_INGOT,23)); // automation, not player
        menu.clicked(2,0,ContainerInput.QUICK_MOVE,player);
        var logs=events(player.getUUID().toString());
        check(logs.stream().anyMatch(e->e.action().equals("block-place") && e.object().equals("minecraft:chest")),"missing placement");
        check(logs.stream().anyMatch(e->e.action().equals("item-insert") && e.count()==17),"missing insert 17");
        check(logs.stream().anyMatch(e->e.action().equals("item-remove") && e.count()==9),"missing remove 9");
        check(logs.stream().anyMatch(e->e.action().equals("item-remove") && e.count()==23),"missing shift remove 23");
        check(logs.stream().noneMatch(e->e.action().equals("item-insert") && e.object().equals("minecraft:iron_ingot")),"automation misattributed");
        check(Tracking.current()==null,"leaked context"); helper.succeed();
    }
    @GameTest(maxTicks=160)
    public void fallingSandAndTnt(GameTestHelper helper) {
        var player=helper.makeMockServerPlayerInLevel(); player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        var level=helper.getLevel(); BlockPos support=helper.absolutePos(new BlockPos(1,3,1));
        level.setBlockAndUpdate(support.below(2),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(support.above(),Blocks.SAND.defaultBlockState());
        // Wait for the unowned initial placement tick to finish, then break the support.
        helper.runAfterDelay(5,()->player.gameMode.destroyBlock(support));
        helper.runAfterDelay(50,()-> {
            var logs=events(player.getUUID().toString());
            check(logs.stream().anyMatch(e->e.action().equals("entity-trigger") && e.object().equals("minecraft:falling_block")),"falling sand not attributed");
            check(logs.stream().anyMatch(e->e.action().equals("block-change") && e.object().equals("minecraft:sand")),"sand landing missing");
            BlockPos tntPos=helper.absolutePos(new BlockPos(3,2,3));
            level.setBlockAndUpdate(tntPos,Blocks.TNT.defaultBlockState());
            level.setBlockAndUpdate(tntPos.east(),Blocks.STONE.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.FLINT_AND_STEEL));
            player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(tntPos),Direction.UP,tntPos,false));
        });
        helper.runAfterDelay(140,()-> {
            var logs=events(player.getUUID().toString());
            check(logs.stream().anyMatch(e->e.action().equals("explosion")),"TNT explosion not attributed");
            check(logs.stream().anyMatch(e->e.action().equals("block-change") && e.detail().contains("minecraft:stone")),"explosion block damage missing");
            check(Tracking.current()==null,"leaked context"); helper.succeed();
        });
    }
}
