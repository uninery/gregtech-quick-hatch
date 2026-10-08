package dev.uninery.quickhatch.server;

import appeng.core.definitions.AEBlocks;
import appeng.items.tools.powered.WirelessTerminalItem;
import dev.uninery.quickhatch.QuickHatch;
import dev.uninery.quickhatch.platform.AE2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * 无线终端端到端自检（只在 AE2 装了的时候被加载，引用 AE2 的类）。
 *
 * <p>做的事：在自检世界里搭一个最小的 ME 网络（无线访问点 + 创造能量仓），
 * 把一个无线终端链到访问点上，然后让中键下单走一遍完整链路并断言结果。
 * 这是"中键下单一律报未连接 / 超出距离"的<b>真机</b>回归测试：</p>
 *
 * <ul>
 *   <li>网络空、没有样板 → 必须回 {@code not_craftable}。
 *       回 {@code not_craftable} 就说明"找到终端 → 建成宿主 → 拿到 grid → 问过样板"整条链路通了；
 *       回 {@code no_range} 或 {@code no_terminal} 都是失败。</li>
 *   <li>物品栏第 0 格先塞一个便携元件（也是 {@code IMenuItem}，但没有网络节点），
 *       第 1 格才是无线终端 → 仍必须是 {@code not_craftable}。
 *       旧代码会抓走第 0 格的便携元件，于是永远 {@code no_range}。</li>
 * </ul>
 */
final class AETerminalProbe {

    /** 一次服务器启动只跑一次（自检会对多个样例物品各跑一遍，重复搭网络会互相拆台）。 */
    private static boolean ran;

    private AETerminalProbe() {}

