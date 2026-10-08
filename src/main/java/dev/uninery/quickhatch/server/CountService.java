package dev.uninery.quickhatch.server;

import dev.uninery.quickhatch.network.ClientboundItemCountsPacket;
import dev.uninery.quickhatch.network.QuickHatchNetwork;
import dev.uninery.quickhatch.network.ServerboundQueryCountsPacket;
import dev.uninery.quickhatch.platform.AE2Compat;
import dev.uninery.quickhatch.platform.HatchIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 界面物品数量统计（物品栏 + 背包类容器 + AE2 网络合计）。
 */
public final class CountService {

    private CountService() {}

    public static void handleQuery(ServerboundQueryCountsPacket msg, ServerPlayer player) {
        if (player == null) return;
        Set<ResourceLocation> indexed = indexedIds();
        Map<ResourceLocation, Long> counts = count(player, indexed);
        Set<ResourceLocation> craftable = AE2Compat.isAvailable()
                ? AE2Compat.craftableIndexedItems(player, indexed) : Set.of();
        QuickHatchNetwork.sendToPlayer(new ClientboundItemCountsPacket(counts, craftable), player);
    }

    /** 兼容旧签名（网络包回调使用）。 */
    public static void handleQuery(ServerPlayer player) {
        handleQuery(new ServerboundQueryCountsPacket(), player);
    }

    public static Map<ResourceLocation, Long> count(ServerPlayer player) {
        return count(player, indexedIds());
    }

    private static Map<ResourceLocation, Long> count(ServerPlayer player, Set<ResourceLocation> indexed) {
        Map<ResourceLocation, Long> out = new HashMap<>();
        Inventory inv = player.getInventory();

        // 1) 玩家物品栏
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            add(out, indexed, stack.getItem(), stack.getCount());
        }

        // 2) 背包类容器（ITEM_HANDLER 能力；含 Curios 饰品槽里的精妙背包）
        AE2Compat.forEachContainer(player, container -> {
            if (container.isEmpty() || container.getCount() != 1) return;
            LazyOptional<IItemHandler> cap = container.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (!cap.isPresent()) return;
            IItemHandler handler = cap.resolve().orElse(null);
            if (handler == null) return;
            for (int s = 0; s < handler.getSlots(); s++) {
                ItemStack inSlot = handler.getStackInSlot(s);
                if (!inSlot.isEmpty()) {
                    add(out, indexed, inSlot.getItem(), inSlot.getCount());
                }
            }
        });

        // 3) AE2 网络（有无线终端时）
        if (AE2Compat.isAvailable()) {
            AE2Compat.addNetworkCounts(player, out, indexed);
        }
        return out;
    }

    private static void add(Map<ResourceLocation, Long> out, Set<ResourceLocation> indexed,
                            Item item, long count) {
        if (count <= 0 || indexed == null) return;
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        if (indexed.contains(id)) {
            out.merge(id, count, Long::sum);
        }
    }

    /** 当前索引涉及的全部物品 id（惰性构建一次）。 */
    private static volatile Set<ResourceLocation> idCache;

    private static Set<ResourceLocation> indexedIds() {
        Set<ResourceLocation> local = idCache;
        if (local == null) {
            synchronized (CountService.class) {
                if (idCache == null) {
                    java.util.HashSet<ResourceLocation> ids = new java.util.HashSet<>();
                    for (HatchIndex.Entry e : HatchIndex.get()) {
                        ids.add(e.id());
                    }
                    idCache = ids;
                }
                local = idCache;
            }
        }
        return local;
    }
}
