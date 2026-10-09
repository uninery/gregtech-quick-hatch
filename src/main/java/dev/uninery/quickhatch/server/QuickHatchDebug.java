package dev.uninery.quickhatch.server;

import com.mojang.brigadier.CommandDispatcher;
import dev.uninery.quickhatch.QuickHatch;
import dev.uninery.quickhatch.platform.HatchIndex;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 拉取链路自测（问题排查用）：
 * - 命令 /qhtest <item>：用 FakePlayer 模拟"物品在物品栏 → findAndExtract"全流程并输出分步日志。
 * - 系统属性 -Dquickhatch.selftest=true 时，服务器启动后自动对样例物品各跑一次。
 */
public final class QuickHatchDebug {

    private QuickHatchDebug() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("qhtest")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("item", com.mojang.brigadier.arguments.StringArgumentType.string())
                        .executes(ctx -> {
                            String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item");
                            runSelfTest(ctx.getSource().getLevel(), id);
                            return 1;
                        }))
                .then(Commands.literal("index").executes(ctx -> {
                    logMultiblockIndex();
                    return 1;
                }))
                .then(Commands.literal("ae").executes(ctx -> {
                    logAeIndex();
                    return 1;
                }))
                .then(Commands.literal("terminal").executes(ctx -> {
                    logTerminalProbe(ctx.getSource().getLevel());
                    return 1;
                })));
    }

    public static void runSelfTest(ServerLevel level, String itemId) {
        ResourceLocation rl;
        try {
            rl = new ResourceLocation(itemId);
        } catch (Exception ex) {
            QuickHatch.LOGGER.info("[selftest] invalid id: {}", itemId);
            return;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR || !rl.equals(BuiltInRegistries.ITEM.getKey(item))) {
            QuickHatch.LOGGER.info("[selftest] unknown item: {} (resolved AIR)", itemId);
            return;
        }
        var fake = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
        fake.getInventory().clearContent();

        // 场景 1：物品直接在物品栏
        boolean added = fake.getInventory().add(new ItemStack(item, 3));
        int got = PullService.findAndExtract(fake, item, 1);
        QuickHatch.LOGGER.info("[selftest] #1 plain inventory: added={} extracted={} remainingInInv={}",
                added, got, countInInv(fake, item));

        // 场景 2：物品在一个带 ITEM_HANDLER 能力的容器里（原版潜影盒即有该能力）
        fake.getInventory().clearContent();
        ItemStack shulker = new ItemStack(net.minecraft.world.level.block.Blocks.SHULKER_BOX);
        var cap = shulker.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER);
        cap.ifPresent(handler -> handler.insertItem(0, new ItemStack(item, 5), false));
        boolean backpackAdded = fake.getInventory().add(shulker);
        int got2 = PullService.findAndExtract(fake, item, 1);
        QuickHatch.LOGGER.info("[selftest] #2 shulker container: added={} extracted={} capPresent={}",
                backpackAdded, got2, cap.isPresent());

        fake.getInventory().clearContent();

        // 场景 3：索引分类自检（分类 / 电压 / 电流 / 输入输出 / 能量子类）
        logIndex();
        // 场景 3b：AE 分类与子分类（方块 / 线缆）明细
        logAeIndex();
        // 场景 3c：无线终端定位（必须能区分"真无线终端"和"便携元件/网络工具"）
        logTerminalProbe(level);
        // 场景 3d：替换流程（左键 = 原地替换 + 旧方块还给玩家）
        logReplaceProbe(level);
        // 场景 3e：已放置仓室的替换 + 样板仓室黑名单 + 拉取不吞物品
        logPlacedHatchProbes(level);
        // 场景 3f：分类改动（蒸汽档 / 中子加速器 / 其他）+ 创造模式行为
        logCategoryProbes(level);
        // 场景 4：全局多方块索引自检（哪些方块种类可以被替换为仓室）
        logMultiblockIndex();
    }

    /**
     * 分类改动自检：
     * <ul>
     *   <li>蒸汽：<b>没有</b> {@code STEAM} 分类；大型/特大/巨型蒸汽输入输出仓都在
     *       <b>能量仓</b>分类 + <b>蒸汽</b>电压档；</li>
     *   <li>中子加速器：{@code *_neutron_accelerator} 归 {@code NEUTRON_ACCELERATOR}；</li>
     *   <li>其他：方块总线 / 中子传感器 / 嬗变总线 / 物料谱解析仓 / 万象转录节点
     *       归 {@code MISC}（装了对应模组才有，缺的列出来）；</li>
     *   <li>创造模式：拉取凭空生成、替换不消耗物品。</li>
     * </ul>
     */
    public static void logCategoryProbes(ServerLevel level) {
        // a) 蒸汽：不应再有 STEAM 分类；蒸汽仓（大型/特大/巨型）要在能量仓 + 蒸汽电压档
        List<String> steamProblems = new ArrayList<>();
        List<String> steamAbsent = new ArrayList<>();
        for (String id : List.of("gtceu:large_steam_input_hatch", "gtceu:mega_steam_input_hatch",
                "gtceu:mega_steam_output_hatch", "gtladditions:huge_steam_input_hatch")) {
            if (itemOf(id) == null) {
                steamAbsent.add(id);
                continue;
            }
            HatchIndex.Entry e = entryOf(id);
            if (e == null) {
                steamProblems.add(id + "=missing");
            } else if (e.category() != HatchIndex.Category.ENERGY_HATCH || e.tier() != HatchIndex.TIER_STEAM) {
                steamProblems.add(id + "=" + e.category() + "/tier" + e.tier());
            }
        }
        List<Integer> tiers = HatchIndex.tiers();
        boolean steamFirst = !tiers.isEmpty() && tiers.get(0) == HatchIndex.TIER_STEAM;
        List<String> steamTierIds = new ArrayList<>();
        List<String> steamNotTiered = new ArrayList<>();
        for (HatchIndex.Entry e : HatchIndex.get()) {
            String path = e.id().getPath();
            boolean steamish = path.contains("steam") && (path.endsWith("_hatch") || path.endsWith("_bus"));
            if (e.tier() == HatchIndex.TIER_STEAM) steamTierIds.add(path);
            // 完整性：索引里所有蒸汽仓/总线都必须在"蒸汽"档里 —— 否则电压列的"蒸汽"筛选就是漏的
            if (steamish && e.tier() != HatchIndex.TIER_STEAM) {
                steamNotTiered.add(e.id() + "=tier" + e.tier());
            }
        }
        boolean noSteamCategory = !hasCategory("STEAM");
        QuickHatch.LOGGER.info("[selftest] category/steam: problems={} absent={} steamTierFirst={} "
                        + "steamCategoryRemoved={} steamTierEntries={} notTiered={} tiers={} -> {}",
                steamProblems.isEmpty() ? "(none)" : steamProblems, steamAbsent, steamFirst, noSteamCategory,
                steamTierIds, steamNotTiered.isEmpty() ? "(none)" : steamNotTiered,
                tiers.subList(0, Math.min(4, tiers.size())),
                (steamProblems.isEmpty() && noSteamCategory && steamNotTiered.isEmpty()
                        && (steamFirst || steamAbsent.size() == 4)) ? "PASS" : "FAIL");

        // b) 中子加速器：注册表里所有 *_neutron_accelerator 都该归 NEUTRON_ACCELERATOR
        List<String> neutronWrong = new ArrayList<>();
        int neutron = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (!id.getPath().endsWith("neutron_accelerator")) continue;
            neutron++;
            HatchIndex.Entry e = entryOf(id.toString());
            if (e == null || e.category() != HatchIndex.Category.NEUTRON_ACCELERATOR) {
                neutronWrong.add(id + "=" + (e == null ? "missing" : e.category()));
            }
        }
        QuickHatch.LOGGER.info("[selftest] category/neutron-accelerator: found={} wrong={} -> {}",
                neutron, neutronWrong.isEmpty() ? "(none)" : neutronWrong,
                neutronWrong.isEmpty() ? "PASS" : "FAIL");

        // c) 其他：表里"本分支装了"的那些必须都在 MISC 分类里（没装的列出来）
        List<String> miscWrong = new ArrayList<>();
        List<String> miscAbsent = new ArrayList<>();
        List<String> miscOk = new ArrayList<>();
        for (String id : List.of("gtceu:block_bus", "gtceu:neutron_sensor",
                "gtladditions:me_block_conservation", "gtladditions:spectral_analysis_hatch",
                "gtladditions:vientiane_transcription_node")) {
            if (itemOf(id) == null) {
                miscAbsent.add(id);
                continue;
            }
            HatchIndex.Entry e = entryOf(id);
            if (e == null || e.category() != HatchIndex.Category.MISC) {
                miscWrong.add(id + "=" + (e == null ? "missing" : e.category()));
            } else {
                miscOk.add(id);
            }
        }
        QuickHatch.LOGGER.info("[selftest] category/misc: ok={} wrong={} not-installed={} -> {}",
                miscOk, miscWrong.isEmpty() ? "(none)" : miscWrong, miscAbsent,
                miscWrong.isEmpty() ? "PASS" : "FAIL");

        logClassifyRules();
        logSubFilterProbes();
        logReplaceBoundary();
        logCreativeProbes(level);
    }

    /**
     * 子筛选自检（用户第二十三轮要求给总线/流体仓加子分类）：
     * 每个分类的子选项必须<b>不重不漏</b>地覆盖该类目下的所有记录 ——
     * 每条记录正好命中一个选项（"普通"负责兜住剩下的），
     * 这样界面上随便点哪一档筛出来的东西都是对的。
     */
    private static void logSubFilterProbes() {
        List<String> problems = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (HatchIndex.Category category : HatchIndex.Category.values()) {
            List<HatchIndex.SubOption> options = HatchIndex.subOptionsOf(category);
            if (options.isEmpty()) continue;
            int[] hits = new int[options.size()];
            for (HatchIndex.Entry e : HatchIndex.get()) {
                if (e.category() != category) continue;
                int matched = 0;
                for (int i = 0; i < options.size(); i++) {
                    if (options.get(i).available() && options.get(i).matches(e)) {
                        matched++;
                        hits[i]++;
                    }
                }
                if (matched != 1) {
                    problems.add(e.id() + "->" + matched + " 个子分类");
                }
            }
            summary.append(category.key).append('=').append(java.util.Arrays.toString(hits)).append(' ');
        }
        // 分类规则自检里已经确认过：变电站仓不能落在流体仓里
        List<String> fluidSuspicious = new ArrayList<>();
        for (HatchIndex.Entry e : HatchIndex.get()) {
            if (e.category() != HatchIndex.Category.FLUID_HATCH) continue;
            String path = e.id().getPath();
            if (path.contains("energy") || path.contains("substation") || path.contains("laser")
                    || (path.contains("hatch") && HatchIndex.amperageOfPublic(path) > 1)) {
                fluidSuspicious.add(e.id().toString());
            }
        }
        QuickHatch.LOGGER.info("[selftest] sub-filters: {} problems={} suspicious-fluid-hatches={} -> {}",
                summary.toString().trim(), problems.isEmpty() ? "(none)" : problems,
                fluidSuspicious.isEmpty() ? "(none)" : fluidSuspicious,
                (problems.isEmpty() && fluidSuspicious.isEmpty()) ? "PASS" : "FAIL");
    }

    /**
     * 分类规则自检（纯 id 判定，<b>不依赖那些模组是否装在开发环境里</b>）：
     * 蒸汽仓 → 能量仓 + 蒸汽档；中子加速器 → 中子加速器分类；
     * 方块总线 / 嬗变总线 / 物料谱解析仓 / 万象转录节点 / 中子传感器 → 其他。
     */
    private static void logClassifyRules() {
        record Rule(String id, HatchIndex.Category category) {}
        List<Rule> rules = List.of(
                new Rule("gtceu:large_steam_input_hatch", HatchIndex.Category.ENERGY_HATCH),
                new Rule("gtceu:mega_steam_input_hatch", HatchIndex.Category.ENERGY_HATCH),
                new Rule("gtceu:mega_steam_output_hatch", HatchIndex.Category.ENERGY_HATCH),
                new Rule("gtladditions:huge_steam_input_hatch", HatchIndex.Category.ENERGY_HATCH),
                // 蒸汽总线是"总线"，不是能量仓（用户第二十一轮纠正）；电压档仍是"蒸汽"（第二十二轮）
                new Rule("gtceu:steam_input_bus", HatchIndex.Category.BUS),
                new Rule("gtceu:steam_output_bus", HatchIndex.Category.BUS),
                // 通行仓：物品→总线、流体→流体仓（第二十二轮）
                new Rule("gtceu:lv_item_passthrough_hatch", HatchIndex.Category.BUS),
                new Rule("gtceu:lv_fluid_passthrough_hatch", HatchIndex.Category.FLUID_HATCH),
                // 巨型输入/输出总线（GTM Things 的 *_huge_item_import_bus_* / export，tier 在后缀）
                new Rule("gtmthings:huge_item_import_bus_lv", HatchIndex.Category.BUS),
                new Rule("gtmthings:huge_item_export_bus_lv", HatchIndex.Category.BUS),
                // 变电站能源仓/动力仓：id 里没有 energy，但是能量仓（第二十三轮：以前跑流体仓去了）
                new Rule("gtceu:ev_substation_input_hatch_64a", HatchIndex.Category.ENERGY_HATCH),
                new Rule("gtceu:ev_substation_output_hatch_64a", HatchIndex.Category.ENERGY_HATCH),
                // 多重流体仓仍然是流体仓（4x / 9x，没有电流标记，不受上面的电流兜底影响）
                new Rule("gtceu:ev_input_hatch_4x", HatchIndex.Category.FLUID_HATCH),
                new Rule("gtceu:max_input_hatch_9x", HatchIndex.Category.FLUID_HATCH),
                // 焦炉仓：名字带 hatch 但谁也管不着 → 兜底进"其他"
                new Rule("gtceu:coke_oven_hatch", HatchIndex.Category.MISC),
                new Rule("gtceu:lv_neutron_accelerator", HatchIndex.Category.NEUTRON_ACCELERATOR),
                new Rule("gtceu:max_neutron_accelerator", HatchIndex.Category.NEUTRON_ACCELERATOR),
                new Rule("gtceu:block_bus", HatchIndex.Category.MISC),
                new Rule("gtceu:neutron_sensor", HatchIndex.Category.MISC),
                new Rule("gtladditions:me_block_conservation", HatchIndex.Category.MISC),
                new Rule("gtladditions:spectral_analysis_hatch", HatchIndex.Category.MISC),
                new Rule("gtladditions:vientiane_transcription_node", HatchIndex.Category.MISC));
        List<String> wrong = new ArrayList<>();
        for (Rule rule : rules) {
            HatchIndex.Category actual = HatchIndex.categorizeId(rule.id());
            if (actual != rule.category()) {
                wrong.add(rule.id() + "=" + actual);
            }
        }
        // 蒸汽总线的输入输出角色（用户要求它们能被"输入/输出"筛选）
        boolean steamBusIo = HatchIndex.ioRoleOfPublic("steam_input_bus", HatchIndex.Category.BUS)
                == HatchIndex.IoRole.INPUT
                && HatchIndex.ioRoleOfPublic("steam_output_bus", HatchIndex.Category.BUS)
                == HatchIndex.IoRole.OUTPUT;
        // 巨型输入/输出总线（import/export 命名）也要有输入输出角色
        boolean hugeBusIo = HatchIndex.ioRoleOfPublic("huge_item_import_bus_lv", HatchIndex.Category.BUS)
                == HatchIndex.IoRole.INPUT
                && HatchIndex.ioRoleOfPublic("huge_item_export_bus_lv", HatchIndex.Category.BUS)
                == HatchIndex.IoRole.OUTPUT;
        // 电压档：蒸汽仓与蒸汽总线都算"蒸汽"（这样电压列的"蒸汽"筛选能把它们都筛出来）；
        // 通行仓/巨型总线用它们自己的 GT 电压；"其他"没有电压档
        boolean steamTier = HatchIndex.tierOf(HatchIndex.Category.ENERGY_HATCH,
                "large_steam_input_hatch", 1) == HatchIndex.TIER_STEAM
                && HatchIndex.tierOf(HatchIndex.Category.BUS, "steam_input_bus", 1) == HatchIndex.TIER_STEAM
                && HatchIndex.tierOf(HatchIndex.Category.FLUID_HATCH, "lv_fluid_passthrough_hatch", 1) == 1
                && HatchIndex.tierOf(HatchIndex.Category.BUS, "huge_item_import_bus_lv", 1) == 1
                && HatchIndex.tierOf(HatchIndex.Category.MISC, "block_bus", 1) == -1;
        QuickHatch.LOGGER.info("[selftest] classify rules: {} cases wrong={} steamBusIo={} hugeBusIo={} "
                        + "tierRules={} -> {}",
                rules.size(), wrong.isEmpty() ? "(none)" : wrong, steamBusIo, hugeBusIo, steamTier,
                (wrong.isEmpty() && steamBusIo && hugeBusIo && steamTier) ? "PASS" : "FAIL");
        logAmperageProbe();
    }

    /** 电流档自检（普通能源仓/动力仓 = 2A，无线版 = 1A，带标记的按标记）。 */
    private static void logAmperageProbe() {
        boolean ampRules = HatchIndex.amperageOfPublic("lv_energy_input_hatch") == 2
                && HatchIndex.amperageOfPublic("max_energy_output_hatch") == 2
                && HatchIndex.amperageOfPublic("lv_energy_input_hatch_4a") == 4
                && HatchIndex.amperageOfPublic("ev_substation_input_hatch_64a") == 64
                && HatchIndex.amperageOfPublic("wireless_energy_input_hatch") == 1
                && HatchIndex.amperageOfPublic("lv_input_bus") == 1;
        // 数据面：索引里每个"基础能源仓/动力仓"都必须是 2A
        List<String> ampWrong = new ArrayList<>();
        for (HatchIndex.Entry e : HatchIndex.get()) {
            String path = e.id().getPath();
            if (e.category() != HatchIndex.Category.ENERGY_HATCH) continue;
            if (HatchIndex.isStandardTwoAmpEnergyHatch(path) && e.amperage() != 2) {
                ampWrong.add(e.id() + "=" + e.amperage() + "A");
            }
        }
        QuickHatch.LOGGER.info("[selftest] amperage: ruleCases={} indexWrong={} -> {}",
                ampRules, ampWrong.isEmpty() ? "(none)" : ampWrong,
                (ampRules && ampWrong.isEmpty()) ? "PASS" : "FAIL");
    }

    /**
     * 替换目标的边界自检（用户第二十一轮：<b>不是界面里所有方块都能触发替换</b>）：
     * 线缆 / 管道 / AE 方块都不该是可替换目标，而普通仓室（输入总线）与焦炉仓应该是。
     */
    private static void logReplaceBoundary() {
        List<String> leaked = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        for (HatchIndex.Entry e : HatchIndex.get()) {
            if (!(e.item() instanceof net.minecraft.world.item.BlockItem blockItem)) continue;
            boolean replaceable = dev.uninery.quickhatch.platform.MultiblockRegistry
                    .isReplaceable(blockItem.getBlock());
            if (!HatchIndex.isHatchCategory(e.category()) && replaceable) {
                leaked.add(e.id().toString());
            }
            // 各挑一个样本，方便日志里看：一条线缆、一条管道
            if (samples.size() < 2 && (e.category() == HatchIndex.Category.CABLE
                    || e.category() == HatchIndex.Category.PIPE)) {
                samples.add(e.id().getPath() + "=" + replaceable);
            }
        }
        List<String> hatchExamples = new ArrayList<>();
        for (String id : List.of("gtceu:lv_input_bus", "gtceu:coke_oven_hatch")) {
            HatchIndex.Entry e = entryOf(id);
            if (e == null) continue;
            hatchExamples.add(id + "=" + dev.uninery.quickhatch.platform.MultiblockRegistry
                    .isReplaceable(((net.minecraft.world.item.BlockItem) e.item()).getBlock()));
        }
        boolean hatchesOk = !hatchExamples.isEmpty()
                && hatchExamples.stream().allMatch(s -> s.endsWith("=true"));
        QuickHatch.LOGGER.info("[selftest] replace boundary: non-hatch leaked={} sample(cable/pipe)={} "
                        + "hatches={} -> {}",
                leaked.isEmpty() ? "(none)" : leaked, samples, hatchExamples,
                (leaked.isEmpty() && hatchesOk) ? "PASS" : "FAIL");

        // 索引覆盖率：名字像仓/总线的机器有没有被漏掉（焦炉仓就是这么被抓出来的）
        List<String> missing = new ArrayList<>();
        for (var entry : com.gregtechceu.gtceu.api.registry.GTRegistries.MACHINES.entries()) {
            if (entry.getValue() instanceof com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition) {
                continue;
            }
            String path = entry.getKey().getPath();
            if (!path.endsWith("_hatch") && !path.endsWith("_bus")) continue;
            if (entryOf(entry.getKey().toString()) == null) missing.add(entry.getKey().toString());
        }
        QuickHatch.LOGGER.info("[selftest] index coverage: hatch/bus machines not indexed={} -> {}",
                missing.isEmpty() ? "(none)" : missing, missing.isEmpty() ? "PASS" : "FAIL");
    }

    /** 创造模式：拉取凭空生成（网络里没有也能给），替换不消耗物品。 */
    private static void logCreativeProbes(ServerLevel level) {
        Item hatch = itemOf("gtceu:lv_input_bus");
        Item other = itemOf("gtceu:lv_output_bus");
        if (hatch == null || other == null) {
            QuickHatch.LOGGER.info("[selftest] creative probe skipped: bus items missing");
            return;
        }
        var fake = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
        fake.getInventory().clearContent();
        fake.getInventory().selected = 0;

        // a) 拉取：身上、背包、网络里全都没有 → 创造模式直接凭空变出来
        //    （创造开关直接传进去：FakePlayer 的 isCreative() 不可靠，这里 fake.isCreative()=… 只作参考）
        boolean nothingBefore = countInInv(fake, hatch) == 0;
        PullService.handle(new dev.uninery.quickhatch.network.ServerboundPullItemPacket(
                BuiltInRegistries.ITEM.getKey(hatch), 64, true), fake, true);
        ItemStack got = fake.getInventory().getItem(0);
        boolean created = nothingBefore && got.is(hatch)
                && got.getCount() == Math.max(1, new ItemStack(hatch).getMaxStackSize());

        // b) 替换：手里没有仓室也能换，且不消耗任何东西、旧方块也不返还（原版创造模式不掉落）
        BlockPos base = level.getSharedSpawnPos();
        level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        BlockPos pos = base.offset(7, 0, 1);
        BlockState oldState = level.getBlockState(pos);
        level.setBlockAndUpdate(pos, ((net.minecraft.world.item.BlockItem) other)
                .getBlock().defaultBlockState());
        fake.getInventory().clearContent();
        int before = countInInv(fake, hatch);
        ReplaceService.handle(new dev.uninery.quickhatch.network.ServerboundReplaceHatchPacket(
                BuiltInRegistries.ITEM.getKey(hatch), pos), fake, true);
        boolean swapped = level.getBlockState(pos).getBlock()
                == ((net.minecraft.world.item.BlockItem) hatch).getBlock();
        boolean notConsumed = countInInv(fake, hatch) == before;
        boolean notGivenBack = countInInv(fake, other) == 0;

        QuickHatch.LOGGER.info("[selftest] creative: pull-created={} ({}x{}) replace-swapped={} "
                        + "hatchNotConsumed={} oldBlockNotReturned={} (fake.isCreative={}) -> {}",
                created, got.is(hatch) ? got.getCount() : 0, BuiltInRegistries.ITEM.getKey(hatch),
                swapped, notConsumed, notGivenBack, fake.isCreative(),
                (created && swapped && notConsumed && notGivenBack) ? "PASS" : "FAIL");

        level.setBlockAndUpdate(pos, oldState);
        fake.getInventory().clearContent();
    }

    private static boolean hasCategory(String name) {
        for (HatchIndex.Category c : HatchIndex.Category.values()) {
            if (c.name().equals(name)) return true;
        }
        return false;
    }

    /** 索引里按 id 找一条（找不到返回 null）。 */
    private static HatchIndex.Entry entryOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        for (HatchIndex.Entry e : HatchIndex.get()) {
            if (e.id().equals(rl)) return e;
        }
        return null;
    }

    /**
     * 替换目标与拉取的两组回归断言：
     * <ul>
     *   <li><b>已放置的仓室也能替换</b>（用户：Ctrl+左键点已放好的仓室也要能换），
     *       并且换完<b>不动手上那一格</b>（否则会播"右手消失再伸出来"的换物动画）；</li>
     *   <li><b>能放样板的仓室</b>（样板总成 / 样板供应器…）不在可替换集合里；</li>
     *   <li><b>拉取不吞物品</b>：快捷栏已有同类堆叠时，拉取只是把它补满，总数不变。</li>
     * </ul>
     */
    public static void logPlacedHatchProbes(ServerLevel level) {
        Item from = itemOf("gtceu:lv_input_bus");
        Item to = itemOf("gtceu:lv_output_bus");
        if (from == null || to == null) {
            QuickHatch.LOGGER.info("[selftest] placed-hatch probe skipped: bus items missing");
        } else {
            BlockPos base = level.getSharedSpawnPos();
            level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
            BlockPos pos = base.offset(6, 0, 1);
            BlockState oldState = level.getBlockState(pos);
            var fake = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
            fake.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
            logPlacedHatchReplace(level, fake, pos, from, to);
            level.setBlockAndUpdate(pos, oldState);
            logPullNoSwallow(fake, from);
            fake.getInventory().clearContent();
        }
        logPatternBlacklist(from);
    }

    /**
     * 已放置的仓室也能被替换，而且**不许动手上那一格**。
     *
     * <p>地上放一个输入总线，手上（选中格）放三块石头，背包里放一个输出总线；
     * 替换成功后：方块变成输出总线、输入总线回到身上、选中格仍然是那三块石头。</p>
     */
    private static void logPlacedHatchReplace(ServerLevel level, ServerPlayer fake, BlockPos pos,
                                             Item from, Item to) {
        if (!(from instanceof net.minecraft.world.item.BlockItem fromBlock)
                || !(to instanceof net.minecraft.world.item.BlockItem toBlock)) {
            QuickHatch.LOGGER.info("[selftest] placed-hatch replace skipped: not block items");
            return;
        }
        level.setBlockAndUpdate(pos, fromBlock.getBlock().defaultBlockState());
        fake.getInventory().clearContent();
        fake.getInventory().selected = 0;
        fake.getInventory().setItem(0, new ItemStack(Items.STONE, 3));
        fake.getInventory().setItem(5, new ItemStack(to, 2));

        boolean replaceable = dev.uninery.quickhatch.platform.MultiblockRegistry.isReplaceable(fromBlock.getBlock());
        int toBefore = countInInv(fake, to);
        ReplaceService.handle(new dev.uninery.quickhatch.network.ServerboundReplaceHatchPacket(
                BuiltInRegistries.ITEM.getKey(to), pos), fake);
        BlockState now = level.getBlockState(pos);
        boolean swapped = now.getBlock() == toBlock.getBlock();
        boolean consumed = countInInv(fake, to) == toBefore - 1;
        boolean returned = countInInv(fake, from) > 0;
        ItemStack held = fake.getInventory().getItem(0);
        boolean heldUntouched = held.is(Items.STONE) && held.getCount() == 3;

        QuickHatch.LOGGER.info("[selftest] placed-hatch replace at {}: isReplaceable={} {} -> {} "
                        + "swapped={} consumed={} oldReturned={} heldSlotUntouched={} -> {}",
                pos, replaceable, BuiltInRegistries.ITEM.getKey(from), BuiltInRegistries.ITEM.getKey(to),
                swapped, consumed, returned, heldUntouched,
                (replaceable && swapped && consumed && returned && heldUntouched) ? "PASS" : "FAIL");
    }

    /**
     * 能放样板的仓室（样板总成 / 样板供应器…）必须**不在**可替换集合里，
     * 而普通仓室（输入总线）必须**在**里面。
     */
    private static void logPatternBlacklist(Item hatch) {
        List<String> leaked = new ArrayList<>();
        boolean hatchReplaceable = hatch instanceof net.minecraft.world.item.BlockItem blockItem
                && dev.uninery.quickhatch.platform.MultiblockRegistry.isReplaceable(blockItem.getBlock());
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof net.minecraft.world.item.BlockItem block)) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (!dev.uninery.quickhatch.platform.HatchIndex.holdsPatterns(id)) continue;
            if (dev.uninery.quickhatch.platform.MultiblockRegistry.isReplaceable(block.getBlock())) {
                leaked.add(id.toString());
            }
        }
        QuickHatch.LOGGER.info("[selftest] replace targets: plain hatch replaceable={} "
                        + "pattern-holding leaked={} -> {}",
                hatchReplaceable, leaked.isEmpty() ? "(none)" : leaked,
                (hatchReplaceable && leaked.isEmpty()) ? "PASS" : "FAIL");
    }

    /**
     * 拉取回归：快捷栏里已有同类物品时，拉取**不能把物品吞掉**
     * （1.0.17 的写法抽完不放进堆叠，物品直接消失），应当是把背包里的补进那一格。
     */
    private static void logPullNoSwallow(ServerPlayer fake, Item item) {
        fake.getInventory().clearContent();
        fake.getInventory().selected = 0;
        fake.getInventory().setItem(0, new ItemStack(item, 1));
        fake.getInventory().setItem(10, new ItemStack(item, 20));
        int before = countInInv(fake, item);
        PullService.handle(new dev.uninery.quickhatch.network.ServerboundPullItemPacket(
                BuiltInRegistries.ITEM.getKey(item), 64, true), fake);
        int after = countInInv(fake, item);
        ItemStack hotbar = fake.getInventory().getItem(0);
        boolean merged = hotbar.is(item) && hotbar.getCount() == before;

        QuickHatch.LOGGER.info("[selftest] pull-no-swallow: before={} after={} hotbarSlot={} -> {}",
                before, after, hotbar.getCount(),
                (after == before && merged) ? "PASS" : "FAIL (items lost or not merged)");
    }

    /**
     * 替换流程自检（服务端那一半）：在一个"能放仓室的方块"上跑一次替换，
     * 断言三件事 —— 方块换成了仓室、仓室物品被扣掉 1 个、原方块回到了玩家身上。
     *
     * <p>用 {@code gtceu:inert_machine_casing}（大型搅拌罐那种仓室槽位方块）当靶子，
     * 拿 {@code gtceu:lv_input_bus} 当仓室。</p>
     */
    public static void logReplaceProbe(ServerLevel level) {
        var casing = BuiltInRegistries.BLOCK.get(new ResourceLocation("gtceu", "inert_machine_casing"));
        Item hatch = itemOf("gtceu:lv_input_bus");
        if (casing == net.minecraft.world.level.block.Blocks.AIR || hatch == null) {
            QuickHatch.LOGGER.info("[selftest] replace probe skipped: blocks/items missing");
            return;
        }
        if (!dev.uninery.quickhatch.platform.MultiblockRegistry.isReplaceable(casing)) {
            QuickHatch.LOGGER.info("[selftest] replace probe skipped: {} is not replaceable",
                    BuiltInRegistries.BLOCK.getKey(casing));
            return;
        }
        BlockPos base = level.getSharedSpawnPos();
        level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        BlockPos pos = base.offset(6, 0, 0);
        BlockState oldState = level.getBlockState(pos);

        var fake = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
        fake.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        level.setBlockAndUpdate(pos, casing.defaultBlockState());
        fake.getInventory().clearContent();
        fake.getInventory().setItem(0, new ItemStack(hatch, 2));

        int before = countInInv(fake, hatch);
        ReplaceService.handle(new dev.uninery.quickhatch.network.ServerboundReplaceHatchPacket(
                BuiltInRegistries.ITEM.getKey(hatch), pos), fake);
        BlockState after = level.getBlockState(pos);
        boolean placed = after.getBlock() != casing;
        boolean consumed = countInInv(fake, hatch) == before - 1;
        boolean returned = countInInv(fake, casing.asItem()) > 0;

        QuickHatch.LOGGER.info("[selftest] replace probe at {}: target={} -> {} placed={} "
                        + "hatchConsumed={} oldBlockReturned={} -> {}",
                pos, BuiltInRegistries.BLOCK.getKey(casing), BuiltInRegistries.BLOCK.getKey(after.getBlock()),
                placed, consumed, returned, (placed && consumed && returned) ? "PASS" : "FAIL");

        fake.getInventory().clearContent();
        level.setBlockAndUpdate(pos, oldState);
    }

    private static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item == Items.AIR ? null : item;
    }

    /**
     * 打印全局多方块索引的统计与关键条目，用来验证"某个方块种类能不能在某种
     * 多方块结构里被替换为仓室"的判定（需求 1/2 的核心）。
     * 同时把<b>没有被收进索引</b>的机器里名字带 hatch/bus/fluid 的列出来，
     * 方便找出"该在列表里却不在"的仓室。
     */
    public static void logMultiblockIndex() {
        var registry = dev.uninery.quickhatch.platform.MultiblockRegistry.replaceableBlocks();
        QuickHatch.LOGGER.info("[selftest] multiblock index: {} replaceable block kinds", registry.size());
        int shown = 0;
        for (var block : registry) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            var allowed = dev.uninery.quickhatch.platform.MultiblockRegistry.allowedHatches(block);
            QuickHatch.LOGGER.debug("[selftest] replaceable {} -> {} hatches", id, allowed.size());
            if (shown < 40 || id.getPath().contains("stirring") || id.getPath().contains("gearbox")
                    || id.getPath().contains("casing")) {
                QuickHatch.LOGGER.info("[selftest] replaceable-block {} (allowedHatches={})",
                        id, allowed.size());
                shown++;
            }
        }
        // 索引/分类里缺失的仓室候选：注册了但没被识别成仓室的机器
        StringBuilder missing = new StringBuilder();
        int missingCount = 0;
        for (var e : com.gregtechceu.gtceu.api.registry.GTRegistries.MACHINES.entries()) {
            String path = e.getKey().getPath();
            if (!(path.contains("hatch") || path.contains("bus"))) continue;
            var category = dev.uninery.quickhatch.platform.HatchIndex.categorizeMachinePublic(path);
            if (category != null) continue;
            missingCount++;
            if (missingCount <= 60) missing.append(e.getKey()).append(' ');
        }
        QuickHatch.LOGGER.info("[selftest] machines NOT indexed ({}): {}", missingCount, missing.toString().trim());
    }

    /**
     * 打印索引里各分类的条目数与关键条目明细，用于验证分类、电压颜色、电流解析、
     * 输入输出角色与"靶仓/源仓"补全是否正确。
     */
    public static void logIndex() {
        var all = dev.uninery.quickhatch.platform.HatchIndex.get();
        QuickHatch.LOGGER.info("[selftest] index total={}", all.size());
        java.util.Map<dev.uninery.quickhatch.platform.HatchIndex.Category, Integer> byCat =
                new java.util.EnumMap<>(dev.uninery.quickhatch.platform.HatchIndex.Category.class);
        for (var e : all) {
            byCat.merge(e.category(), 1, Integer::sum);
        }
        for (var entry : byCat.entrySet()) {
            QuickHatch.LOGGER.info("[selftest] category {} = {}", entry.getKey(), entry.getValue());
        }
        QuickHatch.LOGGER.info("[selftest] io roles = {}",
                dev.uninery.quickhatch.platform.HatchIndex.ioRoles());
        QuickHatch.LOGGER.info("[selftest] tiers = {}",
                dev.uninery.quickhatch.platform.HatchIndex.tiers());
        QuickHatch.LOGGER.info("[selftest] amperages = {}",
                dev.uninery.quickhatch.platform.HatchIndex.amperages());
        // 电压颜色链路自检（GTValues.VC 取值 + 组件的颜色/粗体样式）
        StringBuilder colors = new StringBuilder();
        for (int tier : dev.uninery.quickhatch.platform.HatchIndex.tiers()) {
            var chip = dev.uninery.quickhatch.platform.MultiblockRegistry.tierChip(tier);
            var style = chip.getStyle();
            colors.append(tier).append(':').append(chip.getString())
                    .append("#").append(style.getColor() == null ? "-" : style.getColor().serialize())
                    .append(style.isBold() ? "!" : "").append(' ');
        }
        QuickHatch.LOGGER.info("[selftest] tier colors = {}", colors.toString().trim());
        for (var e : all) {
            if (e.category() == dev.uninery.quickhatch.platform.HatchIndex.Category.CABLE
                    || e.category() == dev.uninery.quickhatch.platform.HatchIndex.Category.PIPE
                    || e.category() == dev.uninery.quickhatch.platform.HatchIndex.Category.AE) {
                continue;
            }
            // 明细走 debug 级别，避免默认日志被刷屏（需要时开 debug 或查 latest.log）
            QuickHatch.LOGGER.debug("[selftest] {} -> cat={} tier={} amp={} energy={} io={}",
                    e.id(), e.category(), e.tier(), e.amperage(), e.energyType(), e.io());
        }
    }

    /**
     * AE 分类自检：把 AE 分类按子分类（面板 / 方块 / 线缆）分组打印，
     * 用来核对"哪些物品落在哪一档"（AE2 未安装时 AE 分类为空属正常）。
     */
    public static void logAeIndex() {
        var ae = dev.uninery.quickhatch.platform.HatchIndex.Category.AE;
        java.util.Map<dev.uninery.quickhatch.platform.HatchIndex.AeSubtype, java.util.List<String>> bySub =
                new java.util.EnumMap<>(dev.uninery.quickhatch.platform.HatchIndex.AeSubtype.class);
        for (var e : dev.uninery.quickhatch.platform.HatchIndex.get()) {
            if (e.category() != ae) continue;
            bySub.computeIfAbsent(e.aeSubtype(), k -> new java.util.ArrayList<>()).add(e.id().toString());
        }
        QuickHatch.LOGGER.info("[selftest] AE category: {} items in {} subtypes",
                bySub.values().stream().mapToInt(java.util.List::size).sum(), bySub.size());
        for (var entry : bySub.entrySet()) {
            java.util.List<String> ids = entry.getValue();
            QuickHatch.LOGGER.info("[selftest] AE/{} = {} :: {}", entry.getKey(), ids.size(), ids);
        }
    }

    /**
     * 无线终端定位自检（中键下单"未连接 / 超出距离"的回归测试）。
     *
     * <p>核心断言：玩家身上带着<b>便携元件</b>（也是 {@code IMenuItem}，但没有网络节点）时，
     * 中键下单必须报"未找到无线终端"，不能拿便携元件的宿主去要网络、然后报
     * "无线终端未链接或超出范围"。以前就是这个原因，中键下单永远失败。</p>
     */
    public static void logTerminalProbe(ServerLevel level) {
        if (!dev.uninery.quickhatch.platform.AE2Compat.isAvailable()) {
            QuickHatch.LOGGER.info("[selftest] AE2 not installed: terminal probe skipped");
            return;
        }
        if (level == null) return;
        var fake = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
        Item cell = itemOf("ae2:portable_item_cell_1k");
        Item terminal = itemOf("ae2:wireless_terminal");
        Item probe = itemOf("gtceu:lv_input_bus");

        fake.getInventory().clearContent();
        QuickHatch.LOGGER.info("[selftest] terminal probe empty inv: hasTerminal={} craft={}",
                dev.uninery.quickhatch.platform.AE2Compat.hasTerminal(fake),
                dev.uninery.quickhatch.platform.AE2Compat.openCraftAmountMenu(fake, probe));

        if (cell != null) {
            fake.getInventory().clearContent();
            fake.getInventory().setItem(0, new ItemStack(cell));
            QuickHatch.LOGGER.info("[selftest] terminal probe portable-cell-only inv: "
                            + "hasTerminal={} craft={} (must be no_terminal)",
                    dev.uninery.quickhatch.platform.AE2Compat.hasTerminal(fake),
                    dev.uninery.quickhatch.platform.AE2Compat.openCraftAmountMenu(fake, probe));
        }

        if (terminal != null && cell != null) {
            // 便携元件排在无线终端前面：旧代码会抓错宿主，新代码必须仍然认出无线终端
            fake.getInventory().clearContent();
            fake.getInventory().setItem(0, new ItemStack(terminal));
            fake.getInventory().setItem(1, new ItemStack(cell));
            QuickHatch.LOGGER.info("[selftest] terminal probe terminal+cell: "
                            + "hasTerminal={} craft={} (unlinked terminal -> no_terminal)",
                    dev.uninery.quickhatch.platform.AE2Compat.hasTerminal(fake),
                    dev.uninery.quickhatch.platform.AE2Compat.openCraftAmountMenu(fake, probe));
        }
        fake.getInventory().clearContent();

        // 真机回归：搭一个最小 ME 网络 + 链接终端，跑完整下单链路（见 AETerminalProbe）
        var server = level.getServer();
        if (server != null) {
            AETerminalProbe.run(server);
        }
    }

    private static int countInInv(ServerPlayer player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }
}
