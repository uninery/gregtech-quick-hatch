package dev.uninery.quickhatch.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：请求统计界面物品的可用数量（物品栏/背包/ME 网络）。
 * 服务端按当前索引全量统计一次并回包。
 */
public record ServerboundQueryCountsPacket() {

    public static void encode(ServerboundQueryCountsPacket msg, FriendlyByteBuf buf) {}

    public static ServerboundQueryCountsPacket decode(FriendlyByteBuf buf) {
        return new ServerboundQueryCountsPacket();
    }

    public static void handle(ServerboundQueryCountsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                dev.uninery.quickhatch.server.CountService.handleQuery(ctx.get().getSender()));
        ctx.get().setPacketHandled(true);
    }
}
