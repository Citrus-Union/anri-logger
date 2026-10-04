package com.anrilogger.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.anrilogger.tracking.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(Level.class)
public abstract class LevelMixin {
    @Unique private int al$depth;
    @WrapMethod(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean al$depth(BlockPos pos,BlockState state,int flags,int limit,Operation<Boolean> original) {
        Cause cause=Tracking.current();
        if(cause==null)return original.call(pos,state,flags,limit);
        try(var scope=Tracking.scope(cause!=null && al$depth>0?cause.indirect():cause)) {
            al$depth++; try { return original.call(pos,state,flags,limit); } finally { al$depth--; }
        }
    }
    @WrapOperation(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState al$change(LevelChunk chunk,BlockPos pos,BlockState state,int flags,Operation<BlockState> original) {
        BlockState before=original.call(chunk,pos,state,flags);
        Tracking.changed((Level)(Object)this,pos,before,chunk.getBlockState(pos)); return before;
    }
}
