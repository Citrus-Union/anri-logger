package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.anrilogger.tracking.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.*;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @WrapMethod(method="addFreshEntity")
    private boolean al$spawn(Entity entity,Operation<Boolean> original) {
        Cause cause=Tracking.current();
        if(cause==null)cause=Tracking.fromEntity(entity);
        boolean tracked=entity instanceof PrimedTnt || entity instanceof FallingBlockEntity || entity instanceof Projectile;
        // Keep an exhausted source as a tombstone so owner fallback cannot mint a fresh allowance.
        if(cause!=null && tracked)((CauseCarrier)entity).al$setCause(cause.indirect());
        boolean added=original.call(entity);
        if(added && tracked && cause!=null && cause.canPropagate())Tracking.log((ServerLevel)(Object)this,entity.blockPosition(),cause,"entity-trigger",
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),1,entity.getUUID().toString());
        return added;
    }
    @WrapMethod(method="tickNonPassenger")
    private void al$tick(Entity entity,Operation<Void> original) {
        // A player tick is not a causal action: never attribute surrounding simulation to mere presence.
        Cause cause=entity instanceof PrimedTnt || entity instanceof FallingBlockEntity || entity instanceof Projectile ? Tracking.fromEntity(entity):null;
        try(var scope=Tracking.scope(cause)) { original.call(entity); }
    }
}
