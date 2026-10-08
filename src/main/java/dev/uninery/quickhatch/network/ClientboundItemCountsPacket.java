package dev.uninery.quickhatch.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * S2C：物品可用数量（物品栏 + 背包 + ME 网络合计）+ ME 网络里可合成的物品集合。
 *
 * <p>可合成集合用来给界面画 AE 终端那种左上角"+"角标
 * （AE2 {@code MEStorageScreen} 对 {@code entry.isCraftable()} 的槽位调
 * {@code StackSizeRenderer.renderSizeLabel(guiGraphics, font, x - 11, y - 11, "+", false)}）。</p>
 *
 * @param counts    数量 &gt; 0 的物品
 * @param craftable ME 网络里"有样板、可下单"的物品 id
 */
public record ClientboundItemCountsPacket(Map<ResourceLocation, Long> counts,
                                          Set<ResourceLocation> craftable) {

    public static void encode(ClientboundItemCountsPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.counts().size());
        for (Map.Entry<ResourceLocation, Long> e : msg.counts().entrySet()) {
            buf.writeResourceLocation(e.getKey());
            buf.writeVarLong(e.getValue());
        }
        buf.writeVarInt(msg.craftable().size());
        for (ResourceLocation id : msg.craftable()) {
            buf.writeResourceLocation(id);
        }
    }

    public static ClientboundItemCountsPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<ResourceLocation, Long> counts = new HashMap<>();
        for (int i = 0; i < size; i++) {
            ResourceLocation id = buf.readResourceLocation();
            counts.put(id, buf.readVarLong());
        }
        int craftableSize = buf.readVarInt();
        Set<ResourceLocation> craftable = new LinkedHashSet<>();
        for (int i = 0; i < craftableSize; i++) {
            craftable.add(buf.readResourceLocation());
        }
        return new ClientboundItemCountsPacket(counts, craftable);
    }

    public static void handle(ClientboundItemCountsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                dev.uninery.quickhatch.client.ClientEvents.handleItemCounts(msg));
        ctx.get().setPacketHandled(true);
    }
}
