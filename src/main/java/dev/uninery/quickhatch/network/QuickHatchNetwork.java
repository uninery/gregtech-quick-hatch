package dev.uninery.quickhatch.network;

import dev.uninery.quickhatch.QuickHatch;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络通道：C2S 交互请求（拉取 / 下单 / 替换），S2C 结果（动作结果 / 数量）。
 *
 * <p>"选中主方块高亮 + 结构筛选"、"底部物品栏 + 鼠标拿放 + 撤销替换"这些
 * 已经废弃的功能连同它们的包一起删掉了。</p>
 */
public final class QuickHatchNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(QuickHatch.MOD_ID, "main"),
            () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);

    private static int packetId = 0;

    private QuickHatchNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(packetId++, ServerboundPullItemPacket.class,
                ServerboundPullItemPacket::encode, ServerboundPullItemPacket::decode,
                ServerboundPullItemPacket::handle);
        CHANNEL.registerMessage(packetId++, ClientboundActionResultPacket.class,
                ClientboundActionResultPacket::encode, ClientboundActionResultPacket::decode,
                ClientboundActionResultPacket::handle);
        CHANNEL.registerMessage(packetId++, ServerboundQueryCountsPacket.class,
                ServerboundQueryCountsPacket::encode, ServerboundQueryCountsPacket::decode,
                ServerboundQueryCountsPacket::handle);
        CHANNEL.registerMessage(packetId++, ClientboundItemCountsPacket.class,
                ClientboundItemCountsPacket::encode, ClientboundItemCountsPacket::decode,
                ClientboundItemCountsPacket::handle);
        CHANNEL.registerMessage(packetId++, ServerboundCraftItemPacket.class,
                ServerboundCraftItemPacket::encode, ServerboundCraftItemPacket::decode,
                ServerboundCraftItemPacket::handle);
        CHANNEL.registerMessage(packetId++, ServerboundReplaceHatchPacket.class,
                ServerboundReplaceHatchPacket::encode, ServerboundReplaceHatchPacket::decode,
                ServerboundReplaceHatchPacket::handle);
    }

    public static void sendToPlayer(Object msg, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }
}
