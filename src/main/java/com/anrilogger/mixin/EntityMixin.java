package com.anrilogger.mixin;
import com.anrilogger.tracking.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Entity.class)
public abstract class EntityMixin implements CauseCarrier {
    @Unique private Cause al$cause;
    @Override public Cause al$getCause() { return al$cause; }
    @Override public void al$setCause(Cause cause) { al$cause=cause; }
    @Inject(method="saveWithoutId",at=@At("TAIL"))
    private void al$save(ValueOutput out,CallbackInfo ci) {
        CauseNbt.write(out,al$cause);
    }
    @Inject(method="load",at=@At("TAIL"))
    private void al$load(ValueInput in,CallbackInfo ci) {
        al$cause=CauseNbt.read(in);
    }
}
