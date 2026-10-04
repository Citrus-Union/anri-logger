package com.anrilogger.tracking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
public record Cause(String id, String playerId, String playerName, String kind, long origin, PropagationBudget budget) {
    public static Cause player(ServerPlayer player,String kind,BlockPos pos) {
        String id=UUID.randomUUID().toString();
        return new Cause(id,player.getUUID().toString(),player.getGameProfile().name(),kind,pos.asLong(),PropagationBudget.start(id));
    }
    public Cause indirect() { return kind.equals("indirect") ? this : at("indirect",origin); }
    public Cause at(String kind,long origin) { return new Cause(id,playerId,playerName,kind,origin,budget); }
    public boolean canPropagate() { return budget.available(); }
}