    /** 搭好网络并把断言排到 40 tick 之后（网格要 tick 几拍才会通电激活）。 */
    static synchronized void run(MinecraftServer server) {
        if (ran) return;
        ran = true;
        ServerLevel level = server.overworld();
        var fake = FakePlayerFactory.getMinecraft(level);

        // 必须放在"正在 tick 的已加载区块"里：世界出生点 + 强制加载，否则网格节点起不来
        BlockPos base = level.getSharedSpawnPos();
        level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        fake.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        BlockPos wapPos = base.offset(2, 0, 0);

        // 无线访问点只在它"背面"那一个方向连网（WirelessAccessPointBlockEntity
        // #getGridConnectableSides 只返回 BACK），所以六个方向都放创造能量仓，保证接上。
        var restore = new java.util.ArrayList<Object[]>();
        restore.add(new Object[]{wapPos, level.getBlockState(wapPos)});
        level.setBlockAndUpdate(wapPos, AEBlocks.WIRELESS_ACCESS_POINT.block().defaultBlockState());
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos cellPos = wapPos.relative(dir);
            restore.add(new Object[]{cellPos, level.getBlockState(cellPos)});
            level.setBlockAndUpdate(cellPos, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        }

        Item terminal = itemOf("ae2:wireless_terminal");
        Item portableCell = itemOf("ae2:portable_item_cell_1k");
        // ae2wtlib 的无线样板编码终端（ItemWT，注册进 wtlib 的终端表，装了 wtlib 才有）
        Item wtlibTerminal = itemOf("ae2wtlib:wireless_pattern_encoding_terminal");
        Item probe = itemOf("gtceu:lv_input_bus");
        if (terminal == null || probe == null) {
            QuickHatch.LOGGER.info("[selftest] terminal e2e skipped: ae2 items missing");
            return;
        }

        ItemStack linked = new ItemStack(terminal);
        WirelessTerminalItem.LINKABLE_HANDLER.link(linked, GlobalPos.of(level.dimension(), wapPos));
        // 终端出厂是没电的，先充满（AE2 自己也是先看"已链接 + 有电"）
        if (terminal instanceof WirelessTerminalItem terminalItem) {
            terminalItem.injectAEPower(linked, terminalItem.getAEMaxPower(linked),
                    appeng.api.config.Actionable.MODULATE);
        }

        server.tell(new TickTask(server.getTickCount() + 40, () -> {
            try {
                // 诊断：网络是否真的起来了
                var be = level.getBlockEntity(wapPos);
                var wap = be instanceof appeng.api.implementations.blockentities.IWirelessAccessPoint point
                        ? point : null;
                var terminalItem = (WirelessTerminalItem) terminal;
                QuickHatch.LOGGER.info("[selftest] terminal e2e setup: wapPosBE={} grid={} linkedGrid={} power={}",
                        be == null ? "null" : be.getClass().getSimpleName(),
                        wap == null ? "not-IWirelessAccessPoint" : wap.getGrid(),
                        terminalItem.getLinkedGrid(linked, level, null),
                        terminalItem.getAECurrentPower(linked));

                // 场景 A：只有无线终端
                fake.getInventory().clearContent();
                fake.getInventory().setItem(0, linked);
                String only = AE2Compat.openCraftAmountMenu(fake, probe);
                boolean locatorOk = AE2Compat.selfTestResolveHost(fake);

                // 场景 B：便携元件排在无线终端前面
                String mixed = only;
                if (portableCell != null) {
                    fake.getInventory().clearContent();
                    fake.getInventory().setItem(0, new ItemStack(portableCell));
                    fake.getInventory().setItem(1, linked);
                    mixed = AE2Compat.openCraftAmountMenu(fake, probe);
                }

                // 场景 C：终端装在容器（潜影盒，任意 ITEM_HANDLER）里 —— 走自定义 locator + 宿主
                String nested = nestedProbe(fake, linked, probe);
                boolean nestedLocator = false;
                if (nested != null) {
                    nestedLocator = AE2Compat.selfTestResolveHost(fake);
                }

                // 场景 D：终端挂在 Curios 饰品槽（Curios 没装 / 假玩家没有饰品栏时跳过）
                String curios = null;
                boolean curiosLocator = false;
                String curiosHost = "";
                if (net.minecraftforge.fml.ModList.get().isLoaded("curios")) {
                    String[] result = CuriosProbe.run(fake, linked, probe);
                    if (result != null) {
                        curios = result[0];
                        curiosLocator = Boolean.parseBoolean(result[1]);
                        curiosHost = result[2];
                    }
                }

                // 场景 E：ae2wtlib 的通用终端挂饰品槽（就是用户报的"在饰品栏无法下单"那条）——
                // 必须走 wtlib 的宿主（量子桥判定在里面），不能用 AE2 自己的宿主
                String wtlib = null;
                boolean wtlibLocator = false;
                boolean wtlibHostLookup = true;
                String wtlibHost = "";
                if (wtlibTerminal != null && net.minecraftforge.fml.ModList.get().isLoaded("curios")) {
                    ItemStack wtStack = new ItemStack(wtlibTerminal);
                    WirelessTerminalItem.LINKABLE_HANDLER.link(wtStack,
                            GlobalPos.of(level.dimension(), wapPos));
                    if (wtlibTerminal instanceof WirelessTerminalItem item) {
                        item.injectAEPower(wtStack, item.getAEMaxPower(wtStack),
                                appeng.api.config.Actionable.MODULATE);
                    }
                    String[] result = CuriosProbe.run(fake, wtStack, probe);
                    if (result != null) {
                        wtlib = result[0];
                        wtlibLocator = Boolean.parseBoolean(result[1]);
                        wtlibHost = result[2];
                        wtlibHostLookup = Boolean.parseBoolean(result[3]);
                    }
                }

                // 场景 F：locator 的"写包 → 读包 → 解析宿主"往返（客户端收到菜单包时的那一步）
                fake.getInventory().clearContent();
                fake.getInventory().setItem(0, linked);
                boolean roundTrip = AE2Compat.selfTestLocatorRoundTrip(fake);

                // 场景 G：AE2 下单界面走完"返回主菜单"时必须自动关掉
                // （用户报的"下单后下单界面没有自动关闭"）
                String closeCheck = closeProbe(fake, probe);

                boolean wtlibOk = wtlib == null || ("not_craftable".equals(wtlib) && wtlibLocator
                        && wtlibHost.contains("WT"));
                boolean ok = "not_craftable".equals(only)
                        && "not_craftable".equals(mixed)
                        && "not_craftable".equals(nested)
                        && locatorOk && nestedLocator
                        && (curios == null || ("not_craftable".equals(curios) && curiosLocator))
                        && wtlibOk && roundTrip && wtlibHostLookup && "closed".equals(closeCheck);
                QuickHatch.LOGGER.info("[selftest] terminal e2e: terminal-only={} terminal+cell={} "
                                + "in-container={} in-curios={}(host={}) in-curios-wtlib={}(host={}) "
                                + "locator(inv)={} locator(container)={} locator(curios)={} locator(wtlib)={} "
                                + "wtlib-host-lookup={} round-trip={} return-to-main-menu={} -> {}",
                        only, mixed, nested,
                        curios == null ? "n/a" : curios, curiosHost.isEmpty() ? "-" : curiosHost,
                        wtlib == null ? "n/a" : wtlib, wtlibHost.isEmpty() ? "-" : wtlibHost,
                        locatorOk, nestedLocator, curiosLocator,
                        wtlib == null ? "n/a" : String.valueOf(wtlibLocator),
                        wtlibHostLookup, roundTrip, closeCheck,
                        ok ? "PASS" : "FAIL (expected not_craftable + locator resolvable)");
            } finally {
                fake.getInventory().clearContent();
                for (Object[] entry : restore) {
                    level.setBlockAndUpdate((BlockPos) entry[0], (BlockState) entry[1]);
                }
            }
        }));
    }

