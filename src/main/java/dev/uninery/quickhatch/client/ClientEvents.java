package dev.uninery.quickhatch.client;

import dev.uninery.quickhatch.client.screen.HatchSelectScreen;
import dev.uninery.quickhatch.network.ClientboundActionResultPacket;
import dev.uninery.quickhatch.network.ClientboundItemCountsPacket;
import dev.uninery.quickhatch.network.QuickHatchNetwork;
import dev.uninery.quickhatch.network.ServerboundQueryCountsPacket;
import dev.uninery.quickhatch.platform.MultiblockRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.Set;

/**
 * 客户端事件。
 *
 * <p><b>G 键</b>：任何时候都能打开仓室选择界面（从设计之初就是如此，不对准方块也行、
 * 对着建筑方块也行）；这种方式打开的是普通模式，只拉取、不替换。</p>
 *
 * <p><b>Ctrl+左键</b>：对着"可替换方块"打开界面并进入替换模式 —— 判据是
 * "能在某种多方块结构里被替换为仓室的方块种类"，<b>或者这个位置本来就放着仓室</b>
 * （{@link MultiblockRegistry}）；样板总成之类"能放样板"的仓室不在内，
 * 免得把里面的样板弄丢。<b>右键不再打开界面</b>（用户要求）。</p>
 */
public final class ClientEvents {

    /** GLFW_LEFT 的边沿状态（Ctrl+左键必须自己轮询，见 onClientTick）。 */
    private static boolean leftMouseWasDown = false;

    private ClientEvents() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(ClientEvents::onRegisterKeys);
        MinecraftForge.EVENT_BUS.register(ClientEvents.class);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(KeyBindings.OPEN_SELECT);
    }

    // ------------------------------------------------------------------ //
    // 每 tick
    // ------------------------------------------------------------------ //

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            leftMouseWasDown = false;
            return;
        }
        // G 键：界面上按 = 关闭；世界里按 = 打开仓室选择界面（<b>不要求</b>对准任何方块，
        // 这种打开方式只拉取、不替换）
        while (KeyBindings.OPEN_SELECT.consumeClick()) {
            if (mc.screen instanceof HatchSelectScreen screen) {
                screen.onClose();
            } else if (mc.screen == null) {
                openSelectScreen(null);
            }
        }
        pollCtrlLeftClick(mc);
    }

    /**
     * 打开仓室选择界面（纯客户端，不需要服务端探测）。
     *
     * @param replaceTarget 对着哪个方块打开的：非 null 时界面进入"替换模式"，
     *                      左键点格子 = 原地把这个方块换成选中的仓室；G 键打开时传 null
     */
    private static void openSelectScreen(BlockPos replaceTarget) {
        Minecraft.getInstance().setScreen(new HatchSelectScreen(replaceTarget));
    }

    /**
     * Ctrl+左键必须自己轮询 GLFW。
     *
     * <p>原因：原版会把"Ctrl（=冲刺键）+ 左键"当成疾跑，直接把
     * {@code keyAttack} 的 down 状态吃掉，Forge 的
     * {@code InputEvent.InteractionKeyMappingTriggered}（isAttack）根本不会触发，
     * 所以之前的 Ctrl+左键完全失效。</p>
     *
     * <p>命中"可替换方块"（多方块里能被换成仓室的方块，<b>或者本身就已是仓室</b>，
     * 样板总成之类能放样板的仓室除外）就打开替换模式界面。</p>
     */
    private static void pollCtrlLeftClick(Minecraft mc) {
        boolean down;
        try {
            down = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT)
                    == GLFW.GLFW_PRESS;
        } catch (Throwable ex) {
            return;
        }
        boolean rising = down && !leftMouseWasDown;
        leftMouseWasDown = down;
        if (!rising) return;
        if (mc.screen != null || mc.level == null || mc.player == null) return;
        if (!mc.mouseHandler.isMouseGrabbed()) return;
        if (!net.minecraft.client.gui.screens.Screen.hasControlDown()) return;
        if (!(mc.hitResult instanceof BlockHitResult bhr) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }
        // 能替换就开界面（替换模式），不能替换就什么都不做
        if (isReplaceableAt(mc, bhr.getBlockPos())) {
            openSelectScreen(bhr.getBlockPos());
        }
    }

    // ------------------------------------------------------------------ //
    // 方块种类是否"能在某种多方块里被替换为仓室"（只看注册表 id）
    // ------------------------------------------------------------------ //

    public static boolean isReplaceableAt(Minecraft mc, BlockPos pos) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        return MultiblockRegistry.isReplaceable(state.getBlock());
    }

    // ------------------------------------------------------------------ //
    // 右键不再打开界面（用户要求：只保留 Ctrl+左键打开；G 键是另一个功能）
    // ------------------------------------------------------------------ //

    // ------------------------------------------------------------------ //
    // 服务端回包
    // ------------------------------------------------------------------ //

    public static void handleActionResult(ClientboundActionResultPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!packet.message().isEmpty()) {
            ChatFormatting color = packet.success() ? ChatFormatting.GREEN : ChatFormatting.RED;
            mc.player.displayClientMessage(Component.translatable(packet.message()).withStyle(color), true);
        }
        if (packet.success() && packet.slot() >= 0) {
            mc.player.getInventory().selected = packet.slot();
        }
        if (packet.success()) {
            // 拉取/替换成功后立刻刷新数量，让界面上的角标及时同步
            refreshItemCounts();
        }
    }

    // ------------------------------------------------------------------ //
    // 数量角标
    // ------------------------------------------------------------------ //

    private static volatile Map<ResourceLocation, Long> itemCounts = Map.of();
    private static volatile Set<ResourceLocation> craftableItems = Set.of();

    public static void handleItemCounts(ClientboundItemCountsPacket packet) {
        itemCounts = packet.counts();
        craftableItems = packet.craftable();
    }

    public static Map<ResourceLocation, Long> itemCounts() {
        return itemCounts;
    }

    /** ME 网络里可合成的物品（界面给它们画"+"角标）。 */
    public static boolean isCraftable(ResourceLocation id) {
        return craftableItems.contains(id);
    }

    public static void requestItemCounts() {
        QuickHatchNetwork.CHANNEL.sendToServer(new ServerboundQueryCountsPacket());
    }

    /**
     * 拉取/放回之后刷新数量：清掉旧数据并重新查询，
     * 这样界面上的数量角标是"及时同步"的（AE 终端也是每次操作后刷新）。
     */
    public static void refreshItemCounts() {
        itemCounts = Map.of();
        craftableItems = Set.of();
        requestItemCounts();
    }

    public static void invalidateItemCounts() {
        itemCounts = Map.of();
        craftableItems = Set.of();
    }

    // ------------------------------------------------------------------ //

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        invalidateItemCounts();
    }

    /** 客户端本地可替换方块种类数（调试用）。 */
    public static int replaceableBlockKinds() {
        Set<Block> blocks = MultiblockRegistry.replaceableBlocks();
        return blocks == null ? 0 : blocks.size();
    }
}
