package com.anrilogger.compat;

import com.anrilogger.AnriLogger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional integration: cache accessors, but read the live rule on every inspection. */
public final class CarpetTisCompat {
    private record Access(Field rule,Method otherPosition) {}
    private static final Access ACCESS=resolve();
    private static boolean warned;
    private CarpetTisCompat() {}
    private static Access resolve() {
        if(!FabricLoader.getInstance().isModLoaded("carpet-tis-addition"))return null;
        try {
            Field rule=Class.forName("carpettisaddition.CarpetTISAdditionSettings").getField("largeBarrel");
            Method other=Class.forName("carpettisaddition.helpers.rule.largeBarrel.LargeBarrelHelper")
                    .getMethod("getOtherPos",BlockState.class,Level.class,BlockPos.class);
            return new Access(rule,other);
        } catch(ReflectiveOperationException|LinkageError ex) {
            AnriLogger.LOGGER.warn("Cannot enable Carpet TIS largeBarrel queries; inspecting individual positions only",ex);
            return null;
        }
    }
    public static BlockPos otherBarrel(ServerLevel level,BlockPos pos,BlockState state) {
        if(ACCESS==null || !(state.getBlock() instanceof BarrelBlock))return null;
        try {
            if(!ACCESS.rule().getBoolean(null))return null;
            // TIS checks the barrel behind this one's facing. Do not load that chunk to inspect it.
            BlockPos candidate=pos.relative(state.getValue(BarrelBlock.FACING).getOpposite());
            if(!level.hasChunkAt(candidate))return null;
            BlockPos other=(BlockPos)ACCESS.otherPosition().invoke(null,state,level,pos);
            return candidate.equals(other)?other:null;
        } catch(ReflectiveOperationException|LinkageError ex) {
            if(!warned) {
                warned=true;
                AnriLogger.LOGGER.warn("Carpet TIS largeBarrel lookup failed; inspecting this position only",ex);
            }
            return null;
        }
    }
}
