package com.anrilogger;
import com.anrilogger.command.AnriLoggerCommands;
import com.anrilogger.store.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.*;
import java.nio.file.Path;
import java.util.UUID;
public final class AnriLogger implements ModInitializer {
    public static final Logger LOGGER=LoggerFactory.getLogger("anri-logger");
    public static AnriLoggerConfig config;
    public static volatile HistoryWriter writer;
    public static String worldId,session;
    @Override public void onInitialize() {
        Path directory=FabricLoader.getInstance().getConfigDir().resolve("anri-logger");
        try { config=AnriLoggerConfig.load(directory.resolve("config.json")); }
        catch(Exception ex) { throw new IllegalStateException("Cannot load Anri Logger configuration",ex); }
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment) -> AnriLoggerCommands.register(dispatcher));
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player,level,hand,pos,direction) ->
            player instanceof net.minecraft.server.level.ServerPlayer sp && AnriLoggerCommands.inspectClick(sp,pos)
                ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                com.anrilogger.tracking.PropagationBudget.clear();
                HistoryStore store=new HistoryStore(directory.resolve("history.db"));
                worldId=store.worldId(server.getWorldPath(LevelResource.ROOT).toRealPath().toString());
                session=UUID.randomUUID().toString();
                store.session(session,worldId+" | "+java.time.Instant.now()+" | "+server.getWorldPath(LevelResource.ROOT));
                store.flush(); writer=new HistoryWriter(store,config.queueCapacity,config.flushIntervalMillis);
                LOGGER.info("Single-file history opened: {}; session {}",directory.resolve("history.db"),session);
            } catch(Exception ex) { throw new IllegalStateException("Cannot open history database; refusing to run without audit storage",ex); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if(writer!=null && writer.failure()!=null) {
                LOGGER.error("History storage failed. Stopping server to prevent unlogged activity.",writer.failure()); server.halt(false);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> AnriLoggerCommands.disconnect(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            com.anrilogger.tracking.PropagationBudget.clear();
            HistoryWriter closing=writer; writer=null; AnriLoggerCommands.clear(); if(closing!=null)closing.close();
        });
    }
}
