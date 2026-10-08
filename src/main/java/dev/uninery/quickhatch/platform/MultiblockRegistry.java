package dev.uninery.quickhatch.platform;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import dev.uninery.quickhatch.QuickHatch;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局多方块索引（与存档无关，只看注册表）。
 *
 * <p>回答的问题：<b>某个方块种类能不能在某种多方块结构里被替换成仓室</b>。
 * 做法是把 {@code GTRegistries.MACHINES} 里所有 {@link MultiblockMachineDefinition}
 * 的图案逐槽位扫一遍：</p>
 *
 * <ol>
 *   <li>该槽位的 {@link TraceabilityPredicate} 候选里<b>有机器方块</b> → 这是仓室槽位；</li>
 *   <li>仓室槽位里"图案上写的那个方块"（{@link BlockPattern#getPreview(int[])} 给出，
 *       例如大型搅拌罐的<b>惰性搅拌机械方块</b>）→ 可被替换为仓室；</li>
 *   <li>没有机器候选的槽位（例如大型搅拌罐的<b>不锈钢齿轮机械方块</b>）→ 只是结构方块，
 *       不可替换。</li>
 * </ol>
 *
 * <p>因此即使世界上一个大型搅拌罐都没有，对着惰性搅拌机械方块右键/Ctrl+左键
 * 也应当能触发替换：判定与"存档里有没有这个多方块"完全无关。</p>
 *
 * <p>遍历槽位谓词需要 {@link BlockPattern#blockMatches}（protected），
 * 这里用反射读取该字段（只 {@code get}，不改可见性）。反射拿不到时该槽位按
 * "无谓词"处理——此时只保留"图案方块本身就是仓室"的位置，行方块会少一些，
 * 但不会误判结构方块为可替换。</p>
 */
public final class MultiblockRegistry {

    /** 该方块种类占着仓室槽位时，槽位允许的仓室定义 id。 */
    public record ReplaceInfo(Set<ResourceLocation> allowedHatches) {}

    private static volatile Map<Block, ReplaceInfo> index;
    private static volatile Set<Block> replaceableBlocks;
    /** 索引里扫到的多方块主方块集合（只用于调试统计）。 */
    private static volatile Set<Block> knownControllers;

    private MultiblockRegistry() {}

    /** 索引里已知的多方块主方块数量（调试/预构建日志用）。 */
    public static int controllerCount() {
        index();
        Set<Block> local = knownControllers;
        return local == null ? 0 : local.size();
    }

    /** 惰性构建（首次访问时扫描全部注册多方块）。 */
    public static Map<Block, ReplaceInfo> index() {
        Map<Block, ReplaceInfo> local = index;
        if (local == null) {
            synchronized (MultiblockRegistry.class) {
                if (index == null) {
                    index = scan();
                }
                local = index;
            }
        }
        return local;
    }

    /** 全部"可被替换为仓室"的方块种类。 */
    public static Set<Block> replaceableBlocks() {
        index();
        Set<Block> local = replaceableBlocks;
        return local == null ? Set.of() : local;
    }

    /**
     * 该方块种类是否可以被替换为仓室。
     *
     * <p>两种情况：① 它是某种多方块图案里的仓室槽位方块（{@link #replaceableBlocks()}）；
     * ② 它本身就是一个<b>已放置的仓室</b>（{@link HatchIndex#replaceableHatchBlocks()}，
     * 已经排除了样板总成之类"能放样板"的仓室，免得把里面的样板弄丢）。</p>
     */
    public static boolean isReplaceable(Block block) {
        if (replaceableBlocks().contains(block)) return true;
        return HatchIndex.replaceableHatchBlocks().contains(block);
    }

    /** 该方块种类占着仓室槽位时允许的仓室定义；不是仓室槽位方块则返回空集。 */
    public static Set<ResourceLocation> allowedHatches(Block block) {
        ReplaceInfo info = index().get(block);
        return info == null ? Set.of() : info.allowedHatches();
    }

    // ------------------------------------------------------------------ //

    private static Map<Block, ReplaceInfo> scan() {
        Map<Block, Set<ResourceLocation>> acc = new LinkedHashMap<>();
        // 先把所有注册多方块的主方块收下来：主方块永远不参与替换
        Set<Block> controllers = new LinkedHashSet<>();
        for (Map.Entry<ResourceLocation, MachineDefinition> entry : GTRegistries.MACHINES.entries()) {
            MachineDefinition def = entry.getValue();
            if (!(def instanceof MultiblockMachineDefinition)) continue;
            try {
                var block = def.getBlock();
                if (block != null) controllers.add(block);
            } catch (Throwable ignored) {
            }
        }
        Field blockMatches = findBlockMatchesField();
        int multiblocks = 0;
        int failed = 0;
        for (Map.Entry<ResourceLocation, MachineDefinition> entry : GTRegistries.MACHINES.entries()) {
            if (!(entry.getValue() instanceof MultiblockMachineDefinition mbDef)) continue;
            BlockPattern pattern = patternOf(mbDef);
            if (pattern == null) continue;
            try {
                scanPattern(pattern, blockMatches, acc, controllers);
                multiblocks++;
            } catch (Throwable ex) {
                failed++;
                QuickHatch.LOGGER.debug("[quickhatch] pattern scan failed for {}", entry.getKey(), ex);
            }
        }
        for (Block controller : controllers) {
            acc.remove(controller);
        }
        Map<Block, ReplaceInfo> out = new HashMap<>();
        Set<ResourceLocation> allHatches = new LinkedHashSet<>();
        for (Map.Entry<Block, Set<ResourceLocation>> e : acc.entrySet()) {
            out.put(e.getKey(), new ReplaceInfo(Set.copyOf(e.getValue())));
            allHatches.addAll(e.getValue());
        }
        replaceableBlocks = Set.copyOf(out.keySet());
        knownControllers = Set.copyOf(controllers);
        QuickHatch.LOGGER.info(
                "[quickhatch] multiblock index: multiblocks={} failed={} controllers={} "
                        + "replaceableBlockKinds={} (predicates={})",
                multiblocks, failed, controllers.size(), out.size(), blockMatches != null);
        return Map.copyOf(out);
    }

    /** 反射取 {@code BlockPattern#blockMatches}（Forge 运行时已被 AT 改成 public）。 */
    private static Field findBlockMatchesField() {        try {
            Field field = BlockPattern.class.getDeclaredField("blockMatches");
            field.setAccessible(true);
            return field;
        } catch (Throwable ex) {
            QuickHatch.LOGGER.warn("[quickhatch] cannot access BlockPattern#blockMatches, "
                    + "falling back to preview-only multiblock scan", ex);
            return null;
        }
    }

    private static BlockPattern patternOf(MultiblockMachineDefinition def) {
        try {
            var factory = def.getPatternFactory();
            return factory == null ? null : factory.get();
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * 扫一个图案：直接遍历 {@code blockMatches[z][y][x]}，逐个槽位取谓词候选。
     *
     * <p><b>不用</b> {@code getPreview}：它每个槽位只返回第一个候选
     * （{@code infos[0]}），在 {@code blocks(A).or(abilities(...))} 这种共用谓词里会挑到
     * 外壳方块，导致 {@code autoAbilities} 带进来的仓室（含并行控制仓）整批漏掉。</p>
     */
    private static void scanPattern(BlockPattern pattern, Field blockMatches,
                                    Map<Block, Set<ResourceLocation>> acc, Set<Block> controllers) {
        TraceabilityPredicate[][][] matches = readMatches(pattern, blockMatches);
        if (matches == null) return;
        for (int c = 0; c < matches.length; c++) {
            TraceabilityPredicate[][] aisle = matches[c];
            if (aisle == null) continue;
            for (int b = 0; b < aisle.length; b++) {
                TraceabilityPredicate[] row = aisle[b];
                if (row == null) continue;
                for (int a = 0; a < row.length; a++) {
                    TraceabilityPredicate predicate = row[a];
                    if (predicate == null) continue;
                    // 槽位必须能放仓室（候选里有机器方块），否则是纯结构方块槽位：
                    // 例如大型搅拌罐的不锈钢齿轮机械方块 where('G', blocks(...))
                    Set<ResourceLocation> hatches = machineIdsOf(predicate);
                    if (hatches.isEmpty()) continue;
                    // 只认"图案里用 blocks(...) 明确写出"的方块（外壳那种），
                    // 不要 blockTag/coil 之类；逐个候选判断而不是只看挑出来的那一个
                    for (Block candidate : blockCandidatesOf(predicate)) {
                        if (candidate == Blocks.AIR || candidate == Blocks.BARRIER) continue;
                        if (candidate instanceof IMachineBlock) continue;
                        if (controllers.contains(candidate)) continue;
                        if (isControllerBlock(candidate)) continue;
                        acc.computeIfAbsent(candidate, k -> new LinkedHashSet<>()).addAll(hatches);
                    }
                }
            }
        }
    }

    private static TraceabilityPredicate[][][] readMatches(BlockPattern pattern, Field field) {
        if (field == null) return null;
        try {
            Object value = field.get(pattern);
            return value instanceof TraceabilityPredicate[][][] matches ? matches : null;
        } catch (Throwable ex) {
            return null;
        }
    }

    /** 谓词里用 {@code blocks(...)} 明确写出的全部方块（不含机器方块与空气）。 */
    private static Set<Block> blockCandidatesOf(TraceabilityPredicate predicate) {
        Set<Block> out = new LinkedHashSet<>();
        for (SimplePredicate sp : allOf(predicate)) {
            if (!(sp instanceof com.gregtechceu.gtceu.api.pattern.predicates.PredicateBlocks)) continue;
            BlockInfo[] candidates = candidatesOf(sp);
            if (candidates == null) continue;
            for (BlockInfo info : candidates) {
                BlockState bs = info == null ? null : info.getBlockState();
                if (bs == null) continue;
                Block block = bs.getBlock();
                if (block == Blocks.AIR || block == Blocks.BARRIER) continue;
                if (block instanceof IMachineBlock) continue;
                out.add(block);
            }
        }
        return out;
    }

    /** 谓词里有没有 {@code PredicateBlocks} 类型的候选（= 图案明确列出的方块，而不是 tag/state）。 */
    private static boolean hasBlockCandidate(TraceabilityPredicate predicate) {
        for (SimplePredicate sp : allOf(predicate)) {
            if (sp instanceof com.gregtechceu.gtceu.api.pattern.predicates.PredicateBlocks
                    && sp.candidates != null) {
                return true;
            }
        }
        return false;
    }

    /** 该方块是不是多方块主方块（主方块永远不参与替换）。 */
    public static boolean isControllerBlock(Block block) {
        if (!(block instanceof IMachineBlock machineBlock)) return false;
        try {
            MachineDefinition def = machineBlock.getDefinition();
            if (def instanceof MultiblockMachineDefinition) return true;
            return def != null && def.getBlock() instanceof IMachineBlock mb
                    && mb.getDefinition() instanceof MultiblockMachineDefinition;
        } catch (Throwable ex) {
            return false;
        }
    }

    private static Set<ResourceLocation> machineIdsOf(TraceabilityPredicate predicate) {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (SimplePredicate sp : allOf(predicate)) {
            BlockInfo[] candidates = candidatesOf(sp);
            if (candidates == null) continue;
            for (BlockInfo info : candidates) {
                BlockState bs = info == null ? null : info.getBlockState();
                if (bs == null) continue;
                if (bs.getBlock() instanceof IMachineBlock machineBlock) {
                    ResourceLocation id = GtIds.id(machineBlock.getDefinition());
                    if (id != null) out.add(id);
                }
            }
        }
        return out;
    }

    private static List<SimplePredicate> allOf(TraceabilityPredicate predicate) {
        List<SimplePredicate> out = new ArrayList<>(predicate.common.size() + predicate.limited.size());
        out.addAll(predicate.common);
        out.addAll(predicate.limited);
        return out;
    }

    private static BlockInfo[] candidatesOf(SimplePredicate sp) {
        if (sp == null || sp.candidates == null) return null;
        try {
            return sp.candidates.get();
        } catch (Throwable ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    // 电压文字颜色（照抄 GTM 自己的设定，不要自己编颜色）
    // ------------------------------------------------------------------ //

    /** 电压等级对应的 GT 文本颜色（{@code GTValues.VC}）。 */
    public static TextColor tierTextColor(int tier) {
        if (tier >= 0 && tier < GTValues.VC.length) {
            return TextColor.fromRgb(GTValues.VC[tier] & 0xFFFFFF);
        }
        return TextColor.fromRgb(0xFFFFFF);
    }
    /**
     * {@code GTValues.VC} 每一档对应的原版颜色码。
     *
     * <p><b>为什么需要这张表</b>：{@code GuiGraphics#drawString} 只认直接传进去的 ARGB，
     * <b>不会</b>用 {@code Component} 的 style 颜色（{@code Style} 的颜色只作用于
     * tooltip 等走 {@code Font} 富文本的路径）。所以电压文字要真的变色，
     * 必须把颜色作为参数传下去 / 用 {@code §} 码。</p>
     */
    private static final ChatFormatting[] TIER_FORMATTING = {
            ChatFormatting.DARK_RED,      // 0  ULV  #C80000
            ChatFormatting.GRAY,          // 1  LV   #DCDCDC
            ChatFormatting.GOLD,          // 2  MV   #FF6400
            ChatFormatting.YELLOW,        // 3  HV   #FFFF1E
            ChatFormatting.DARK_GRAY,     // 4  EV   #808080
            ChatFormatting.WHITE,         // 5  IV   #F0F0F5
            ChatFormatting.RED,           // 6  LuV  #E99797
            ChatFormatting.DARK_AQUA,     // 7  ZPM  #7EC3C4
            ChatFormatting.DARK_GREEN,    // 8  UV   #7EB07E
            ChatFormatting.LIGHT_PURPLE,  // 9  UHV  #BF74C0
            ChatFormatting.BLUE,          // 10 UEV  #0B5CFE
            ChatFormatting.DARK_PURPLE,   // 11 UIV  #914E91
            ChatFormatting.DARK_GREEN,    // 12 UXV  #488748
            ChatFormatting.DARK_RED,      // 13 OpV  #8C0000
            ChatFormatting.BLUE,          // 14 MAX  #2828F5
    };

    /**
     * 电压文字（带 {@code §} 颜色码的纯字符串，{@code Font} 一定会解析）。
     * OpV/MAX 额外带粗体码。
     */
    public static String tierColoredText(int tier) {
        if (tier < 0 || tier >= GTValues.VN.length || tier >= TIER_FORMATTING.length) return "?";
        String bold = tierIsBold(tier) ? "\u00a7l" : "";
        return "\u00a7" + TIER_FORMATTING[tier].getChar() + bold + GTValues.VN[tier];
    }

    /** 电压 chip 组件（style 颜色也设上，给 tooltip 等富文本路径用）。 */
    public static Component tierChip(int tier) {
        if (tier < 0 || tier >= GTValues.VN.length) {
            return Component.literal("?").withStyle(ChatFormatting.WHITE);
        }
        Style style = Style.EMPTY.withColor(tierTextColor(tier));
        if (tierIsBold(tier)) {
            style = style.withBold(true);
        }
        MutableComponent text = Component.literal(GTValues.VN[tier]);
        text.setStyle(style);
        return text;
    }

    /** OpV/MAX 在 GT 里是粗体（{@code GTValues.VNF} 里带 BOLD）。 */
    private static boolean tierIsBold(int tier) {
        if (tier >= 0 && tier < GTValues.VNF.length) {
            return GTValues.VNF[tier].indexOf(ChatFormatting.BOLD.getChar()) >= 0;
        }
        return false;
    }
}
