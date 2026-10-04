package com.anrilogger.tracking;
import com.anrilogger.AnriLogger;
import com.anrilogger.store.LogEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
public final class Tracking {
    private static final ThreadLocal<Cause> CURRENT=new ThreadLocal<>();
    private Tracking() {}
    public static Cause current() { return CURRENT.get(); }
    public static Scope scope(Cause cause) { Cause old=CURRENT.get(); if(cause==null)CURRENT.remove();else CURRENT.set(cause); return () -> { if(old==null)CURRENT.remove();else CURRENT.set(old); }; }
    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
    public static Cause fromEntity(Entity entity) {
        if(entity==null)return null;
        Cause saved=((CauseCarrier)entity).al$getCause();
        if(saved!=null)return saved.indirect();
        if(entity instanceof ServerPlayer player)return Cause.player(player,"indirect",player.blockPosition());
        if(entity instanceof TraceableEntity trace && trace.getOwner()!=null && trace.getOwner()!=entity) {
            Entity owner=trace.getOwner();
            if(owner instanceof ServerPlayer player)return Cause.player(player,"indirect",entity.blockPosition());
            Cause ownerCause=((CauseCarrier)owner).al$getCause();
            return ownerCause==null?null:ownerCause.indirect();
        }
        return null;
    }
    public static String block(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
    public static void changed(Level level,BlockPos pos,BlockState before,BlockState after) {
        Cause cause=current();
        if(cause==null || before==null || before==after || AnriLogger.writer==null || !(level instanceof ServerLevel serverLevel))return;
        boolean direct=!cause.kind().equals("indirect") && cause.origin()==pos.asLong();
        // Count changes, including repeated changes at one position, so redstone clocks also stop.
        if(!direct && !cause.budget().recordBlock())return;
        String action="block-change";
        if(cause.kind().equals("break") && cause.origin()==pos.asLong())action="block-break";
        else if(cause.kind().equals("place") && direct)action="block-place";
        append(serverLevel,pos,cause,action,action.equals("block-break") || after.isAir() ? block(before):block(after),1,before+" -> "+after);
    }
    public static void log(ServerLevel level,BlockPos pos,Cause cause,String action,String object,int count,String detail) {
        if(cause==null || AnriLogger.writer==null)return;
        if(cause.kind().equals("indirect") && !cause.canPropagate())return;
        append(level,pos,cause,action,object,count,detail);
    }
    private static void append(ServerLevel level,BlockPos pos,Cause cause,String action,String object,int count,String detail) {
        AnriLogger.writer.append(new LogEvent(0,System.currentTimeMillis(),level.getGameTime(),AnriLogger.worldId,AnriLogger.session,
                level.dimension().identifier().toString(),pos.getX(),pos.getY(),pos.getZ(),cause.playerId(),cause.playerName(),
                action,object,count,detail,cause.id(),0,0));
    }
}
