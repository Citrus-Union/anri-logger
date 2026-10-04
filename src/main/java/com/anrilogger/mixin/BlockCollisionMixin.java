package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.anrilogger.tracking.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockCollisionMixin {
    @WrapMethod(method="entityInside")
    private void al$inside(Level level,BlockPos pos,Entity entity,InsideBlockEffectApplier applier,boolean effect,Operation<Void> original) {
        Cause cause=Tracking.current();
        if(cause==null && entity instanceof ServerPlayer player)cause=Cause.player(player,"indirect",pos);
        try(var scope=Tracking.scope(cause)) { original.call(level,pos,entity,applier,effect); }
    }
}
