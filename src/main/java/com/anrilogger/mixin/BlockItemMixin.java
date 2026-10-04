package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.anrilogger.tracking.*;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @WrapMethod(method="placeBlock")
    private boolean al$place(BlockPlaceContext context,BlockState state,Operation<Boolean> original) {
        Cause old=Tracking.current();
        Cause place=old==null?null:old.at("place",context.getClickedPos().asLong());
        try(var scope=Tracking.scope(place)) { return original.call(context,state); }
    }
}
