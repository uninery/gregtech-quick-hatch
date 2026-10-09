package dev.uninery.quickhatch.server;

import dev.uninery.quickhatch.QuickHatch;
import dev.uninery.quickhatch.network.ClientboundActionResultPacket;
import dev.uninery.quickhatch.network.QuickHatchNetwork;
import dev.uninery.quickhatch.network.ServerboundPullItemPacket;
import dev.uninery.quickhatch.platform.AE2Compat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

/**
 * 拉取服务：把界面点到的物品从"玩家身上能找到的地方"取出来送进背包。
 *
 * <p>取物顺序：玩家物品栏 → 背包类容器（暴露 {@link ForgeCapabilities#ITEM_HANDLER}
 * 的物品，如精妙背包，含 Curios 饰品槽里的）→ 无线终端所在 ME 网络（AE2 装了才有）。</p>
 *
 * <p>两种放入行为对应界面上的点击：
 * 普通左键 = 一整组、普通右键 = 1 个，都可以占用快捷栏的选中槽；
 * shift+左键 = 一整组、shift+右键 = 1 个，都只补同类 / 找空位、不动已有物品。</p>
 */
public final class PullService {

    private PullService() {}

    public static void handle(ServerboundPullItemPacket msg, ServerPlayer player) {
        handle(msg, player, player != null && player.isCreative());
    }

    /**
     * @param creative 是否创造模式。单独当参数传是为了让自检能直接跑创造那条分支
     *                 （{@code FakePlayer#isCreative()} 返回什么并不可靠）。
     */
    static void handle(ServerboundPullItemPacket msg, ServerPlayer player, boolean creative) {
        if (player == null) return;
        Item item = BuiltInRegistries.ITEM.get(msg.item());
        if (item == Items.AIR || !msg.item().equals(BuiltInRegistries.ITEM.getKey(item))) {
            fail(player, "message.quickhatch.invalid_item");
            return;
        }
        if (msg.preferHotbar()) {
            handlePullHotbar(msg, item, player, creative);
        } else {
            handlePullNoReplace(msg, item, player, creative);
        }
    }

    // ------------------------------------------------------------------ //
    // 普通左/右键：拉取到快捷栏并把快捷栏切过去
    // （类似原版创造模式中键取物 / EAEP 从 AE 中键拉取）
    // ------------------------------------------------------------------ //

    /**
     * 拉取到快捷栏 —— 逻辑照 EAEP {@code PickFromWirelessC2SPacket} + 原版 {@code Minecraft#pickBlock}：
     *
     * <ol>
     *   <li><b>身上已经有同种物品</b>：切到那一格（快捷栏内直接选，背包里的换到选中格，
     *       与原版 pickBlock 一致），并<b>只补"放得下的数量"</b>—— 多一个都不抽，
     *       抽出来的必定进堆叠（1.0.17 之前的写法抽完不放进堆叠，把物品吞了）。</li>
     *   <li><b>没有同种物品</b>：选中格为空就放选中格，否则放第一个空槽
     *       （EAEP 用的是 {@code Inventory#getFreeSlot()}，即"快捷栏优先"）；
     *       同样只抽放得下的数量。没有任何空位时不抽、直接提示，绝不吞物品。</li>
     * </ol>
     */
    private static void handlePullHotbar(ServerboundPullItemPacket msg, Item item, ServerPlayer player,
                                         boolean creative) {
        Inventory inv = player.getInventory();
        int want = clampCount(msg.stackSize(), item);
        ItemStack probe = new ItemStack(item);

        // 1) 身上已有同类物品 → 切过去 + 补满
        int existing = findSameItemSlot(inv, probe);
        if (existing >= 0) {
            int space = inv.getItem(existing).getMaxStackSize() - inv.getItem(existing).getCount();
            int moved = space > 0 ? obtain(player, item, Math.min(want, space), creative) : 0;
            if (moved > 0) {
                // 注意：抽取过程会 shrink/setItem，早先抓到的 ItemStack 引用可能已经被换掉，
                // 所以这里**重新取一次**再写回，否则抽出来的物品会凭空消失（吞物品 bug）。
                ItemStack slotStack = inv.getItem(existing);
                if (slotStack.isEmpty()) {
                    inv.setItem(existing, new ItemStack(item, moved));
                } else {
                    slotStack.grow(moved);
                }
                inv.setChanged();
            }
            selectSlot(inv, existing);
            QuickHatchNetwork.sendToPlayer(new ClientboundActionResultPacket(true, inv.selected, ""), player);
            return;
        }

        // 2) 没有同类：选中格为空就用选中格，否则第一个空槽
        int target = inv.getItem(inv.selected).isEmpty() ? inv.selected : inv.getFreeSlot();
        if (target < 0) {
            fail(player, "message.quickhatch.inventory_full");
            return;
        }
        ItemStack targetStack = inv.getItem(target);
        int space = targetStack.isEmpty()
                ? probe.getMaxStackSize()
                : targetStack.getMaxStackSize() - targetStack.getCount();
        int extracted = obtain(player, item, Math.min(want, space), creative);
        if (extracted <= 0) {
            fail(player, "message.quickhatch.no_item");
            return;
        }
        inv.setItem(target, new ItemStack(item, extracted));
        if (target < 9) {
            inv.selected = target;
        }
        inv.setChanged();
        QuickHatchNetwork.sendToPlayer(new ClientboundActionResultPacket(true, inv.selected, ""), player);
    }

