package dev.uninery.quickhatch.server;

import dev.uninery.quickhatch.network.ClientboundActionResultPacket;
import dev.uninery.quickhatch.network.QuickHatchNetwork;
import dev.uninery.quickhatch.network.ServerboundCraftItemPacket;
import dev.uninery.quickhatch.platform.AE2Compat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 中键合成：打开 AE2 原生的下单界面（{@code CraftAmountMenu}）。
 *
 * <p>照原版 AE 终端中键（{@code MEStorageMenu} 的 {@code AUTO_CRAFT}）与
 * ExtendedAE_Plus 的 JEI 下单（{@code OpenCraftFromJeiC2SPacket}）：
 * 定位无线终端 → 用终端宿主拿 grid → {@code CraftAmountMenu.open(player, locator, key, n)}，
 * 初始数量取 {@code AEItemKey#getAmountPerUnit()}（两边参考实现都是这个值）。
 * 数量输入、合成计划与提交全部由 AE2 自己的界面完成，这边不算任何配方。</p>
 */
public final class CraftService {

    private CraftService() {}

    public static void handle(ServerboundCraftItemPacket msg, ServerPlayer player) {
        if (player == null) return;
        Item item = BuiltInRegistries.ITEM.get(msg.item());
        if (item == Items.AIR || !msg.item().equals(BuiltInRegistries.ITEM.getKey(item))) {
            fail(player, "message.quickhatch.invalid_item");
            return;
        }
        String status = AE2Compat.openCraftAmountMenu(player, item);
        switch (status) {
            case "opened" -> { /* 界面已打开，无需提示 */ }
            case "no_terminal" -> notify(player, "message.quickhatch.ae_no_terminal");
            case "no_range" -> notify(player, "message.quickhatch.ae_no_range");
            case "not_craftable" -> notify(player, "message.quickhatch.not_craftable");
            default -> notify(player, "message.quickhatch.craft_failed");
        }
    }

    private static void notify(ServerPlayer player, String key) {
        QuickHatchNetwork.sendToPlayer(new ClientboundActionResultPacket(true, -1, key), player);
    }

    private static void fail(ServerPlayer player, String key) {
        QuickHatchNetwork.sendToPlayer(new ClientboundActionResultPacket(false, -1, key), player);
    }
}
