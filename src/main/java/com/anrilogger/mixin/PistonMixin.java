package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.anrilogger.tracking.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.storage.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMixin implements CauseCarrier {
    @Unique private Cause al$cause;
    public Cause al$getCause() { return al$cause; }
    public void al$setCause(Cause cause) { al$cause=cause; }
    @Inject(method="<init>",at=@At("RETURN"))
    private void al$created(CallbackInfo ci) { if(Tracking.current()!=null)al$cause=Tracking.current().indirect(); }
    @WrapMethod(method="tick")
    private static void al$tick(Level level,BlockPos pos,BlockState state,PistonMovingBlockEntity piston,Operation<Void> original) {
        try(var scope=Tracking.scope(((CauseCarrier)piston).al$getCause())) { original.call(level,pos,state,piston); }
    }
    @WrapMethod(method="finalTick")
    private void al$finish(Operation<Void> original) {
        try(var scope=Tracking.scope(al$cause!=null?al$cause:Tracking.current())) { original.call(); }
    }
    @Inject(method="saveAdditional",at=@At("TAIL"))
    private void al$save(ValueOutput out,CallbackInfo ci) { CauseNbt.write(out,al$cause); }
    @Inject(method="loadAdditional",at=@At("TAIL"))
    private void al$load(ValueInput in,CallbackInfo ci) { al$cause=CauseNbt.read(in); }
}