    /**
     * 拿到最多 {@code amount} 个物品。
     *
     * <p><b>创造模式</b>：直接凭空生成（和原版创造模式对方块中键取物一样，不动网络、不消耗任何东西），
     * 所以"网络里没有这个物品"也能拉出来。生存模式：照 {@link #findAndExtract} 的
     * 物品栏 → 背包 → ME 网络顺序真的去取。</p>
     */
    private static int obtain(ServerPlayer player, Item item, int amount, boolean creative) {
        if (amount <= 0) return 0;
        if (creative) {
            return Math.min(amount, Math.max(1, new ItemStack(item).getMaxStackSize()));
        }
        return findAndExtract(player, item, amount);
    }

    /** 身上第一格同种物品（物品 + NBT 一致）：快捷栏 0..8 优先，再背包其余格；没有返回 -1。 */
    private static int findSameItemSlot(Inventory inv, ItemStack probe) {
        for (int i = 0; i < 9; i++) {
            if (matches(inv.getItem(i), probe)) return i;
        }
        for (int i = 9; i < inv.getContainerSize(); i++) {
            if (matches(inv.getItem(i), probe)) return i;
        }
        return -1;
    }

    private static boolean matches(ItemStack stack, ItemStack probe) {
        return !stack.isEmpty() && ItemStack.isSameItemSameTags(stack, probe);
    }

    /** 照原版 pickBlock 选格：快捷栏内直接选，背包里的换到当前选中格。 */
    private static void selectSlot(Inventory inv, int slot) {
        if (slot < 9) {
            inv.selected = slot;
            return;
        }
        inv.pickSlot(slot);
    }

