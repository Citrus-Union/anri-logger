package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.anrilogger.AnriLogger;
import com.anrilogger.tracking.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;
import java.util.function.BiConsumer;
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> {
    @Unique private final Map<ScheduledTick<T>,Cause> al$causes=new WeakHashMap<>();
    @Shadow public abstract boolean hasScheduledTick(BlockPos pos,T type);
    @Inject(method="schedule",at=@At("HEAD"))
    private void al$schedule(ScheduledTick<T> tick,CallbackInfo ci) {
        Cause cause=Tracking.current();
        if(cause!=null && cause.canPropagate() && !hasScheduledTick(tick.pos(),tick.type())) {
            if(al$causes.size()>=AnriLogger.config.maxTrackedScheduledTicks) {
                AnriLogger.LOGGER.warn("Scheduled cause tracking limit reached; new delayed attribution omitted"); return;
            }
            al$causes.put(tick,cause.indirect());
        }
    }
    @WrapOperation(method="runCollectedTicks",at=@At(value="INVOKE",target="Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void al$run(BiConsumer<BlockPos,T> output,Object pos,Object type,Operation<Void> original,@Local ScheduledTick<T> entry) {
        try(var scope=Tracking.scope(al$causes.remove(entry))) { original.call(output,pos,type); }
    }
}
