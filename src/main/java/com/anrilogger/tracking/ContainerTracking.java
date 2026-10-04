package com.anrilogger.tracking;

import com.anrilogger.mixin.CompoundContainerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.*;
import java.util.*;

/** Snapshot only the synchronous click. Serialize components only for slots that actually changed. */
public final class ContainerTracking {
    private static final Map<AbstractContainerMenu,BlockPos> OPEN=new WeakHashMap<>();
    public static void open(AbstractContainerMenu menu,BlockPos pos) { OPEN.put(menu,pos.immutable()); }
    public static BlockPos openedAt(AbstractContainerMenu menu) { return OPEN.get(menu); }
    private record SlotAddress(Object container,int slot) {}
    private record StackAt(BlockPos pos,ItemStack stack) {}
    public static final class Snapshot {
        private final Map<SlotAddress,StackAt> slots=new HashMap<>();
        private boolean readable=true;
        private void add(Object container,int slot,BlockPos pos,ItemStack stack) {
            slots.put(new SlotAddress(container,slot),new StackAt(pos.immutable(),stack.copy()));
        }
    }
    private record ItemKey(BlockPos pos,String item,String data) {}
    private record Physical(Container container,int slot) {}
    private static Physical physical(Container container,int slot) {
        if(container instanceof CompoundContainerAccessor compound) {
            int size=compound.al$first().getContainerSize();
            return slot<size?physical(compound.al$first(),slot):physical(compound.al$second(),slot-size);
        }
        return new Physical(container,slot);
    }
    public static Snapshot blockSnapshot(BlockEntity block,ServerPlayer player) {
        Snapshot result=new Snapshot();
        if(block instanceof RandomizableContainer loot && loot.getLootTable()!=null) { result.readable=false; return result; }
        // Only direct-use inventories. Reading unopened loot containers can generate their loot!
        if(block instanceof DecoratedPotBlockEntity pot)result.add(block,0,block.getBlockPos(),pot.getTheItem());
        else if(block instanceof LecternBlockEntity lectern)result.add(block,0,block.getBlockPos(),lectern.getBook());
        else if(block instanceof CampfireBlockEntity campfire)for(int i=0;i<campfire.getItems().size();i++)result.add(block,i,block.getBlockPos(),campfire.getItems().get(i));
        return result;
    }
    public static Snapshot snapshot(AbstractContainerMenu menu,ServerPlayer player) {
        Snapshot result=new Snapshot(); Set<Physical> visited=new HashSet<>();
        for(Slot slot:menu.slots) {
            Physical physical=physical(slot.container,slot.getContainerSlot());
            Container container=physical.container();
            if(!visited.add(physical))continue;
            BlockPos pos;
            if(container instanceof BlockEntity block)pos=block.getBlockPos();
            else if(container instanceof Entity entity)pos=entity.blockPosition();
            else if(container==player.getEnderChestInventory())pos=OPEN.get(menu);
            else continue; // Exclude player inventory and virtual crafting/result slots.
            if(pos!=null)result.add(container,physical.slot(),pos,container.getItem(physical.slot()));
        }
        return result;
    }
    private static void delta(Map<ItemKey,Integer> changes,StackAt entry,int sign,ServerPlayer player) {
        if(entry==null || entry.stack().isEmpty())return;
        ItemStack stack=entry.stack();
        String data=ItemStack.CODEC.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE),stack.copyWithCount(1)).getOrThrow().toString();
        changes.merge(new ItemKey(entry.pos(),BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),data),sign*stack.getCount(),Integer::sum);
    }
    public static void difference(ServerPlayer player,Cause cause,Snapshot before,Snapshot after) {
        if(!before.readable || !after.readable)return;
        Set<SlotAddress> slots=new HashSet<>(before.slots.keySet()); slots.addAll(after.slots.keySet());
        Map<ItemKey,Integer> changes=new HashMap<>();
        for(SlotAddress slot:slots) {
            StackAt a=before.slots.get(slot), b=after.slots.get(slot);
            if(a!=null && b!=null && a.pos().equals(b.pos()) && a.stack().getCount()==b.stack().getCount() && ItemStack.isSameItemSameComponents(a.stack(),b.stack()))continue;
            delta(changes,a,-1,player); delta(changes,b,1,player);
        }
        changes.forEach((key,delta) -> {
            if(delta!=0)Tracking.log((ServerLevel)player.level(),key.pos(),cause,delta>0?"item-insert":"item-remove",key.item(),Math.abs(delta),key.data());
        });
    }
}
