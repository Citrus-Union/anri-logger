package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.anrilogger.tracking.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(LecternMenu.class)
public abstract class LecternMenuMixin {
    @WrapMethod(method="clickMenuButton")
    private boolean al$button(Player player,int button,Operation<Boolean> original) {
        var pos=ContainerTracking.openedAt((AbstractContainerMenu)(Object)this);
        if(!(player instanceof ServerPlayer sp) || pos==null)return original.call(player,button);
        var block=player.level().getBlockEntity(pos); var before=ContainerTracking.blockSnapshot(block,sp);
        Cause cause=Cause.player(sp,"container",pos);
        try(var scope=Tracking.scope(cause)) {
            boolean result=original.call(player,button);
            if(result)ContainerTracking.difference(sp,cause,before,ContainerTracking.blockSnapshot(block,sp));
            return result;
        }
    }
}
