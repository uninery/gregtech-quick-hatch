package dev.uninery.quickhatch;

import dev.uninery.quickhatch.client.QuickHatchClient;
import dev.uninery.quickhatch.network.QuickHatchNetwork;
import dev.uninery.quickhatch.platform.AE2Compat;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 快捷仓室 (Quick Hatch) — GregTech CEu Modern 辅助模组。
 *
 * <p>G 键（或对着可替换方块右键 / Ctrl+左键）打开仓室选择界面：一个 ME 终端式的小窗，
 * 左侧按分类 / 电压 / 电流筛选，点网格从物品栏、精妙背包或无线终端所在的 ME 网络
 * 拉取仓室到快捷栏，中键走 AE2 原生的下单界面。</p>
 */
@Mod(QuickHatch.MOD_ID)
public class QuickHatch {

    public static final String MOD_ID = "quickhatch";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public QuickHatch() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.addListener(QuickHatch::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(QuickHatch::onServerStarted);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            QuickHatchClient.init(modEventBus);
        }
    }

    private static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        dev.uninery.quickhatch.server.QuickHatchDebug.register(event.getDispatcher());
    }

    private static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        if (!Boolean.getBoolean("quickhatch.selftest")) return;
        event.getServer().tell(new net.minecraft.server.TickTask(
                event.getServer().getTickCount() + 60, () -> {
            for (String id : new String[]{"minecraft:stone", "gtceu:lv_input_bus", "gtceu:lv_energy_input_hatch"}) {
                dev.uninery.quickhatch.server.QuickHatchDebug.runSelfTest(event.getServer().overworld(), id);
            }
        }));
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            QuickHatchNetwork.register();
            // AE2 装了才加载 Holder：注册自定义 MenuLocator（客户端/服务端都要有）
            AE2Compat.init();
        });
        LOGGER.info("Quick Hatch loaded");
    }
}
