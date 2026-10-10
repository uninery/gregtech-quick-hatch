package dev.uninery.quickhatch;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 快捷仓室的配置文件（{@code config/quickhatch-common.toml}）。
 *
 * <p>目前只有一项："仓室选择界面关闭时是否保留手动筛选"。
 * 以前这是界面里的一个 static 字段，重进游戏就回到默认（不保留），
 * 用户要求写进配置文件，免得每次进游戏都得手动打开开关（第二十七轮）。</p>
 *
 * <p>界面上那个"筛选: 保留 / 不保留"开关点一下就会写这里并立刻存盘。</p>
 */
public final class QuickHatchConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /** 关界面时是否保留手动筛选。 */
    public static final ForgeConfigSpec.BooleanValue KEEP_FILTERS = BUILDER
            .comment("仓室选择界面关闭时是否保留手动筛选（就是界面左下角那个开关）",
                    "Keep manual filters when the hatch select screen is closed.")
            .define("keepFilters", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private QuickHatchConfig() {
    }

    /** 配置有没有加载好（专用服务端跑自检时可能是 false）。 */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    /** 读开关；配置没加载时按默认值（不保留）走。 */
    public static boolean keepFilters() {
        return SPEC.isLoaded() && Boolean.TRUE.equals(KEEP_FILTERS.get());
    }

    /** 写开关并立刻存盘（界面点击时调用）。 */
    public static void setKeepFilters(boolean value) {
        if (!SPEC.isLoaded()) return;
        KEEP_FILTERS.set(value);
        SPEC.save();
    }

    /** 配置文件路径（自检用）。 */
    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("quickhatch-common.toml");
    }

    /** 配置文件内容（读不到就返回空串，自检用）。 */
    public static String fileText() {
        try {
            Path path = file();
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
