package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.anrilogger.tracking.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Entity source;
    @Shadow @Final private Vec3 center;
    @WrapMethod(method="explode")
    private int al$explode(Operation<Integer> original) {
        Cause cause=Tracking.current(); if(cause==null)cause=Tracking.fromEntity(source);
        if(cause!=null)cause=cause.indirect();
        try(var scope=Tracking.scope(cause)) {
            Tracking.log(level,BlockPos.containing(center),cause,"explosion",source==null?"minecraft:explosion":net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(source.getType()).toString(),1,"");
            return original.call();
        }
    }
}
