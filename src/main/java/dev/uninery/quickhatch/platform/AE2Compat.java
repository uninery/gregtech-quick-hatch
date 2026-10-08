package dev.uninery.quickhatch.platform;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * AE2 兼容层（通用）。所有 AE2 / Curios 类引用都隔离在内部 {@link Holder} 中，
 * 只有在对应模组已安装时才会被加载，未安装时安全返回 0 / null。
 *
 * <h2>终端访问的正确姿势（照抄 AE2 {@code MenuItemLocator#locate} +
 * ExtendedAE_Plus {@code WirelessTerminalLocator} / {@code CuriosItemLocator}）</h2>
 *
 * <ol>
 *   <li><b>只认真正的无线终端</b>：{@code IMenuItem} 不等于无线终端——AE2 的
 *       {@code NetworkToolItem}、{@code AbstractPortableCell}（便携元件）、
 *       {@code QuartzCuttingKnifeItem} 全都实现 {@code IMenuItem}。
 *       以前"物品栏里第一个 {@code IMenuItem}"就是终端，于是玩家身上只要带着
 *       便携元件，中键下单就会拿到它的宿主（没有网络节点）→ 一律报"未连接/超出距离"。
 *       现在按 {@code WirelessTerminalItem} 判定，并<b>逐个候选试到能用的那个</b>。</li>
 *   <li><b>终端可能不在物品栏</b>：Curios 饰品槽、饰品槽里的精妙背包、
 *       物品栏里的精妙背包……都要能找。AE2 自带的 {@code MenuLocators.forInventorySlot}
 *       只认物品栏槽位，所以这些位置用一个自定义 {@code MenuLocator}
 *       （{@link Holder.TerminalLocator}）让客户端和服务端各自解析回同一个物品栈，
 *       并用 {@link Holder.NestedTerminalHost} 把（可能被扣了电的）物品栈写回原槽位。</li>
 *   <li><b>取物/下单的判据照 AE2 自己</b>：能不能用看
 *       {@code WirelessTerminalItem#getLinkedGrid}（宿主 {@code getInventory()} 非空即已链接）
 *       与 {@code hasPower}；<b>不</b>自己加"信号范围"预检——AE2 的
 *       {@code checkPreconditions} 也不查信号范围，范围是菜单每 tick 自己校验的。</li>
 * </ol>
 */
public final class AE2Compat {

    /** 副手槽在 {@code Inventory} 里的索引。 */
    private static final int OFFHAND_SLOT = 40;

    private AE2Compat() {}

    /** 终端所在的"槽位种类"。 */
    public enum SlotKind {
        /** 玩家物品栏（0..containerSize-1，含快捷栏 / 盔甲 / 副手 40）。 */
        INVENTORY,
        /** Curios 饰品槽（slotId + 组内下标）。 */
        CURIOS,
        /** 物品栏里某个"背包类容器"（暴露 {@code ITEM_HANDLER}）的第 innerSlot 格。 */
        INVENTORY_CONTAINER,
        /** Curios 饰品槽里某个背包类容器的第 innerSlot 格。 */
        CURIOS_CONTAINER;

        public static final SlotKind[] VALUES = values();

        public static SlotKind byOrdinal(int ordinal) {
            return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : INVENTORY;
        }
    }

    /**
     * 终端的定位描述。客户端与服务端都能靠它重新找到同一个物品栈，
     * 因此可以安全地塞进 AE2 的菜单包里（{@link Holder.TerminalLocator}）。
     */
    public record TerminalRef(SlotKind kind, int slot, String curiosSlotId, int curiosIndex, int innerSlot) {

        static TerminalRef inventory(int slot) {
            return new TerminalRef(SlotKind.INVENTORY, slot, "", -1, -1);
        }

        static TerminalRef curios(String slotId, int index) {
            return new TerminalRef(SlotKind.CURIOS, -1, slotId, index, -1);
        }

        /** 由"容器所在槽位"派生出"容器内部第 inner 格"。 */
        TerminalRef into(int inner) {
            return switch (kind) {
                case INVENTORY -> new TerminalRef(SlotKind.INVENTORY_CONTAINER, slot, "", -1, inner);
                case CURIOS -> new TerminalRef(SlotKind.CURIOS_CONTAINER, -1, curiosSlotId, curiosIndex, inner);
                default -> this;
            };
        }
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded("ae2");
    }

    /**
     * 注册兼容层里需要注册的东西（自定义 {@code MenuLocator}）。
     * 客户端与服务端都要在打开任何菜单之前跑一次，否则对面解不开菜单包。
     */
    public static void init() {
        if (!isAvailable()) return;
        Holder.init();
    }

    /** 玩家身上有没有能用的终端（无线终端优先，便携元件兜底）。 */
    public static boolean hasTerminal(ServerPlayer player) {
        if (!isAvailable() || player == null) return false;
        return Holder.findBest(player, true) != null;
    }

    /**
     * 从无线终端所在 ME 网络提取 item。
     *
     * @return 实际提取数量（终端未找到 / 未链接 / 没电 / 电网失败时为 0）
     */
    public static int extractFromTerminal(ServerPlayer player, Item item, int amount) {
        if (!isAvailable() || amount <= 0) return 0;
        return Holder.extractFromTerminal(player, item, amount);
    }

    /**
     * 遍历玩家身上所有可能装物品的容器（物品栏 + Curios 饰品槽里的背包等）。
     *
     * <p>精妙背包这类容器通常放在 Curios 的 back 槽里，不在物品栏的 36 格里，
     * 所以取物/统计必须把饰品槽也算上。</p>
     *
     * <p><b>注意</b>：这个方法<b>不能</b>放进 {@code Holder}——{@code Holder} 里引用了
     * AE2 的类，JVM 在解析 {@code Holder.forEachContainer} 这个引用时会连带加载
     * {@code Holder}，AE2 未安装时直接 {@code NoClassDefFoundError}。
     * 所以这里只用 Curios 的 API，连 Curios 的类都不在方法签名里出现。</p>
     *
     * @param sink 每个非空容器物品栈都会回调一次
     */
    public static void forEachContainer(ServerPlayer player, Consumer<ItemStack> sink) {
        if (player == null || sink == null) return;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) sink.accept(stack);
        }
        if (!ModList.get().isLoaded("curios")) return;
        try {
            var resolved = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (resolved.isEmpty()) return;
            top.theillusivec4.curios.api.type.capability.ICuriosItemHandler handler = resolved.get();
            for (var entry : handler.getCurios().entrySet()) {
                top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler stacks =
                        entry.getValue().getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack st = stacks.getStackInSlot(i);
                    if (!st.isEmpty()) sink.accept(st);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 收集 AE2 的部件物品（线缆、面板、元件总线、P2P 隧道等）。
     *
     * @param sink 参数为 (item, AE 子分类)
     */
    public static void collectPartItems(BiConsumer<Item, HatchIndex.AeSubtype> sink) {
        if (!isAvailable()) return;
        Holder.collectPartItems(sink);
    }

    /**
     * AE 部件子分类：<b>只有线缆与方块两档</b>（用户要求：面板/显示器/终端一律归"方块"）。
     * 线缆按注册 id 认（{@code *_cable} 与石英纤维），其余全是方块。
     *
     * <p>判定只看 id，不引用 AE2 的类——本方法在 AE2 未安装时也会被调用。</p>
     */
    public static HatchIndex.AeSubtype partSubtype(ResourceLocation id, Item item) {
        return HatchIndex.aePartSubtype(id);
    }

    /**
     * 这个方块是不是"能连上 ME 网络"的 AE 设备（含所有附属模组，任意命名空间）。
     *
     * <p>判据是 AE2 自己的公开 API：方块是 {@code AEBaseEntityBlock}（会挂方块实体），
     * 并且它的方块实体实现 {@code appeng.api.networking.IInWorldGridNodeHost}
     * （能在世界里主持一个网格节点）。建筑/装饰方块没有网格节点，天然被排除。</p>
     */
    public static boolean isGridDeviceBlock(net.minecraft.world.level.block.Block block) {
        if (!isAvailable() || block == null) return false;
        return Holder.isGridDeviceBlock(block);
    }

    /**
     * 统计无线终端所在 ME 网络中索引物品的数量，累加进 out。
     */
    public static void addNetworkCounts(ServerPlayer player, Map<ResourceLocation, Long> out,
                                        Set<ResourceLocation> indexed) {
        if (!isAvailable()) return;
        Holder.addNetworkCounts(player, out, indexed);
    }

    /**
     * ME 网络里<b>可合成</b>（有样板）的索引物品 id，用于界面给可合成物品画"+"角标
     * （AE 原版终端 {@code MEStorageScreen} 的做法）。
     */
    public static Set<ResourceLocation> craftableIndexedItems(ServerPlayer player,
                                                              Set<ResourceLocation> indexed) {
        if (!isAvailable()) return Set.of();
        return Holder.craftableIndexedItems(player, indexed);
    }

    /**
     * 中键合成：打开 AE2 原生的下单界面（{@code CraftAmountMenu}）。
     *
     * <p>完全照参考实现的链路走：AE2 原生终端中键（{@code MEStorageMenu} 的
     * {@code AUTO_CRAFT} 分支：{@code CraftAmountMenu.open(player, getLocator(), key,
     * key.getAmountPerUnit())}）与 ExtendedAE_Plus 的
     * {@code OpenCraftFromJeiC2SPacket}（定位终端 → {@code hasPower} + {@code getLinkedGrid}
     * 拿网格 → {@code craftingService.isCraftable} → {@code CraftAmountMenu.open(..., locator, key, 1)}）。
     * 初始数量用 {@code AEItemKey#getAmountPerUnit()}（物品就是 1，和上面两边一致）。</p>
     *
     * @return opened / no_terminal / no_range / not_craftable
     */
    public static String openCraftAmountMenu(ServerPlayer player, Item item) {
        if (!isAvailable()) return "no_terminal";
        return Holder.openCraftAmountMenu(player, item);
    }

    /**
     * 自检钩子：找到会被选中的终端，生成它的 locator，再用 locator <b>反向解析一次宿主</b>
     * —— 这正是 AE2 在客户端与服务端打开菜单时要走的那一步，
     * 解析不出来菜单一开就报"未连接"。
     *
     * @return locator 能解析出宿主 = true
     */
    public static boolean selfTestResolveHost(ServerPlayer player) {
        if (!isAvailable() || player == null) return false;
        return Holder.selfTestResolveHost(player);
    }

    /**
     * 包内给 {@link WTLibCompat} 用：按 {@link TerminalRef} 读回终端物品栈。
     * （wtlib 的宿主也挂在同一个"槽位"上，读写都得走同一套。）
     */
    static ItemStack terminalStackAt(Player player, TerminalRef ref) {
        return Holder.stackOf(player, ref);
    }

    /** 包内给 {@link WTLibCompat} 用：把（扣过电的）终端物品栈写回原槽位。 */
    static void writeTerminalStack(Player player, TerminalRef ref, ItemStack stack) {
        Holder.writeBack(player, ref, stack);
    }

    /**
     * AE2 菜单的"返回主菜单"：本模组的终端界面是客户端自己的小窗（不是 AE2 菜单），
     * 所以下单流程（{@code CraftAmountMenu} → {@code CraftConfirmMenu}）走完时直接关掉界面，
     * 否则确认界面会一直停在屏幕上（用户报的"下单后下单界面没有自动关闭"）。
     */
    static void closeCraftMenu(Player player) {
        try {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 自检钩子：当前会被选中的终端宿主的类名（没有可用终端时返回空串）。
     * 用来断言"饰品槽里的 wtlib 终端确实走了 wtlib 的宿主"。
     */
    public static String selfTestHostClass(ServerPlayer player) {
        if (!isAvailable() || player == null) return "";
        return Holder.selfTestHostClass(player);
    }

    /**
     * 自检钩子：locator 的"写包 → 读包 → 解析宿主"往返（客户端收到菜单包时的那一步）。
     *
     * @return 读回来的 locator 能解析出宿主 = true
     */
    public static boolean selfTestLocatorRoundTrip(ServerPlayer player) {
        if (!isAvailable() || player == null) return false;
        return Holder.selfTestLocatorRoundTrip(player);
    }

    /**
     * 自检钩子：wtlib 找终端物品栈时走的那一步
     * （{@code locator.locate(player, WTMenuHost.class)}）能不能解析出宿主。
     */
    public static boolean selfTestWTLibHostLookup(ServerPlayer player) {
        if (!isAvailable() || player == null) return true;
        return Holder.selfTestWTLibHostLookup(player);
    }

    /**
     * 自检钩子：不管能不能合成，直接把 AE2 的下单界面开出来（用来验证
     * "返回主菜单 = 关掉界面"这条链路）。
     *
     * @return 界面确实开出来了 = true
     */
    public static boolean selfTestOpenCraftAmountMenu(ServerPlayer player, Item item) {
        if (!isAvailable() || player == null || item == null) return false;
        return Holder.selfTestOpenCraftAmountMenu(player, item);
    }

    /** AE2 / Curios / ae2wtlib 类只在本类中引用。 */
    private static final class Holder {

        /** 找到的终端：物品栈 + 定位描述 + 是不是真无线终端（而不是便携元件）。 */
        private record Found(ItemStack stack, TerminalRef ref, boolean wireless) {}

        private static boolean registered;

        private Holder() {}

        /** 注册自定义 {@code MenuLocator}（客户端 / 服务端各注册一次）。 */
        static synchronized void init() {
            if (registered) return;
            registered = true;
            appeng.menu.locator.MenuLocators.register(TerminalLocator.class,
                    TerminalLocator::writeToPacket, TerminalLocator::readFromPacket);
        }

        // ------------------------------------------------------------------ //
        // 定位：物品栏 / Curios / 背包类容器，以及"哪一个是能用的终端"
        // ------------------------------------------------------------------ //

        /**
         * 按优先级找一个能用的终端：<b>真无线终端优先</b>，
         * {@code allowPortableCell} 为真时允许退回便携元件（只能取物 / 统计，不能下单）。
         */
        static Found findBest(Player player, boolean allowPortableCell) {
            if (player == null) return null;
            Found wireless = firstUsable(player, true);
            if (wireless != null) return wireless;
            return allowPortableCell ? firstUsable(player, false) : null;
        }

        /**
         * 逐个候选试到"建得出宿主、宿主有 ME 存储"的那个。
         *
         * <p>这一步是修掉"随便抓一个 {@code IMenuItem} 就当终端"的关键：
         * 便携元件、网络工具、石英刀都能建出宿主，但它们没有 ME 网络，
         * 以前会顶掉真正的无线终端，让中键下单永远报"未连接 / 超出距离"。</p>
         */
        private static Found firstUsable(Player player, boolean wirelessOnly) {
            for (Found found : findAll(player)) {
                if (wirelessOnly && !found.wireless()) continue;
                if (!wirelessOnly && found.wireless()) continue;
                // 真无线终端：照 EAEP isWirelessTerminal 判"是不是终端"，
                // 建不出宿主 / 没有 ME 存储的都跳过（便携元件、网络工具不是终端）
                if (found.wireless()) {
                    ItemStack stack = found.stack();
                    if (ModList.get().isLoaded("ae2wtlib") && WTLibCompat.isWTLibTerminal(stack)
                            && !WTLibCompat.isUsableTerminal(stack)) {
                        continue;
                    }
                }
                appeng.menu.locator.MenuLocator locator = locatorOf(player, found);
                appeng.api.implementations.menuobjects.ItemMenuHost host =
                        hostOf(player, found, locator, appeng.api.implementations.menuobjects.IPortableTerminal.class);
                if (!(host instanceof appeng.api.implementations.menuobjects.IPortableTerminal portable)) continue;
                if (portable.getInventory() == null) continue;
                return found;
            }
            return null;
        }

        /** 玩家身上所有"可能是终端"的物品，顺序照 EAEP：主手 → 副手 → 其余物品栏 → Curios → 容器一层。 */
        private static List<Found> findAll(Player player) {
            List<Found> out = new ArrayList<>();
            List<TerminalRef> containers = new ArrayList<>();
            var inv = player.getInventory();
            // 1) 主手 / 副手优先（EAEP WirelessTerminalLocator.find 就是这个顺序）
            collect(inv.getItem(inv.selected), TerminalRef.inventory(inv.selected), out, containers);
            collect(inv.getItem(OFFHAND_SLOT), TerminalRef.inventory(OFFHAND_SLOT), out, containers);
            // 2) 其余物品栏槽位（含盔甲）
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (i == inv.selected || i == OFFHAND_SLOT) continue;
                collect(inv.getItem(i), TerminalRef.inventory(i), out, containers);
            }
            // 3) Curios 饰品槽
            for (CuriosSlot slot : curiosSlots(player)) {
                collect(slot.stack(), TerminalRef.curios(slot.slotId(), slot.index()), out, containers);
            }
            // 4) 上面那些槽位里"背包类容器"内部的一层
            for (TerminalRef containerRef : containers) {
                ItemStack container = stackOf(player, containerRef);
                IItemHandler handler = itemHandler(container);
                if (handler == null) continue;
                for (int s = 0; s < handler.getSlots(); s++) {
                    ItemStack in = handler.getStackInSlot(s);
                    if (isTerminalStack(in)) {
                        out.add(new Found(in, containerRef.into(s), isWirelessTerminal(in)));
                    }
                }
            }
            return out;
        }

        private static void collect(ItemStack stack, TerminalRef ref,
                                    List<Found> terminals, List<TerminalRef> containers) {
            if (stack.isEmpty()) return;
            if (isTerminalStack(stack)) {
                terminals.add(new Found(stack, ref, isWirelessTerminal(stack)));
            }
            if (itemHandler(stack) != null) {
                containers.add(ref);
            }
        }

        private static boolean isTerminalStack(ItemStack stack) {
            return !stack.isEmpty()
                    && stack.getItem() instanceof appeng.api.implementations.menuobjects.IMenuItem;
        }

        private static boolean isWirelessTerminal(ItemStack stack) {
            return !stack.isEmpty()
                    && stack.getItem() instanceof appeng.items.tools.powered.WirelessTerminalItem;
        }

        /** 这个终端归 wtlib 管吗（wtlib 登记过的终端，且当前子终端可用）。 */
        private static boolean wtlibUsable(ItemStack stack) {
            return ModList.get().isLoaded("ae2wtlib") && WTLibCompat.isUsableTerminal(stack);
        }

        /**
         * 照 EAEP {@code WirelessTerminalLocator.getConnectedGrid} 取终端连着的网络：
         * <ul>
         *   <li>wtlib 的终端 → 交给 wtlib（{@code WTMenuHost#rangeCheck()} 里面含量子桥判定）；</li>
         *   <li>其余 AE2 终端 → {@code hasPower} + {@code getLinkedGrid}。</li>
         * </ul>
         * 返回 null = 这个终端现在连不上网（没电 / 没链接 / 超出范围）。EAEP 在这种情况
         * 直接静默返回，我们保留一句提示给玩家。
         */
        static appeng.api.networking.IGrid connectedGrid(Player player, Found found,
                                                         appeng.menu.locator.MenuLocator locator) {
            ItemStack stack = found.stack();
            if (ModList.get().isLoaded("ae2wtlib") && WTLibCompat.isUsableTerminal(stack)) {
                return WTLibCompat.getConnectedGrid(player, stack, found.ref(), locator);
            }
            if (!(stack.getItem() instanceof appeng.items.tools.powered.WirelessTerminalItem terminal)) {
                return null;
            }
            if (!terminal.hasPower(player, 0.5, stack)) {
                return null;
            }
            return terminal.getLinkedGrid(stack, player.level(), null);
        }

        // ------------------------------------------------------------------ //
        // 槽位读写（客户端与服务端共用）
        // ------------------------------------------------------------------ //

        /** 按 {@link TerminalRef} 重新找到终端物品栈；找不到返回空。 */
        static ItemStack stackOf(Player player, TerminalRef ref) {
            if (player == null || ref == null) return ItemStack.EMPTY;
            return switch (ref.kind()) {
                case INVENTORY -> {
                    var inv = player.getInventory();
                    yield ref.slot() >= 0 && ref.slot() < inv.getContainerSize()
                            ? inv.getItem(ref.slot()) : ItemStack.EMPTY;
                }
                case CURIOS -> {
                    var stacks = curiosHandler(player, ref.curiosSlotId());
                    yield stacks != null && ref.curiosIndex() >= 0 && ref.curiosIndex() < stacks.getSlots()
                            ? stacks.getStackInSlot(ref.curiosIndex()) : ItemStack.EMPTY;
                }
                case INVENTORY_CONTAINER -> inner(
                        stackOf(player, TerminalRef.inventory(ref.slot())), ref.innerSlot());
                case CURIOS_CONTAINER -> inner(
                        stackOf(player, TerminalRef.curios(ref.curiosSlotId(), ref.curiosIndex())),
                        ref.innerSlot());
            };
        }

        /** 把（可能被扣了电、改了 NBT 的）终端物品栈写回它原来的槽位。 */
        static void writeBack(Player player, TerminalRef ref, ItemStack stack) {
            if (player == null || ref == null || stack == null) return;
            try {
                switch (ref.kind()) {
                    case INVENTORY -> player.getInventory().setItem(ref.slot(), stack);
                    case CURIOS -> {
                        var stacks = curiosHandler(player, ref.curiosSlotId());
                        if (stacks != null) stacks.setStackInSlot(ref.curiosIndex(), stack);
                    }
                    case INVENTORY_CONTAINER, CURIOS_CONTAINER -> {
                        TerminalRef containerRef = ref.kind() == SlotKind.INVENTORY_CONTAINER
                                ? TerminalRef.inventory(ref.slot())
                                : TerminalRef.curios(ref.curiosSlotId(), ref.curiosIndex());
                        replaceInContainer(stackOf(player, containerRef), ref.innerSlot(), stack);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private static ItemStack inner(ItemStack container, int innerSlot) {
            IItemHandler handler = itemHandler(container);
            if (handler == null || innerSlot < 0 || innerSlot >= handler.getSlots()) return ItemStack.EMPTY;
            return handler.getStackInSlot(innerSlot);
        }

        /** 容器里"替换一格"：先取出旧的，再放进新的（容器不提供 setStackInSlot）。 */
        private static void replaceInContainer(ItemStack container, int innerSlot, ItemStack stack) {
            IItemHandler handler = itemHandler(container);
            if (handler == null || innerSlot < 0 || innerSlot >= handler.getSlots()) return;
            ItemStack current = handler.getStackInSlot(innerSlot);
            if (!ItemStack.isSameItem(current, stack)) return;
            handler.extractItem(innerSlot, current.getCount(), false);
            handler.insertItem(innerSlot, stack, false);
        }

        private static IItemHandler itemHandler(ItemStack stack) {
            if (stack == null || stack.isEmpty() || stack.getCount() != 1) return null;
            try {
                var cap = stack.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER);
                if (!cap.isPresent()) return null;
                IItemHandler handler = cap.resolve().orElse(null);
                return handler != null && handler.getSlots() > 0 ? handler : null;
            } catch (Throwable ex) {
                return null;
            }
        }

        private record CuriosSlot(String slotId, int index, ItemStack stack) {}

        private static List<CuriosSlot> curiosSlots(Player player) {
            List<CuriosSlot> out = new ArrayList<>();
            if (player == null || !ModList.get().isLoaded("curios")) return out;
            try {
                var resolved = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
                if (resolved.isEmpty()) return out;
                for (var entry : resolved.get().getCurios().entrySet()) {
                    var stacks = entry.getValue().getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        ItemStack st = stacks.getStackInSlot(i);
                        if (!st.isEmpty()) out.add(new CuriosSlot(entry.getKey(), i, st));
                    }
                }
            } catch (Throwable ignored) {
            }
            return out;
        }

        private static top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler curiosHandler(
                Player player, String slotId) {
            if (player == null || slotId == null || slotId.isEmpty() || !ModList.get().isLoaded("curios")) {
                return null;
            }
            try {
                var resolved = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
                if (resolved.isEmpty()) return null;
                var stacksHandler = resolved.get().getCurios().get(slotId);
                return stacksHandler == null ? null : stacksHandler.getStacks();
            } catch (Throwable ignored) {
                return null;
            }
        }

        // ------------------------------------------------------------------ //
        // 宿主与 MenuLocator
        // ------------------------------------------------------------------ //

        /**
         * 构造终端宿主（形状照 EAEP：{@code CuriosItemLocator#locate} + {@code AE2WTLibCompat.locateMenuHost}）。
         *
         * <ul>
         *   <li><b>wtlib 管的终端</b> → {@link WTLibCompat#createHost}：物品栏里用它自己声明的
         *       宿主工厂，饰品槽 / 背包里用 wtlib 的 {@code WTMenuHost} 子类
         *       （量子桥判定、充能都在 wtlib 里）；</li>
         *   <li><b>饰品槽 / 背包里的 AE2 终端</b> → 照 EAEP 的
         *       {@code CuriosWirelessTerminalMenuHost} / {@code CuriosWirelessCraftingTerminalMenuHost}；</li>
         *   <li><b>物品栏里的 AE2 终端</b> → AE2 自己的宿主类（无线合成终端用它的专用宿主），
         *       但 {@code returnToMainMenu} 换成"关掉界面"；</li>
         *   <li>其余 {@code IMenuItem}（便携元件 / 网络工具等）→ 物品自己的 {@code getMenuHost}。</li>
         * </ul>
         *
         * @param hostInterface 请求方要的宿主接口（AE2 的 {@code MenuTypeBuilder} 会传
         *                      {@code ISubMenuHost.class}，wtlib 找宿主时会传 {@code WTMenuHost.class}）
         */
        static appeng.api.implementations.menuobjects.ItemMenuHost hostOf(
                Player player, Found found, appeng.menu.locator.MenuLocator locator, Class<?> hostInterface) {
            ItemStack stack = found.stack();
            if (stack.isEmpty()
                    || !(stack.getItem() instanceof appeng.api.implementations.menuobjects.IMenuItem menuItem)) {
                return null;
            }
            try {
                boolean nested = found.ref().kind() != SlotKind.INVENTORY;
                // wtlib 管的终端：一律交给 wtlib 的宿主（量子桥）
                if (wtlibUsable(stack)) {
                    return WTLibCompat.createHost(player, stack, found.ref(), locator, hostInterface);
                }
                if (!isWirelessTerminal(stack)) {
                    // 便携元件 / 网络工具等：物品自己的通用路径（饰品槽里没有物品栏槽位可言）
                    return nested ? null : menuItem.getMenuHost(player, found.ref().slot(), stack, null);
                }
                if (nested) {
                    // 照 EAEP：合成终端在饰品槽 / 背包里保留它的专用宿主类型
                    if (stack.getItem() instanceof appeng.items.tools.powered.WirelessCraftingTerminalItem
                            && hostInterface != appeng.api.storage.ISubMenuHost.class) {
                        return new NestedCraftingTerminalHost(player, stack, found.ref(), locator);
                    }
                    return new NestedTerminalHost(player, stack, found.ref(), locator);
                }
                // 物品栏里的 AE2 终端：用 AE2 自己的宿主类，只把"返回主菜单"改成关界面
                if (stack.getItem() instanceof appeng.items.tools.powered.WirelessCraftingTerminalItem) {
                    return new appeng.helpers.WirelessCraftingTerminalMenuHost(
                            player, found.ref().slot(), stack, closeCallback());
                }
                return new appeng.helpers.WirelessTerminalMenuHost(
                        player, found.ref().slot(), stack, closeCallback());
            } catch (Throwable ex) {
                return null;
            }
        }

        /**
         * "返回主菜单"的回调。
         *
         * <p>本模组的终端界面是客户端自己的小窗（不是 AE2 菜单），所以 AE2 的下单流程
         * （{@code CraftAmountMenu} → {@code CraftConfirmMenu} → 开始合成 / 返回）走完时，
         * 没有什么"终端界面"可以回去，直接<b>关掉界面</b>即可 ——
         * 否则 {@code CraftConfirmMenu} 会一直停在屏幕上。</p>
         */
        private static java.util.function.BiConsumer<Player, appeng.menu.ISubMenu> closeCallback() {
            return (player, subMenu) -> AE2Compat.closeCraftMenu(player);
        }

        /**
         * 该终端的 locator —— 顺序与形式照 EAEP {@code WirelessTerminalLocator.LocatedTerminal#createMenuLocator}：
         * 手 → {@code forHand}，物品栏 → {@code forInventorySlot}，饰品槽 / 背包 → 我们自己的
         * {@link TerminalLocator}（AE2 的 {@code MenuItemLocator} 只认物品栏槽位）。
         */
        static appeng.menu.locator.MenuLocator locatorOf(Player player, Found found) {
            if (found.ref().kind() == SlotKind.INVENTORY) {
                int slot = found.ref().slot();
                if (slot == player.getInventory().selected) {
                    return appeng.menu.locator.MenuLocators.forHand(player,
                            net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                if (slot == OFFHAND_SLOT) {
                    return appeng.menu.locator.MenuLocators.forHand(player,
                            net.minecraft.world.InteractionHand.OFF_HAND);
                }
                return appeng.menu.locator.MenuLocators.forInventorySlot(slot);
            }
            init();
            return new TerminalLocator(found.ref());
        }

        /**
         * Curios / 背包类容器里终端的宿主 —— 对应 EAEP 的
         * {@code CuriosWirelessTerminalMenuHost}（做法一模一样：建 {@code WirelessTerminalMenuHost}
         * 的匿名槽位版本，然后在 {@code onBroadcastChanges} 里把物品栈写回原槽位，
         * 好让扣掉的电与 NBT 变更真的落到饰品槽 / 背包里）。
         */
        static class NestedTerminalHost extends appeng.helpers.WirelessTerminalMenuHost {

            final TerminalRef ref;

            NestedTerminalHost(Player player, ItemStack stack, TerminalRef ref,
                               appeng.menu.locator.MenuLocator locator) {
                super(player, null, stack, closeCallback());
                this.ref = ref;
            }

            @Override
            public boolean onBroadcastChanges(net.minecraft.world.inventory.AbstractContainerMenu menu) {
                writeBack(getPlayer(), ref, getItemStack());
                return super.onBroadcastChanges(menu);
            }
        }

        /** 合成终端在饰品槽 / 背包里的宿主，对应 EAEP {@code CuriosWirelessCraftingTerminalMenuHost}。 */
        static final class NestedCraftingTerminalHost extends appeng.helpers.WirelessCraftingTerminalMenuHost {

            private final TerminalRef ref;

            NestedCraftingTerminalHost(Player player, ItemStack stack, TerminalRef ref,
                                       appeng.menu.locator.MenuLocator locator) {
                super(player, null, stack, closeCallback());
                this.ref = ref;
            }

            @Override
            public boolean onBroadcastChanges(net.minecraft.world.inventory.AbstractContainerMenu menu) {
                writeBack(getPlayer(), ref, getItemStack());
                return super.onBroadcastChanges(menu);
            }
        }

        /**
         * Curios / 容器里终端的 {@code MenuLocator}：两端都靠 {@link TerminalRef}
         * 重新解析出同一个物品栈，于是 AE2 的菜单包在客户端也能建成宿主。
         */
        record TerminalLocator(TerminalRef ref) implements appeng.menu.locator.MenuLocator {

            @Override
            public <T> T locate(Player player, Class<T> hostInterface) {
                TerminalRef target = ref;
                ItemStack stack = stackOf(player, target);
                if (stack.isEmpty()
                        || !(stack.getItem() instanceof appeng.api.implementations.menuobjects.IMenuItem)) {
                    return null;
                }
                appeng.api.implementations.menuobjects.ItemMenuHost host =
                        hostOf(player, new Found(stack, target, isWirelessTerminal(stack)), this, hostInterface);
                return host != null && hostInterface.isInstance(host) ? hostInterface.cast(host) : null;
            }

            void writeToPacket(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeVarInt(ref.kind().ordinal());
                buf.writeVarInt(ref.slot() + 1);
                buf.writeUtf(ref.curiosSlotId() == null ? "" : ref.curiosSlotId());
                buf.writeVarInt(ref.curiosIndex() + 1);
                buf.writeVarInt(ref.innerSlot() + 1);
            }

            static TerminalLocator readFromPacket(net.minecraft.network.FriendlyByteBuf buf) {
                SlotKind kind = SlotKind.byOrdinal(buf.readVarInt());
                int slot = buf.readVarInt() - 1;
                String slotId = buf.readUtf();
                int curiosIndex = buf.readVarInt() - 1;
                int innerSlot = buf.readVarInt() - 1;
                return new TerminalLocator(new TerminalRef(kind, slot, slotId, curiosIndex, innerSlot));
            }
        }

        /** 找到终端并构造出可访问 ME 存储的便携终端；失败返回 null。 */
        static appeng.api.implementations.menuobjects.IPortableTerminal openPortable(
                Player player, boolean allowPortableCell) {
            Found found = findBest(player, allowPortableCell);
            if (found == null) return null;
            appeng.api.implementations.menuobjects.ItemMenuHost host = hostOf(player, found,
                    locatorOf(player, found), appeng.api.implementations.menuobjects.IPortableTerminal.class);
            if (host instanceof appeng.api.implementations.menuobjects.IPortableTerminal portable
                    && portable.getInventory() != null) {
                return portable;
            }
            return null;
        }

        static appeng.api.networking.IGrid gridOf(appeng.api.implementations.menuobjects.ItemMenuHost host) {
            if (host instanceof appeng.api.networking.security.IActionHost iah && iah.getActionableNode() != null) {
                return iah.getActionableNode().getGrid();
            }
            return null;
        }

        static int extractFromTerminal(ServerPlayer player, Item item, int amount) {
            appeng.api.implementations.menuobjects.IPortableTerminal portable = openPortable(player, true);
            if (portable == null) return 0;
            appeng.api.storage.MEStorage storage = portable.getInventory();
            appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(new ItemStack(item));
            if (key == null) return 0;
            // poweredExtraction：模拟提取 → 扣能 → 真实提取，与原版终端取物一致
            long extracted = appeng.api.storage.StorageHelper.poweredExtraction(
                    portable, storage, key, amount, source(player, portable));
            return (int) Math.min(extracted, amount);
        }

        private static appeng.api.networking.security.IActionSource source(
                ServerPlayer player, appeng.api.implementations.menuobjects.IPortableTerminal portable) {
            return appeng.api.networking.security.IActionSource.ofPlayer(
                    player, portable instanceof appeng.api.networking.security.IActionHost iah ? iah : null);
        }

        // ------------------------------------------------------------------ //
        // 数量统计 / 可合成统计
        // ------------------------------------------------------------------ //

        static void collectPartItems(BiConsumer<Item, HatchIndex.AeSubtype> sink) {
            for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                if (item instanceof appeng.api.parts.IPartItem<?>) {
                    ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
                    // 子分类只有"线缆 / 方块"两档，纯 id 判定（见 HatchIndex#aePartSubtype）
                    sink.accept(item, HatchIndex.aePartSubtype(id));
                }
            }
        }

        // ------------------------------------------------------------------ //
        // AE 设备方块判定（子分类只有"线缆 / 方块"，方块这一档就是全部非线缆物品）
        // ------------------------------------------------------------------ //

        /** {@code AEBaseEntityBlock#blockEntityClass}：没有公开 getter，只能反射读。 */
        private static final java.lang.reflect.Field BLOCK_ENTITY_CLASS = findBlockEntityClassField();

        private static java.lang.reflect.Field findBlockEntityClassField() {
            try {
                var field = appeng.block.AEBaseEntityBlock.class.getDeclaredField("blockEntityClass");
                field.setAccessible(true);
                return field;
            } catch (Throwable ex) {
                dev.uninery.quickhatch.QuickHatch.LOGGER.warn(
                        "[quickhatch] cannot read AEBaseEntityBlock#blockEntityClass, "
                                + "AE device blocks fall back to the built-in id list", ex);
                return null;
            }
        }

        /**
         * "能连上 ME 网络的方块" = 方块实现在世界里主持网格节点
         * （{@code IInWorldGridNodeHost}，AE2 公开 API，所有附属模组的设备都实现它）。
         */
        static boolean isGridDeviceBlock(net.minecraft.world.level.block.Block block) {
            // 线缆/总线的载体方块（cable_bus）单独放置没有意义，不进列表
            if (block instanceof appeng.block.networking.CableBusBlock) return false;
            Class<?> beClass = blockEntityClassOf(block);
            return beClass != null
                    && appeng.api.networking.IInWorldGridNodeHost.class.isAssignableFrom(beClass);
        }

        private static Class<?> blockEntityClassOf(net.minecraft.world.level.block.Block block) {
            if (BLOCK_ENTITY_CLASS == null
                    || !(block instanceof appeng.block.AEBaseEntityBlock<?> entityBlock)) {
                return null;
            }
            try {
                Object value = BLOCK_ENTITY_CLASS.get(entityBlock);
                return value instanceof Class<?> cls ? cls : null;
            } catch (Throwable ex) {
                return null;
            }
        }

        static void addNetworkCounts(ServerPlayer player, Map<ResourceLocation, Long> out,
                                     Set<ResourceLocation> indexed) {
            appeng.api.implementations.menuobjects.IPortableTerminal portable = openPortable(player, true);
            if (portable == null) return;
            appeng.api.stacks.KeyCounter available = portable.getInventory().getAvailableStacks();
            for (var entry : available) {
                appeng.api.stacks.AEKey key = entry.getKey();
                if (key instanceof appeng.api.stacks.AEItemKey itemKey) {
                    ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(itemKey.getItem());
                    if (indexed.contains(id)) {
                        out.merge(id, entry.getLongValue(), Long::sum);
                    }
                }
            }
        }

        /** ME 网络里可合成（有样板）的索引物品 id（AE 原版终端给可合成物品画"+"）。 */
        static Set<ResourceLocation> craftableIndexedItems(ServerPlayer player,
                                                           Set<ResourceLocation> indexed) {
            Set<ResourceLocation> out = new java.util.LinkedHashSet<>();
            Found found = findBest(player, true);
            if (found == null) return out;
            appeng.api.networking.IGrid grid = connectedGrid(player, found, locatorOf(player, found));
            if (grid == null) return out;
            appeng.api.networking.crafting.ICraftingService crafting = grid.getCraftingService();
            for (ResourceLocation id : indexed) {
                Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
                appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(new ItemStack(item));
                if (key != null && crafting.isCraftable(key)) {
                    out.add(id);
                }
            }
            return out;
        }

        // ------------------------------------------------------------------ //
        // 中键合成：打开 AE2 原生的下单界面 CraftAmountMenu
        //
        // 照 AE2 WirelessTerminalItem#openFromInventory / ExtendedAE_Plus
        // OpenCraftFromJeiC2SPacket：
        //   1) 定位一个真的无线终端（逐个候选试，不是"第一个 IMenuItem"）
        //   2) 用它的宿主拿 grid（getActionableNode 内部会做信号范围检查）
        //   3) CraftAmountMenu.open(player, locator, key, n)
        //      locator 必须两端都能解析回同一个终端，否则菜单一开就报"未连接"。
        // ------------------------------------------------------------------ //

        static String openCraftAmountMenu(ServerPlayer player, Item item) {
            Found found = findBest(player, false);
            if (found == null) return "no_terminal";
            appeng.menu.locator.MenuLocator locator = locatorOf(player, found);
            // 照 EAEP：先按各自的 API 确认终端连着的网络（wtlib 走量子桥判定）
            appeng.api.networking.IGrid grid = connectedGrid(player, found, locator);
            if (grid == null) return "no_range";
            appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(new ItemStack(item));
            if (key == null || !grid.getCraftingService().isCraftable(key)) return "not_craftable";
            // 初始数量照 AE2 AUTO_CRAFT / EAEP：key.getAmountPerUnit()（物品 = 1）
            appeng.menu.me.crafting.CraftAmountMenu.open(player, locator, key, key.getAmountPerUnit());
            return "opened";
        }

        /** 自检：拿 locator 反向解析一次宿主（AE2 在两端打开菜单时做的就是这一步）。 */
        static boolean selfTestResolveHost(Player player) {
            Found found = findBest(player, false);
            if (found == null) return false;
            init();
            appeng.menu.locator.MenuLocator locator = locatorOf(player, found);
            return locator.locate(player, appeng.api.storage.ISubMenuHost.class) != null;
        }

        /** 自检：当前会被选中的终端宿主的类名。 */
        static String selfTestHostClass(Player player) {
            Found found = findBest(player, false);
            if (found == null) return "";
            appeng.api.implementations.menuobjects.ItemMenuHost host = hostOf(player, found,
                    locatorOf(player, found), appeng.api.storage.ISubMenuHost.class);
            return host == null ? "" : host.getClass().getSimpleName();
        }

        /** 自检：wtlib 找宿主时用的那一次解析（{@code locate(WTMenuHost.class)}）。 */
        static boolean selfTestWTLibHostLookup(Player player) {
            if (!ModList.get().isLoaded("ae2wtlib")) return true;
            Found found = findBest(player, false);
            if (found == null) return true;
            return WTLibCompat.canResolveHost(player, found.ref(), locatorOf(player, found));
        }

        /** 自检：直接开一次 AE2 的 CraftAmountMenu（不检查能不能合成）。 */
        static boolean selfTestOpenCraftAmountMenu(ServerPlayer player, Item item) {
            Found found = findBest(player, false);
            if (found == null) return false;
            appeng.menu.locator.MenuLocator locator = locatorOf(player, found);
            appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(new ItemStack(item));
            if (key == null) return false;
            appeng.menu.me.crafting.CraftAmountMenu.open(player, locator, key, key.getAmountPerUnit());
            return player.containerMenu instanceof appeng.menu.me.crafting.CraftAmountMenu;
        }

        /**
         * 自检：把 locator 写进网络缓冲再读回来（客户端收到菜单包时做的正是这一步），
         * 然后用读回来的 locator 解析宿主。返回解析结果非空 = true。
         */
        static boolean selfTestLocatorRoundTrip(Player player) {
            Found found = findBest(player, false);
            if (found == null) return false;
            init();
            appeng.menu.locator.MenuLocator locator = locatorOf(player, found);
            try {
                net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer());
                appeng.menu.locator.MenuLocators.writeToPacket(buf, locator);
                appeng.menu.locator.MenuLocator decoded = appeng.menu.locator.MenuLocators.readFromPacket(buf);
                return decoded.locate(player, appeng.api.storage.ISubMenuHost.class) != null;
            } catch (Throwable ex) {
                dev.uninery.quickhatch.QuickHatch.LOGGER.warn("[selftest] locator round-trip failed", ex);
                return false;
            }
        }
    }
}
