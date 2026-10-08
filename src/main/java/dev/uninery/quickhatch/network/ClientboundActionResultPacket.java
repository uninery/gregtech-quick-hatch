package dev.uninery.quickhatch.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：动作结果。
 *
 * @param success  是否成功
 * @param slot     拉取成功时放入的快捷栏槽位（-1 表示不切换/替换模式）
 * @param message  结果翻译键（空字符串表示无提示）
 */
public record ClientboundActionResultPacket(boolean success, int slot, String message) {

    public static void encode(ClientboundActionResultPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.success());
        buf.writeVarInt(msg.slot());
        buf.writeUtf(msg.message(), 256);
    }

    public static ClientboundActionResultPacket decode(FriendlyByteBuf buf) {
        return new ClientboundActionResultPacket(buf.readBoolean(), buf.readVarInt(), buf.readUtf(256));
    }

    public static void handle(ClientboundActionResultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                dev.uninery.quickhatch.client.ClientEvents.handleActionResult(msg));
        ctx.get().setPacketHandled(true);
    }
}
