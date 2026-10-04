package com.anrilogger.tracking;
import net.minecraft.world.level.storage.*;
import java.util.UUID;
public final class CauseNbt {
    public static void write(ValueOutput out,Cause cause) {
        if(cause==null)return;
        out.putString("anri_logger_cause",cause.id()); out.putString("anri_logger_player",cause.playerId()); out.putString("anri_logger_name",cause.playerName());
    }
    public static Cause read(ValueInput in) {
        String id=in.getStringOr("anri_logger_cause",""),player=in.getStringOr("anri_logger_player","");
        try {
            UUID.fromString(id); UUID.fromString(player);
            return new Cause(id,player,in.getStringOr("anri_logger_name",player),"indirect",0,PropagationBudget.restore(id));
        } catch(IllegalArgumentException ignored) { return null; }
    }
}