    private static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item == Items.AIR ? null : item;
    }

    /**
     * 场景 G：AE2 下单界面走到"返回主菜单"时必须自动关掉
     * （用户报的"下单后下单界面没有自动关闭"）。
     *
     * <p>做法就是把 AE2 的真实链路走一遍：{@code CraftAmountMenu.open} →
     * 断言真的开出了 {@code CraftAmountMenu} → 调 {@code confirm(0, ...)}
     * （数量 0 = 取消，AE2 会走 {@code host.returnToMainMenu(player, this)}）
     * → 断言菜单确实被关掉了。</p>
     *
     * @return {@code "closed"} = 关掉了；其它值是失败原因
     */
    private static String closeProbe(net.minecraft.server.level.ServerPlayer player, Item item) {
        if (item == null) return "no_item";
        try {
            AE2Compat.selfTestOpenCraftAmountMenu(player, item);
            if (!(player.containerMenu instanceof appeng.menu.me.crafting.CraftAmountMenu amountMenu)) {
                return "not-open:" + player.containerMenu.getClass().getSimpleName();
            }
            // 数量 0 → AE2 走 "returnToMainMenu" 分支（host 的回调应当关掉界面）
            amountMenu.confirm(0, false, false);
            return player.containerMenu instanceof appeng.menu.me.crafting.CraftAmountMenu
                    ? "still-open" : "closed";
        } catch (Throwable ex) {
            QuickHatch.LOGGER.warn("[selftest] close probe failed", ex);
            return "error:" + ex.getClass().getSimpleName();
        }
    }

    /**
     * Curios 饰品槽那一档。单独一个内部类，**只在 Curios 装了的时候才会被 JVM 加载**
     * （引用 Curios 的类，放在外面会让没装 Curios 的服崩）。
     *
     * @return {@code [下单结果, locator 是否解析成功, 宿主类名]}；假玩家没有饰品栏时返回 null
     */
    private static final class CuriosProbe {

        private CuriosProbe() {}

        static String[] run(net.minecraft.world.entity.player.Player player,
                            ItemStack terminal, Item probe) {
            try {
                var resolved = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
                if (resolved.isEmpty()) return null;
                for (var entry : resolved.get().getCurios().entrySet()) {
                    var stacks = entry.getValue().getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        ItemStack old = stacks.getStackInSlot(i);
                        stacks.setStackInSlot(i, terminal);
                        player.getInventory().clearContent();
                        String craft = AE2Compat.openCraftAmountMenu(
                                (net.minecraft.server.level.ServerPlayer) player, probe);
                        boolean locator = AE2Compat.selfTestResolveHost(
                                (net.minecraft.server.level.ServerPlayer) player);
                        String host = AE2Compat.selfTestHostClass(
                                (net.minecraft.server.level.ServerPlayer) player);
                        boolean hostLookup = AE2Compat.selfTestWTLibHostLookup(
                                (net.minecraft.server.level.ServerPlayer) player);
                        stacks.setStackInSlot(i, old);
                        return new String[]{craft, String.valueOf(locator), host, String.valueOf(hostLookup)};
                    }
                }
            } catch (Throwable ex) {
                QuickHatch.LOGGER.warn("[selftest] curios probe failed", ex);
            }
            return null;
        }
    }

    /**
     * 把链接好的终端塞进一个潜影盒（带 {@code ITEM_HANDLER} 的容器物品）再放进物品栏，
     * 然后跑一次中键下单。返回下单结果；装不进去返回 null。
     */
    private static String nestedProbe(net.minecraft.world.entity.player.Player player,
                                      ItemStack terminal, Item probe) {
        ItemStack box = new ItemStack(net.minecraft.world.level.block.Blocks.SHULKER_BOX);
        var cap = box.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER);
        if (!cap.isPresent()) return null;
        var handler = cap.resolve().orElse(null);
        if (handler == null || handler.getSlots() <= 0) return null;
        if (!handler.insertItem(0, terminal, false).isEmpty()) return null;

        player.getInventory().clearContent();
        player.getInventory().setItem(0, box);
        return AE2Compat.openCraftAmountMenu((net.minecraft.server.level.ServerPlayer) player, probe);
    }
}
