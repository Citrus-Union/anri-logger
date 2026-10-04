package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.anrilogger.*;
import com.anrilogger.tracking.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import java.util.*;
@Mixin(ServerLevel.class)
public abstract class BlockEventMixin {
    @Unique private final Map<BlockEventData,Cause> al$blockEvents=new WeakHashMap<>();
    @WrapOperation(method="blockEvent",at=@At(value="INVOKE",target="Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;add(Ljava/lang/Object;)Z"))
    private boolean al$queue(ObjectLinkedOpenHashSet<BlockEventData> events,Object event,Operation<Boolean> original) {
        boolean added=original.call(events,event); Cause cause=Tracking.current();
        if(added && cause!=null && cause.canPropagate() && al$blockEvents.size()<AnriLogger.config.maxTrackedScheduledTicks)al$blockEvents.put((BlockEventData)event,cause.indirect());
        return added;
    }
    @WrapMethod(method="doBlockEvent")
    private boolean al$event(BlockEventData event,Operation<Boolean> original) {
        try(var scope=Tracking.scope(al$blockEvents.remove(event))) { return original.call(event); }
    }
}