    /**
     * 把 stack 放进快捷栏（0..8）：先找同类未满的槽位补满，再找空位。
     * 返回放入的槽位（stack 全部放完时），未放完返回 -1。
     */
    private static int insertIntoHotbar(Inventory inv, ItemStack stack) {
        int first = -1;
        for (int i = 0; i < 9 && !stack.isEmpty(); i++) {
            ItemStack existing = inv.getItem(i);
            if (existing.isEmpty()) {
                if (first < 0) first = i;
                continue;
            }
            if (!ItemStack.isSameItemSameTags(existing, stack)) continue;
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) continue;
            int move = Math.min(space, stack.getCount());
            existing.grow(move);
            stack.shrink(move);
            if (first < 0) first = i;
            inv.setChanged();
        }
        if (!stack.isEmpty() && first >= 0) {
            inv.setItem(first, stack.copy());
            stack.setCount(0);
        }
        return stack.isEmpty() ? first : -1;
    }

    // ------------------------------------------------------------------ //
    // Shift 点击：不替换已有物品（补同类/空位），界面不关闭
    // ------------------------------------------------------------------ //

    /**
     * 拉取 {@code msg.stackSize()} 个且不改动已有物品栈：
     * 先补快捷栏/背包里的同类物品栈，再找空位；都不行就掉落。
     */
    private static void handlePullNoReplace(ServerboundPullItemPacket msg, Item item, ServerPlayer player,
                                            boolean creative) {
        int want = clampCount(msg.stackSize(), item);
        int extracted = obtain(player, item, want, creative);
        if (extracted <= 0) {
            fail(player, "message.quickhatch.no_item");
            return;
        }
        Inventory inv = player.getInventory();
        ItemStack stack = new ItemStack(item, extracted);
        boolean placed = false;
        for (int i = 0; i < inv.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack existing = inv.getItem(i);
            if (existing.isEmpty() || !ItemStack.isSameItemSameTags(existing, stack)) continue;
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) continue;
            int move = Math.min(space, stack.getCount());
            existing.grow(move);
            stack.shrink(move);
            placed = true;
        }
        // 空位：优先快捷栏，再背包其余位置
        for (int i = 0; i < inv.getContainerSize() && !stack.isEmpty(); i++) {
            if (!inv.getItem(i).isEmpty()) continue;
            inv.setItem(i, stack.copy());
            stack.setCount(0);
            placed = true;
        }
        if (!stack.isEmpty()) {
            player.drop(stack, false);
        }
        if (placed) inv.setChanged();
        QuickHatchNetwork.sendToPlayer(new ClientboundActionResultPacket(true, -1, ""), player);
    }

    private static int clampCount(int count, Item item) {
        int maxStack = Math.max(1, new ItemStack(item).getMaxStackSize());
        return Math.min(Math.max(1, count), maxStack);
    }

    private static void fail(ServerPlayer player, String message) {
        QuickHatchNetwork.sendToPlayer(new ClientboundActionResultPacket(false, -1, message), player);
    }

    // ------------------------------------------------------------------ //
    // 取物：物品栏 → 背包（ITEM_HANDLER 能力）→ AE2 无线终端
    // ------------------------------------------------------------------ //

    /** 从玩家可用的所有来源提取 count 个 item，返回实际提取数。 */
    public static int findAndExtract(ServerPlayer player, Item item, int count) {
        return findAndExtract(player, item, count, false);
    }

    /**
     * 从玩家可用的所有来源提取 count 个 item，返回实际提取数。
     *
     * @param avoidSelectedSlot 先跳过"当前选中的快捷栏格"，最后才动它。
     *                          替换流程用它：手上那一格不变，就不会触发挥手/换物动画。
     */
    public static int findAndExtract(ServerPlayer player, Item item, int count, boolean avoidSelectedSlot) {
        int need = count;
        Inventory inv = player.getInventory();

        // 1) 玩家物品栏（含副手）；avoidSelectedSlot 时把选中格留到最后
        for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
            if (avoidSelectedSlot && i == inv.selected) continue;
            need -= take(player, inv, i, item, need);
        }
        if (need > 0 && avoidSelectedSlot && inv.selected < inv.getContainerSize()) {
            need -= take(player, inv, inv.selected, item, need);
        }
        QuickHatch.LOGGER.debug("[pull] {} x{}: after inventory need={}",
                BuiltInRegistries.ITEM.getKey(item), count, need);

        // 2) 背包类容器：任何暴露 ITEM_HANDLER 能力的物品（精妙背包、手提袋等）。
        //    精妙背包通常挂在 Curios 饰品槽（back），不在物品栏 36 格里，
        //    所以这里遍历 AE2Compat.forEachContainer（物品栏 + 饰品槽）。
        final int[] remain = {need};
        AE2Compat.forEachContainer(player, container -> {
            if (remain[0] <= 0) return;
            if (container.isEmpty() || container.getCount() != 1) return;
            LazyOptional<IItemHandler> cap = container.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (!cap.isPresent()) return;
            IItemHandler handler = cap.resolve().orElse(null);
            if (handler == null) return;
            for (int s = 0; s < handler.getSlots() && remain[0] > 0; s++) {
                ItemStack inSlot = handler.getStackInSlot(s);
                if (!inSlot.isEmpty() && inSlot.is(item)) {
                    ItemStack extracted = handler.extractItem(s, remain[0], false);
                    remain[0] -= extracted.getCount();
                }
            }
        });
        need = remain[0];
        QuickHatch.LOGGER.debug("[pull] {} x{}: after backpacks need={}",
                BuiltInRegistries.ITEM.getKey(item), count, need);

        // 3) 无线终端所在 ME 网络
        if (need > 0) {
            int got = AE2Compat.extractFromTerminal(player, item, need);
            QuickHatch.LOGGER.debug("[pull] {} x{}: AE2 extracted={}",
                    BuiltInRegistries.ITEM.getKey(item), count, got);
            need -= got;
        }

        return count - need;
    }

    /** 从物品栏某一格取最多 amount 个 item，返回实际取到的数量。 */
    private static int take(ServerPlayer player, Inventory inv, int slot, Item item, int amount) {
        ItemStack stack = inv.getItem(slot);
        if (stack.isEmpty() || !stack.is(item)) return 0;
        int take = Math.min(amount, stack.getCount());
        stack.shrink(take);
        if (stack.isEmpty()) {
            inv.setItem(slot, ItemStack.EMPTY);
        }
        return take;
    }
}
