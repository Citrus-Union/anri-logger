package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.anrilogger.tracking.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuMixin {
    @WrapMethod(method="clicked")
    private void al$click(int slot,int button,ContainerInput input,Player player,Operation<Void> original) {
        if(!(player instanceof ServerPlayer serverPlayer)) { original.call(slot,button,input,player); return; }
        var menu=(AbstractContainerMenu)(Object)this;
        var before=ContainerTracking.snapshot(menu,serverPlayer);
        Cause cause=Cause.player(serverPlayer,"container",player.blockPosition());
        try(var scope=Tracking.scope(cause)) {
            try { original.call(slot,button,input,player); }
            finally { ContainerTracking.difference(serverPlayer,cause,before,ContainerTracking.snapshot(menu,serverPlayer)); }
        }
    }
}
