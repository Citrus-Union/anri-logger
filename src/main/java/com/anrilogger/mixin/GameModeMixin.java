package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.anrilogger.command.AnriLoggerCommands;
import com.anrilogger.tracking.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;
@Mixin(ServerPlayerGameMode.class)
public abstract class GameModeMixin {
    @Shadow @Final protected ServerPlayer player;
    @Shadow protected ServerLevel level;
    @WrapMethod(method="destroyBlock")
    private boolean al$break(BlockPos pos,Operation<Boolean> original) {
        if(AnriLoggerCommands.inspectClick(player,pos))return false;
        try(var scope=Tracking.scope(Cause.player(player,"break",pos))) { return original.call(pos); }
    }
    @WrapMethod(method="useItemOn")
    private InteractionResult al$use(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,Operation<InteractionResult> original) {
        if(AnriLoggerCommands.inspectClick(player,hit.getBlockPos()))return InteractionResult.SUCCESS_SERVER;
        Cause cause=Cause.player(player,"use",hit.getBlockPos());
        String object=Tracking.block(level.getBlockState(hit.getBlockPos()));
        var oldMenu=player.containerMenu;
        var block=level.getBlockEntity(hit.getBlockPos());
        var before=ContainerTracking.blockSnapshot(block,player);
        try(var scope=Tracking.scope(cause)) {
            InteractionResult result=original.call(player,level,stack,hand,hit);
            // Menu opening may unpack a loot table; that is not a player deposit.
            if(result.consumesAction() && player.containerMenu==oldMenu && block==level.getBlockEntity(hit.getBlockPos()))
                ContainerTracking.difference(player,cause,before,ContainerTracking.blockSnapshot(block,player));
            if(result.consumesAction())Tracking.log((ServerLevel)level,hit.getBlockPos(),cause,"block-use",object,1,"hand="+hand);
            if(player.containerMenu!=oldMenu) {
                ContainerTracking.open(player.containerMenu,hit.getBlockPos());
                Tracking.log((ServerLevel)level,hit.getBlockPos(),cause,"container-open",object,1,"");
            }
            return result;
        }
    }
    @WrapMethod(method="useItem")
    private InteractionResult al$item(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,Operation<InteractionResult> original) {
        try(var scope=Tracking.scope(Cause.player(player,"use",player.blockPosition()))) { return original.call(player,level,stack,hand); }
    }
}
