package dev.uninery.quickhatch.platform;

import de.mari_023.ae2wtlib.terminal.ItemWT;
import de.mari_023.ae2wtlib.terminal.WTMenuHost;
import de.mari_023.ae2wtlib.wut.WTDefinition;
import de.mari_023.ae2wtlib.wut.WUTHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * ae2wtlib（AE2 Wireless Terminals）兼容层 —— <b>做法照抄 ExtendedAE_Plus 的
 * {@code compat/ae2wtlib/AE2WTLibCompat} + {@code menu/host/CuriosWTMenuHost} +
 * {@code menu/host/CuriosWTSubMenuHost}</b>，一行都没自己发明。
 *
 * <p><b>本类引用了 wtlib 的类，所以只会在装了 ae2wtlib 的时候才被 JVM 加载</b>
 * （调用点先判 {@code ModList.isLoaded("ae2wtlib")}，没装就永不触碰本类）。</p>
 *
 * <p>为什么必须用它：wtlib 的终端（{@code ItemWT}，通用终端 {@code ItemWUT} 也继承它）
 * 多一层"量子桥"玩法——终端可以在别的维度 / 离无线访问点很远的地方连网，
 * 判定写在 wtlib 的 {@code WTMenuHost#rangeCheck()}（{@code super.rangeCheck()
 * || isQuantumLinked()}）与 {@code getActionableNode()} 里。
 * 我们自己用 AE2 的 {@code WirelessTerminalMenuHost} 建宿主就会丢掉这套判定，
 * 于是饰品槽里的 wtlib 终端一律报"未连接或超出距离"。</p>
 */
final class WTLibCompat {

    private WTLibCompat() {}

    /** 是不是 wtlib 的终端物品（通用终端 / 各子终端）。对应 EAEP {@code isWirelessTerminalItem}。 */
    static boolean isWTLibTerminal(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof ItemWT;
    }

    /**
     * 终端当前能不能连网：通用终端要装了子终端，普通子终端看自己是否登记在册。
     * 对应 EAEP {@code isWirelessTerminal}（{@code WUTHandler.getCurrentTerminal} 有效）。
     */
    static boolean isUsableTerminal(ItemStack stack) {
        return definitionOf(stack) != null;
    }

    /**
     * 照 EAEP {@code AE2WTLibCompat.getConnectedGrid}：建出 wtlib 的宿主 →
     * {@code rangeCheck()}（含量子桥判定）→ {@code getActionableNode().getGrid()}。
     */
    @Nullable
    static appeng.api.networking.IGrid getConnectedGrid(Player player, ItemStack stack,
                                                       AE2Compat.TerminalRef ref,
                                                       appeng.menu.locator.MenuLocator locator) {
        WTMenuHost host = createHost(player, stack, ref, locator, appeng.api.storage.ISubMenuHost.class);
        if (host == null || !host.rangeCheck()) {
            return null;
        }
        var node = host.getActionableNode();
        return node == null ? null : node.getGrid();
    }

    /**
     * 照 EAEP {@code AE2WTLibCompat.locateMenuHost}：饰品槽 / 容器里的 wtlib 终端
     * 用我们自己的 Curios 宿主；物品栏里的终端用 <b>wtlib 自己声明的宿主工厂</b>
     * （{@code definition.wTMenuHostFactory().create(...)}，和 EAEP 同一行）。
     *
     * <p>唯一与 EAEP 不同的地方：{@code returnToMainMenu} 传的是"关掉当前 AE2 界面"
     * —— 本模组的终端界面是客户端自己的小窗（不是 AE2 菜单），下单完成后应当直接关掉，
     * 而不是像 EAEP 那样回到终端界面。</p>
     */
    @Nullable
    static WTMenuHost createHost(
            Player player, ItemStack stack, AE2Compat.TerminalRef ref,
            appeng.menu.locator.MenuLocator locator, Class<?> hostInterface) {
        WTDefinition definition = definitionOf(stack);
        if (definition == null) {
            return null;
        }
        try {
            if (ref.kind() != AE2Compat.SlotKind.INVENTORY) {
                return new CuriosWTMenuHost(player, stack, ref, locator);
            }
            return definition.wTMenuHostFactory().create(player, ref.slot(), stack,
                    (p, subMenu) -> AE2Compat.closeCraftMenu(p));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 自检用：wtlib 找终端物品栈时走的那一步
     * （{@code WUTHandler.getItemStackFromLocator} → {@code locator.locate(player, WTMenuHost.class)}）
     * 在我们的 locator 上能不能解析出 wtlib 的宿主。
     */
    static boolean canResolveHost(Player player, AE2Compat.TerminalRef ref,
                                  appeng.menu.locator.MenuLocator locator) {
        try {
            return locator.locate(player, WTMenuHost.class) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 打开终端自己的界面（wtlib 入口）。本模组暂不使用，保留给"返回终端"那种流程。 */
    static void openMainMenu(Player player, appeng.menu.locator.MenuLocator locator) {
        try {
            WUTHandler.open(player, locator, true);
        } catch (Throwable ignored) {
        }
    }

    /** 当前子终端的定义；通用终端没装子终端时返回 null。对应 EAEP {@code getDefinition}。 */
    @Nullable
    private static WTDefinition definitionOf(ItemStack stack) {
        try {
            String current = WUTHandler.getCurrentTerminal(stack);
            return current == null || current.isEmpty() ? null : WUTHandler.wirelessTerminals.get(current);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 饰品槽 / 背包里的 wtlib 终端宿主 —— 对应 EAEP 的
     * {@code CuriosWTMenuHost} + {@code CuriosWTSubMenuHost}
     * （两者实现完全一样，EAEP 拆两个类只是为了语义）。
     *
     * <p>{@code WTMenuHost} 自带量子桥判定与充能；它唯一不管的是"物品不在物品栏槽位"时的
     * 存在性检查与写回，这里按 {@link AE2Compat.TerminalRef} 补上。</p>
     */
    private static final class CuriosWTMenuHost extends WTMenuHost {

        private final AE2Compat.TerminalRef ref;

        CuriosWTMenuHost(Player player, ItemStack stack, AE2Compat.TerminalRef ref,
                         appeng.menu.locator.MenuLocator locator) {
            super(player, null, stack, (p, subMenu) -> AE2Compat.closeCraftMenu(p));
            this.ref = ref;
            // 装载 viewcells / singularity：量子桥判定要读 singularity 槽里的频率
            readFromNbt();
        }

        @Override
        protected boolean ensureItemStillInSlot() {
            ItemStack current = AE2Compat.terminalStackAt(getPlayer(), ref);
            return !current.isEmpty() && ItemStack.isSameItem(current, getItemStack());
        }

        @Override
        public boolean onBroadcastChanges(net.minecraft.world.inventory.AbstractContainerMenu menu) {
            AE2Compat.writeTerminalStack(getPlayer(), ref, getItemStack());
            return super.onBroadcastChanges(menu);
        }
    }
}
