package dev.uninery.quickhatch.server;

import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import dev.uninery.quickhatch.network.ClientboundActionResultPacket;
import dev.uninery.quickhatch.network.QuickHatchNetwork;
import dev.uninery.quickhatch.network.ServerboundReplaceHatchPacket;
import dev.uninery.quickhatch.platform.MultiblockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 替换服务：界面在"对着可替换方块"打开时，左键点一个仓室 = 原地替换那个方块。
 *
 * <p>判定只有一条：目标位置的方块种类"能在某种多方块结构里被替换为仓室"
 * （{@link MultiblockRegistry} 的全局索引，进存档前就把全部多方块图案扫过了）。
 * 不看结构有没有成形、不做任何多方块探测 —— 方块 id 就是唯一判据。</p>
 *
 * <p>流程：校验方块与距离 → 从"物品栏 → 背包类容器 → 无线终端所在 ME 网络"
 * 扣 1 个仓室 → 原地放下仓室（朝向玩家）→ <b>被破坏的方块直接还给玩家身上</b>。</p>
 */
public final class ReplaceService {

    /** 允许替换的最大距离（平方）：界面开着的时候玩家可能走开几步。 */
    private static final double MAX_DISTANCE_SQR = 8 * 8;

    private ReplaceService() {}

    public static void handle(ServerboundReplaceHatchPacket msg, ServerPlayer player) {
        handle(msg, player, player != null && player.isCreative());
    }

    /**
     * @param creative 是否创造模式。单独当参数传是为了让自检能直接跑创造那条分支
     *                 （{@code FakePlayer#isCreative()} 返回什么并不可靠）。
     */
    static void handle(ServerboundReplaceHatchPacket msg, ServerPlayer player, boolean creative) {
        if (player == null) return;
        Item item = BuiltInRegistries.ITEM.get(msg.item());
        if (item == Items.AIR || !msg.item().equals(BuiltInRegistries.ITEM.getKey(item))) {
            fail(player, "message.quickhatch.invalid_item");
            return;
        }
        if (!(item instanceof MetaMachineItem machineItem)) {
            fail(player, "message.quickhatch.not_hatch");
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = msg.pos();
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DISTANCE_SQR) {
            fail(player, "message.quickhatch.too_far");
            return;
        }
        BlockState oldState = level.getBlockState(pos);
        if (oldState.isAir() || !MultiblockRegistry.isReplaceable(oldState.getBlock())) {
            fail(player, "message.quickhatch.not_replaceable");
            return;
        }
        // 生存模式：扣 1 个仓室（物品栏 → 背包类容器 → 无线终端所在 ME 网络）；
        // 先跳过手上那一格，手上没变就不会出现"换物/挥手"动画。
        // 创造模式：不消耗任何物品（用户要求），没有仓室也能换。
        if (!creative && PullService.findAndExtract(player, item, 1, true) <= 0) {
            fail(player, "message.quickhatch.no_item");
            return;
        }
        // 原地破坏 + 放下仓室（朝向玩家）
        MachineDefinition definition = machineItem.getDefinition();
        BlockState newState = definition.defaultBlockState();
        if (newState.hasProperty(BlockStateProperties.FACING)
                && newState.getValue(BlockStateProperties.FACING) instanceof Direction) {
            newState = newState.setValue(BlockStateProperties.FACING, player.getDirection().getOpposite());
        }
        level.setBlock(pos, newState, Block.UPDATE_ALL);
        // 被破坏的方块直接还给身上（**不碰手上那一格**，见 giveBack）；
        // 创造模式不给（原版创造模式破坏方块也不会掉落）
        ItemStack oldStack = new ItemStack(oldState.getBlock());
        if (!creative && !oldStack.isEmpty()) {
            giveBack(player, oldStack);
        }
        SoundType sound = newState.getSoundType(level, pos, null);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
        QuickHatchNetwork.sendToPlayer(new ClientboundActionResultPacket(true, -1,
                "message.quickhatch.replaced"), player);
    }

    /**
     * 把被破坏的方块还给玩家身上，且<b>不动当前选中的快捷栏格</b>
     * （动它 = 手上物品变化 = 那个"右手消失再伸出来"的换物动画）。
     *
     * <p>顺序：先并进已有的同类堆叠（优先非选中格）→ 再找空槽（跳过选中格）→
     * 实在没地方就丢在玩家脚边（绝不凭空消失）。</p>
     */
    private static void giveBack(ServerPlayer player, ItemStack stack) {
        Inventory inv = player.getInventory();
        // 1) 并进已有的同类堆叠
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                boolean selected = i == inv.selected;
                if (pass == 0 && selected) continue;
                if (pass == 1 && !selected) continue;
                ItemStack existing = inv.getItem(i);
                if (existing.isEmpty() || !ItemStack.isSameItemSameTags(existing, stack)) continue;
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space <= 0) continue;
                int move = Math.min(space, stack.getCount());
                existing.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) {
                    inv.setChanged();
                    return;
                }
            }
        }
        // 2) 空槽（跳过选中格）
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == inv.selected || !inv.getItem(i).isEmpty()) continue;
            inv.setItem(i, stack.copy());
            stack.setCount(0);
            inv.setChanged();
            return;
        }
        // 3) 没地方了：丢在脚边
        player.drop(stack, false);
    }

    private static void fail(ServerPlayer player, String message) {
        QuickHatchNetwork.sendToPlayer(new ClientboundActionResultPacket(false, -1, message), player);
    }
}
