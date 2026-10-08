package dev.uninery.quickhatch.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：拉取物品请求。
 *
 * @param item         要拉取的物品 id
 * @param stackSize    拉取数量（1 = 单个，64 = 一整组）
 * @param preferHotbar true = 放进快捷栏且可占用快捷栏槽位（普通左/右键，界面关闭）；
 *                     false = 不替换已有物品，先补同类再找空位（shift 点击，界面不关）
 */
public record ServerboundPullItemPacket(ResourceLocation item, int stackSize, boolean preferHotbar) {

    /** 一组的数量（界面里 shift+左键 / 普通左键拉取整组）。 */
    public static final int FULL_STACK = 64;

    /** 普通左键：一整组，可占用快捷栏槽位。 */
    public static ServerboundPullItemPacket pullStack(ResourceLocation item) {
        return new ServerboundPullItemPacket(item, FULL_STACK, true);
    }

    /** 普通右键：1 个，可占用快捷栏槽位。 */
    public static ServerboundPullItemPacket pullSingle(ResourceLocation item) {
        return new ServerboundPullItemPacket(item, 1, true);
    }

    /** shift+左键：一整组，不替换已有物品。 */
    public static ServerboundPullItemPacket pullStackKeep(ResourceLocation item) {
        return new ServerboundPullItemPacket(item, FULL_STACK, false);
    }

    /** shift+右键：1 个，不替换已有物品。 */
    public static ServerboundPullItemPacket pullSingleKeep(ResourceLocation item) {
        return new ServerboundPullItemPacket(item, 1, false);
    }

    public static void encode(ServerboundPullItemPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.item());
        buf.writeVarInt(msg.stackSize());
        buf.writeBoolean(msg.preferHotbar());
    }

    public static ServerboundPullItemPacket decode(FriendlyByteBuf buf) {
        return new ServerboundPullItemPacket(buf.readResourceLocation(), buf.readVarInt(),
                buf.readBoolean());
    }

    public static void handle(ServerboundPullItemPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                dev.uninery.quickhatch.server.PullService.handle(msg, ctx.get().getSender()));
        ctx.get().setPacketHandled(true);
    }
}
