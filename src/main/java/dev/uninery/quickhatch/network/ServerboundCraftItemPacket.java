package dev.uninery.quickhatch.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：中键请求合成——服务端打开 AE2 原生的下单界面（CraftAmountMenu）。
 *
 * @param item 要合成的物品
 */
public record ServerboundCraftItemPacket(ResourceLocation item) {

    public static void encode(ServerboundCraftItemPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.item());
    }

    public static ServerboundCraftItemPacket decode(FriendlyByteBuf buf) {
        return new ServerboundCraftItemPacket(buf.readResourceLocation());
    }

    public static void handle(ServerboundCraftItemPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                dev.uninery.quickhatch.server.CraftService.handle(msg, ctx.get().getSender()));
        ctx.get().setPacketHandled(true);
    }
}
