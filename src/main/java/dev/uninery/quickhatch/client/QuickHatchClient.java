package dev.uninery.quickhatch.client;

import dev.uninery.quickhatch.QuickHatch;
import dev.uninery.quickhatch.platform.MultiblockRegistry;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

/**
 * 客户端入口：仅在物理客户端被加载（见 QuickHatch 构造器中的 Dist 判断）。
 *
 * <p>在这里<b>提前</b>把全局多方块索引建好（需求 3：遍历应在进入存档前完成），
 * 免得玩家第一次右键/Ctrl+左键时才现算、卡一下。</p>
 */
public final class QuickHatchClient {

    private QuickHatchClient() {}

    public static void init(IEventBus modEventBus) {
        ClientEvents.init(modEventBus);
        // FMLLoadCompleteEvent：所有模组注册与 common setup 都已完成，
        // 此时 GTRegistries.MACHINES 与 AE2 的注册表都是完整的。
        modEventBus.addListener(QuickHatchClient::onLoadComplete);
    }

    private static void onLoadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            long start = System.nanoTime();
            int blocks = MultiblockRegistry.replaceableBlocks().size();
            int controllers = MultiblockRegistry.controllerCount();
            QuickHatch.LOGGER.info(
                    "[quickhatch] multiblock index prebuilt before world join: "
                            + "controllers={} replaceableBlockKinds={} ({} ms)",
                    controllers, blocks, (System.nanoTime() - start) / 1_000_000);
        });
    }
}
