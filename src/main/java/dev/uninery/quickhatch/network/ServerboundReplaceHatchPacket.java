package dev.uninery.quickhatch.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：替换请求 —— 把目标位置那个"能放仓室的方块"原地破坏，换成界面里选中的仓室。
 *
 * <p>界面是在<b>对着可替换方块右键 / Ctrl+左键</b>时打开的，那时客户端手上就有目标坐标，
 * 所以这个包只是把"选中的仓室 + 目标坐标"报给服务端，服务端做全部校验
 * （方块种类可替换、距离、仓室物品够不够）。</p>
 *
 * @param item 选中的仓室物品 id
 * @param pos  目标方块位置（被替换掉的那个）
 */
public record ServerboundReplaceHatchPacket(ResourceLocation item, BlockPos pos) {

    public static void encode(ServerboundReplaceHatchPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.item());
        buf.writeBlockPos(msg.pos());
    }

    public static ServerboundReplaceHatchPacket decode(FriendlyByteBuf buf) {
        return new ServerboundReplaceHatchPacket(buf.readResourceLocation(), buf.readBlockPos());
    }

    public static void handle(ServerboundReplaceHatchPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                dev.uninery.quickhatch.server.ReplaceService.handle(msg, ctx.get().getSender()));
        ctx.get().setPacketHandled(true);
    }
}
