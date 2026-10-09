package dev.uninery.quickhatch.platform;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.data.chemical.material.properties.PropertyKey;
import com.gregtechceu.gtceu.api.item.MaterialPipeBlockItem;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.common.block.CableBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仓室/线缆/管道/AE2 物品索引（客户端，打开界面时惰性构建；服务端计数复用）。
 *
 * <p>识别规则（详见 AGENTS.md）：
 * 维护=maintenance、消声=muffler、转子=rotor/holder、数据=data/optical/wireless_data、
 * 算力=computation、ME=me_、蒸汽=steam_*_bus / steam_*_hatch（不含 steam_miner 等单方块机器）；
 * 总线=item 输入/输出总线、流体仓=输入/输出流体仓，二者靠 {@link IoRole} 区分输入输出；
 * 能量仓的子类：无线=gtmthings / wireless、电网/激光电网=GTM Advanced Hatch(net_energy/net_laser)、
 * 其余=有线。</p>
 */
public final class HatchIndex {

    /**
     * 第二列"子筛选行"的一个选项。每个分类各有自己的一组：
     * 能量仓 = 有线/无线/电网；总线 = 普通/通行/巨型/留存；
     * 流体仓 = 普通/通行/巨型/四重/九重；AE = 方块/线缆。
     */
    public interface SubOption {

        Component label();

        /** 界面上是否显示这一项（例如没装 AH 电网相关模组就不显示"电网"）。 */
        default boolean available() {
            return true;
        }

        /** 这一项是否包含该记录（实现里自己判分类）。 */
        boolean matches(Entry entry);
    }

    /** 能源仓子类。 */
    public enum EnergyType implements SubOption {
        WIRED("wired"), WIRELESS("wireless"), ADV_NET("adv_net"), NONE("none");

        public final String key;

        EnergyType(String key) {
            this.key = key;
        }

        public Component label() {
            return Component.translatable("quickhatch.energy." + key);
        }

        @Override
        public boolean available() {
            return this != ADV_NET || hasAdvNetEnergy();
        }

        @Override
        public boolean matches(Entry entry) {
            return entry.category() == Category.ENERGY_HATCH && entry.energyType() == this;
        }
    }

    /**
     * 总线的子分类（用户第二十三轮）：<b>普通 / 通行 / 巨型 / 留存</b>。
     *
     * <ul>
     *   <li>普通：输入/输出总线（含蒸汽总线）；</li>
     *   <li>通行：物品通行仓（{@code *_item_passthrough_hatch}）；</li>
     *   <li>巨型：id 带 {@code huge} 的总线（GTM Things 的巨型输入/输出总线）；</li>
     *   <li>留存：id 带 {@code lock} 的总线（GTMAdvancedHatch 的留存输出总线）。</li>
     * </ul>
     */
    public enum BusSub implements SubOption {
        PLAIN("plain"), PASSTHROUGH("passthrough"), HUGE("huge"), LOCK("lock");

        public final String key;

        BusSub(String key) {
            this.key = key;
        }

        public Component label() {
            return Component.translatable("quickhatch.sub." + key);
        }

        @Override
        public boolean matches(Entry entry) {
            if (entry.category() != Category.BUS) return false;
            String path = entry.id().getPath();
            return switch (this) {
                case PASSTHROUGH -> isItemPassthroughHatch(path);
                case HUGE -> path.contains("huge");
                case LOCK -> path.contains("lock");
                case PLAIN -> !isItemPassthroughHatch(path) && !path.contains("huge") && !path.contains("lock");
            };
        }
    }

    /**
     * 流体仓的子分类（用户第二十三轮）：<b>普通 / 通行 / 巨型 / 四重 / 九重</b>。
     *
     * <ul>
     *   <li>普通：{@code lv_input_hatch} 这类；</li>
     *   <li>通行：流体通行仓（{@code *_fluid_passthrough_hatch}）；</li>
     *   <li>巨型：{@code *_huge_input_hatch} / {@code *_huge_output_hatch}；</li>
     *   <li>四重：{@code *_input_hatch_4x} 这类多重流体仓；</li>
     *   <li>九重：{@code *_input_hatch_9x}。</li>
     * </ul>
     */
    public enum FluidSub implements SubOption {
        PLAIN("plain"), PASSTHROUGH("passthrough"), HUGE("huge"), QUAD("quad"), NINE("nine");

        public final String key;

        FluidSub(String key) {
            this.key = key;
        }

        public Component label() {
            return Component.translatable("quickhatch.sub." + key);
        }

        @Override
        public boolean matches(Entry entry) {
            if (entry.category() != Category.FLUID_HATCH) return false;
            String path = entry.id().getPath();
            return switch (this) {
                case PASSTHROUGH -> isFluidPassthroughHatch(path);
                case HUGE -> path.contains("huge");
                case QUAD -> path.contains("_4x");
                case NINE -> path.contains("_9x");
                case PLAIN -> !isFluidPassthroughHatch(path) && !path.contains("huge")
                        && !path.contains("_4x") && !path.contains("_9x");
            };
        }
    }

    /** 某个分类的子筛选选项（没有子分类的分类返回空列表）。 */
    public static List<SubOption> subOptionsOf(Category category) {
        if (category == null) return List.of();
        return switch (category) {
            case ENERGY_HATCH -> List.of(EnergyType.WIRELESS, EnergyType.WIRED, EnergyType.ADV_NET);
            case BUS -> List.of(BusSub.PLAIN, BusSub.PASSTHROUGH, BusSub.HUGE, BusSub.LOCK);
            case FLUID_HATCH -> List.of(FluidSub.PLAIN, FluidSub.PASSTHROUGH, FluidSub.HUGE,
                    FluidSub.QUAD, FluidSub.NINE);
            case AE -> List.of(AeSubtype.BLOCK, AeSubtype.CABLE);
            default -> List.of();
        };
    }

    /**
     * 输入输出分类（筛选用）：源仓就是输出、靶仓就是输入，不分开。
     * 总线/流体仓/蒸汽仓用 input_xxx / output_xxx 判定；
     * 能量/数据/算力仓的靶仓（target / energy_input / xxx_transmitter）算输入，
     * 源仓（source / energy_output / xxx_receiver）算输出。
     */
    public enum IoRole {
        INPUT("input"), OUTPUT("output"), NONE("none");

        public final String key;

        IoRole(String key) {
            this.key = key;
        }

        public Component label() {
            return Component.translatable("quickhatch.io." + key);
        }
    }

    /**
     * AE 分类的子分类（AE 分类合并后由子面板切换）：<b>只有 方块 / 线缆 两档</b>。
     *
     * <p>面板、显示器、终端、合成监控器……<b>全部归"方块"</b>（用户明确要求
     * "面板全放方块里去"，不要再拆出"面板"这一档）；"线缆" = 注册 id 以
     * {@code cable} 结尾的线缆部件与石英纤维。</p>
     */
    public enum AeSubtype implements SubOption {
        BLOCK("block"), CABLE("cable"), NONE("none");

        public final String key;

        AeSubtype(String key) {
            this.key = key;
        }

        public Component label() {
            return Component.translatable("quickhatch.ae." + key);
        }

        @Override
        public boolean matches(Entry entry) {
            return entry.category() == Category.AE && entry.aeSubtype() == this;
        }
    }

    /** AE 部件子分类（纯 id 判定，不依赖 AE2 的类）：线缆 or 方块。 */
    static AeSubtype aePartSubtype(ResourceLocation id) {
        String path = id == null ? "" : id.getPath();
        if (path.endsWith("cable") || "quartz_fiber".equals(path)) {
            return AeSubtype.CABLE;
        }
        return AeSubtype.BLOCK;
    }

    /** 筛选分类。 */
    public enum Category {
        BUS("bus"),
        FLUID_HATCH("fluid_hatch"),
        ENERGY_HATCH("energy_hatch"),
        DUAL_HATCH("dual_hatch"),
        MAINTENANCE("maintenance"),
        MUFFLER("muffler"),
        COMPUTATION("computation"),
        DATA("data"),
        ROTOR("rotor"),
        PARALLEL_HATCH("parallel_hatch"),
        ME("me"),
        CABLE("cable"),
        PIPE("pipe"),
        AE("ae"),
        /** 中子加速器（中子活化器的仓室：{@code *_neutron_accelerator}）。 */
        NEUTRON_ACCELERATOR("neutron_accelerator"),
        /** 其他：方块总线 / 嬗变总线 / 物料谱解析仓 / 万象转录节点 / 中子传感器 这类杂项。 */
        MISC("misc");

        public final String key;

        Category(String key) {
            this.key = key;
        }

        public Component label() {
            return Component.translatable("quickhatch.category." + key);
        }
    }

    /**
     * "蒸汽"不是电压等级，是排在最前面的一个伪电压档。
     *
     * <p>用户要求：<b>取消原来的"蒸汽"分类</b>，改成电压列最前面的一档"蒸汽"，
     * 蒸汽输入/输出仓（大型 / 特大 / 巨型）都归到<b>能量仓</b>分类里。</p>
     */
    public static final int TIER_STEAM = -2;

    /** 蒸汽<b>仓</b>判据（{@code *_hatch}）：归"能量仓"分类。 */
    static boolean isSteamEnergyHatch(String path) {
        return path.contains("steam") && path.endsWith("_hatch");
    }

    /**
     * 蒸汽相关的仓室/总线（{@code *_hatch} / {@code *_bus}）：<b>电压档一律用"蒸汽"</b>。
     *
     * <p>蒸汽仓归"能量仓"、蒸汽总线归"总线"，但两者的"电压"都是蒸汽 ——
     * 所以电压列那档"蒸汽"能把它们都筛出来（用户第二十二轮：蒸汽筛选要有效）。</p>
     */
    static boolean isSteamPart(String path) {
        return path.contains("steam") && (path.endsWith("_hatch") || path.endsWith("_bus"));
    }

    /** 通行仓：物品通行仓 = 总线，流体通行仓 = 流体仓（用户第二十二轮）。 */
    static boolean isItemPassthroughHatch(String path) {
        return path.contains("item_passthrough_hatch");
    }

    static boolean isFluidPassthroughHatch(String path) {
        return path.contains("fluid_passthrough_hatch");
    }

    /**
     * "其他"分类的 id 表（命名空间 + 路径）。
     *
     * <p>名字本身像仓/总线的（以 {@code _hatch} / {@code _bus} 结尾，例如焦炉仓
     * {@code coke_oven_hatch}、物料谱解析仓）会被 {@code categorizeMachine} 末尾的兜底规则
     * 自动收进"其他"；这张表是给那些<b>名字看不出来</b>的方块用的
     * （嬗变总线的 id 以 {@code me_} 开头，本来会被算成"ME 仓"；中子传感器压根没有 hatch/bus 后缀）。</p>
     *
     * <ul>
     *   <li>{@code gtceu:coke_oven_hatch} 焦炉仓</li>
     *   <li>{@code gtceu:block_bus} 方块总线</li>
     *   <li>{@code gtceu:neutron_sensor} 中子传感器</li>
     *   <li>{@code gtladditions:me_block_conservation} 嬗变总线</li>
     *   <li>{@code gtladditions:spectral_analysis_hatch} 物料谱解析仓</li>
     *   <li>{@code gtladditions:vientiane_transcription_node} 万象转录节点</li>
     * </ul>
     */
    private static final Set<String> MISC_IDS = new java.util.LinkedHashSet<>(List.of(
            "gtceu:coke_oven_hatch",
            "gtceu:block_bus",
            "gtceu:neutron_sensor",
            "gtladditions:me_block_conservation",
            "gtladditions:spectral_analysis_hatch",
            "gtladditions:vientiane_transcription_node"));

    /**
     * 这个分类里的东西是不是"仓室"（能装进多方块、可以被替换成别的仓室）。
     *
     * <p>线缆 / 管道 / AE 方块<b>不是</b>仓室：它们虽然也列在界面里（方便拉取），
     * 但对着电线按替换键是不该有反应的。</p>
     */
    public static boolean isHatchCategory(Category category) {
        return category != Category.CABLE && category != Category.PIPE && category != Category.AE;
    }

    /**
     * 电流后缀。
     * <ul>
     *   <li>收尾式：{@code lv_energy_input_hatch_4a} / {@code wireless_energy_input_hatch_lv_4a}
     *       / {@code ev_energy_input_hatch_16a}</li>
     *   <li>中间式：{@code ev_256a_laser_target_hatch}（激光靶/源仓，电流在 laser 之前）</li>
     * </ul>
     */
    private static final Pattern AMP_SUFFIX = Pattern.compile("_(\\d+)a$");
    private static final Pattern AMP_MIDDLE = Pattern.compile("_(\\d+)a_");

    /** 一条索引记录。tier = 电压等级（0..14，非电压物品 -1）；amperage = 电流（非电流物品 -1）。 */
    public record Entry(ResourceLocation id, Category category, int tier, int amperage,
                        EnergyType energyType, IoRole io, AeSubtype aeSubtype,
                        Item item, String searchText) {

        public ItemStack display() {
            return new ItemStack(item);
        }
    }

    private static volatile List<Entry> index;

    private HatchIndex() {}

    public static List<Entry> get() {
        List<Entry> local = index;
        if (local == null) {
            synchronized (HatchIndex.class) {
                if (index == null) {
                    index = build();
                }
                local = index;
            }
        }
        return local;
    }

    /**
     * 能放<b>样板</b>的仓室（样板总成、样板供应器等）—— 替换时排除掉它们：
     * 里面存的样板/程序不能因为换个方块就丢了。
     *
     * <p>判据用注册表 id（{@code pattern}）：{@code gtceu:me_pattern_buffer}、
     * {@code me_pattern_buffer_proxy}、{@code ae2:pattern_provider}、
     * ExtendedAE 的 {@code ex_pattern_provider}…… 全部命中。</p>
     */
    public static boolean holdsPatterns(ResourceLocation id) {
        return id != null && id.getPath().contains("pattern");
    }

    private static volatile Set<Block> replaceableHatchBlocks;

    /**
     * "已放置的仓室"里允许被替换的方块种类 = 仓室类目里的方块物品 − 能放样板的那些。
     *
     * <p><b>只有仓室</b>：线缆、管道、AE 方块（{@link #isHatchCategory} 判为 false）不在内 ——
     * 用户明确要求"不是所有被记录到界面里的方块都能触发替换，只有能被替换为仓室的方块和所有仓室"，
     * 否则对着电线也会弹替换界面。</p>
     */
    public static Set<Block> replaceableHatchBlocks() {
        Set<Block> local = replaceableHatchBlocks;
        if (local == null) {
            synchronized (HatchIndex.class) {
                if (replaceableHatchBlocks == null) {
                    Set<Block> out = new HashSet<>();
                    for (Entry e : get()) {
                        if (!isHatchCategory(e.category())) continue;
                        if (holdsPatterns(e.id())) continue;
                        if (e.item() instanceof net.minecraft.world.item.BlockItem blockItem) {
                            out.add(blockItem.getBlock());
                        }
                    }
                    replaceableHatchBlocks = Set.copyOf(out);
                }
                local = replaceableHatchBlocks;
            }
        }
        return local;
    }

    /** 索引中实际存在的电压等级（升序）；蒸汽档（{@link #TIER_STEAM}）有的话排在最前面。 */
    public static List<Integer> tiers() {
        boolean[] present = new boolean[GTValues.TIER_COUNT];
        boolean steam = false;
        for (Entry e : get()) {
            if (e.tier() == TIER_STEAM) {
                steam = true;
            } else if (e.tier() >= 0 && e.tier() < GTValues.TIER_COUNT) {
                present[e.tier()] = true;
            }
        }
        List<Integer> out = new ArrayList<>();
        if (steam) out.add(TIER_STEAM);
        for (int t = 0; t < GTValues.TIER_COUNT; t++) {
            if (present[t]) out.add(t);
        }
        return out;
    }

    /** 索引中实际存在的电流档（升序）。 */
    public static List<Integer> amperages() {
        TreeSet<Integer> out = new TreeSet<>();
        for (Entry e : get()) {
            if (e.amperage() > 0) out.add(e.amperage());
        }
        return new ArrayList<>(out);
    }

    /** 索引中实际存在的输入输出分类（升序，按枚举顺序）。 */
    public static List<IoRole> ioRoles() {
        List<IoRole> out = new ArrayList<>();
        for (IoRole role : new IoRole[]{IoRole.INPUT, IoRole.OUTPUT}) {
            for (Entry e : get()) {
                if (e.io() == role) {
                    out.add(role);
                    break;
                }
            }
        }
        return out;
    }

    /** 索引中是否存在 GTM Advanced Hatch 的电网/激光电网仓（决定子筛选是否显示该分类）。 */
    public static boolean hasAdvNetEnergy() {
        for (Entry e : get()) {
            if (e.category() == Category.ENERGY_HATCH && e.energyType() == EnergyType.ADV_NET) {
                return true;
            }
        }
        return false;
    }

    private static List<Entry> build() {
        List<Entry> out = new ArrayList<>();

        // 1) GTM/GTLCore 机器（各仓室/总线；排除多方块控制器与无关单方块机器）
        for (var e : GTRegistries.MACHINES.entries()) {
            MachineDefinition def = e.getValue();
            if (def instanceof MultiblockMachineDefinition) continue;
            MetaMachineItem item = def.getItem();
            if (item == null) continue;
            String path = e.getKey().getPath();
            Category category = categorizeMachine(e.getKey());
            if (category == null) continue;
            int amperage = -1;
            EnergyType energyType = EnergyType.NONE;
            if (category == Category.ENERGY_HATCH) {
                amperage = amperageOf(path);
                energyType = energyTypeOf(path, e.getKey().getNamespace());
            } else if (category == Category.CABLE) {
                amperage = amperageOf(path);
            }
            // 蒸汽仓（大型/特大/巨型蒸汽输入输出仓）不按 GT 电压档走，单独一档"蒸汽"
            int tier = tierOf(category, path, def.getTier());
            out.add(new Entry(e.getKey(), category, tier, amperage, energyType,
                    ioRoleOf(path, category), AeSubtype.NONE, item, searchText(item, e.getKey())));
        }

        // 2) 线缆与管道（GTM 材质管道体系）
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (item instanceof MaterialPipeBlockItem pipeItem) {
                var block = pipeItem.getBlock();
                if (block instanceof CableBlock cable) {
                    int amp = cable.pipeType != null ? cable.pipeType.amperage : -1;
                    out.add(new Entry(id, Category.CABLE, voltageToTier(
                            cable.material.getProperty(PropertyKey.WIRE).getVoltage()), amp,
                            EnergyType.NONE, IoRole.NONE, AeSubtype.NONE, item, searchText(item, id)));
                } else {
                    out.add(new Entry(id, Category.PIPE, -1, -1, EnergyType.NONE, IoRole.NONE,
                            AeSubtype.NONE, item, searchText(item, id)));
                }
            } else if (item instanceof BlockItem blockItem && isAeDeviceBlock(blockItem.getBlock(), id)) {
                // AE 的设备方块（能连上 ME 网络的那些）；建筑方块不进列表。
                // 子分类只有"线缆/方块"，方块这一档就是全部非线缆内容（面板也在里面）
                out.add(new Entry(id, Category.AE, -1, -1, EnergyType.NONE, IoRole.NONE,
                        AeSubtype.BLOCK, item, searchText(item, id)));
            }
        }

        // 3) AE2 部件物品（线缆与面板等；AE2 未安装时为空）
        AE2Compat.collectPartItems((item, subtype) -> {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            out.add(new Entry(id, Category.AE, -1, -1, EnergyType.NONE, IoRole.NONE,
                    subtype, item, searchText(item, id)));
        });

        // 4) "其他"分类的兜底：表里的方块不一定登记在 GTM 机器注册表里（可能是普通方块），
        //    已经进过列表的跳过，其余按 id 直接补上
        Set<ResourceLocation> seen = new HashSet<>();
        for (Entry e : out) {
            seen.add(e.id());
        }
        for (String miscId : MISC_IDS) {
            ResourceLocation id = ResourceLocation.tryParse(miscId);
            if (id == null || seen.contains(id)) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            out.add(new Entry(id, Category.MISC, -1, -1, EnergyType.NONE, IoRole.NONE,
                    AeSubtype.NONE, item, searchText(item, id)));
        }

        out.sort((a, b) -> {
            int byCat = a.category().compareTo(b.category());
            if (byCat != 0) return byCat;
            int byTier = Integer.compare(a.tier(), b.tier());
            if (byTier != 0) return byTier;
            return a.id().compareTo(b.id());
        });
        return List.copyOf(out);
    }

    private static String searchText(Item item, ResourceLocation id) {
        String name = new ItemStack(item).getHoverName().getString();
        return (name + " " + id.getPath() + " " + id.getNamespace()).toLowerCase(Locale.ROOT);
    }

    /**
     * AE 的"设备方块"= 能连上 ME 网络的方块。判定分两层：
     *
     * <ol>
     *   <li><b>结构判定（主力）</b>：{@link AE2Compat#isGridDeviceBlock(Block)} ——
     *       方块挂着 {@code AEBaseEntityBlock} 且它的方块实体能在世界里主持网格节点。
     *       这条对 AE2 本体与所有附属模组（ExtendedAE 等）通用，也不怕 AE2 以后新增方块。</li>
     *   <li><b>注册 id 兜底</b>：AE2 自己那几个"属于 AE 但自己不连网格"的方块
     *       （充能器、压印器之类）单独列出来，免得结构判定把它们漏掉。</li>
     * </ol>
     *
     * <p>以前这里是<b>唯一</b>的一层：一张写死的 id 表。写死的表必然漏——它连
     * {@code 1k~256k_crafting_storage} 的 id 顺序都写反了（AE2 的真实 id 是
     * {@code 1k_crafting_storage} 而不是 {@code crafting_storage_1k}），
     * 附属模组的 AE 方块更是一个都进不来。</p>
     */
    static boolean isAeDeviceBlock(Block block, ResourceLocation itemId) {
        // 调试方块（ae2:debug_*、附属模组的 debug_*）不算设备
        if (itemId != null && itemId.getPath().startsWith("debug_")) return false;
        return AE2Compat.isGridDeviceBlock(block) || isAeDeviceBlockId(itemId);
    }

    /** 兜底表：AE2 自己的 AE 方块注册 id（{@code namespace == ae2}）。 */
    static boolean isAeDeviceBlockId(ResourceLocation id) {
        if (id == null || !"ae2".equals(id.getNamespace())) return false;
        return switch (id.getPath()) {
            // 网络核心
            case "controller", "drive", "chest", "interface", "cell_workbench", "io_port",
                 "condenser", "energy_acceptor", "crystal_resonance_generator", "vibration_chamber",
                 "growth_accelerator", "energy_cell", "dense_energy_cell", "creative_energy_cell",
                 "wireless_access_point", "spatial_anchor", "spatial_pylon", "spatial_io_port",
                 "quantum_ring", "quantum_link", "molecular_assembler", "light_detector",
                 "pattern_provider", "inscriber", "charger",
                 // 合成 CPU 多方块（存储的 id 是「容量在前」：1k_crafting_storage）
                 "crafting_unit", "crafting_accelerator", "1k_crafting_storage", "4k_crafting_storage",
                 "16k_crafting_storage", "64k_crafting_storage", "256k_crafting_storage",
                 "crafting_monitor" -> true;
            // 建筑/装饰/世界方块：石英与天空石系列、玻璃、楼梯台阶墙、染色、矩阵框架、TNT、箱子、储罐、机械曲柄
            default -> false;
        };
    }

    /**
     * 按注册 id 分类。顺序敏感：
     * 维护/消声/转子/数据/算力优先；光学靶源仓(optical_*)与无线数据仓归数据仓；
     * 蒸汽只认总线与仓室（steam_*_bus / steam_*_hatch），
     * 蒸汽采矿机等蒸汽单方块机器不进仓室列表；ME 前缀先于通用；
     * energy/net_energy/net_laser/激光靶源仓 都算能量仓（含输入输出之分）。
     */
    /**
     * 按注册 id 分类。顺序敏感：
     * 维护/消声/转子/数据/算力优先；光学靶源仓(optical_*)与无线数据仓归数据仓；
     * 蒸汽只认总线与仓室（steam_*_bus / steam_*_hatch），
     * 蒸汽采矿机等蒸汽单方块机器不进仓室列表；ME 前缀先于通用；
     * energy/net_energy/net_laser/激光靶源仓 都算能量仓（含输入输出之分）。
     *
     * <p>给自检用的公开入口。</p>
     */
    public static Category categorizeMachinePublic(String path) {
        return categorizeMachine(path);
    }

    /**
     * 按"命名空间:路径"直接分类（自检用；不依赖方块是否真的装了，
     * 所以能验证 {@code gtladditions:*} / GTLCore 的 {@code gtceu:block_bus} 这些规则）。
     */
    public static Category categorizeId(String namespacePath) {
        return categorizeMachine(ResourceLocation.tryParse(namespacePath));
    }

    /**
     * 一条记录最终用的电压档：蒸汽仓用"蒸汽"档，其他类的杂项没有电压档，
     * 其余用机器定义自己的电压。（自检也会直接调它。）
     */
    public static int tierOf(Category category, String path, int definitionTier) {
        if (isSteamPart(path)) return TIER_STEAM;
        if (category == Category.MISC) return -1;
        return definitionTier;
    }

    /** 只有路径时的分类（自检用）；命名空间相关的"其他"表照样能命中唯一路径。 */
    static Category categorizeMachine(String path) {
        for (var e : GTRegistries.MACHINES.entries()) {
            if (e.getKey().getPath().equals(path)) {
                return categorizeMachine(e.getKey());
            }
        }
        return categorizeMachine(ResourceLocation.tryParse("gtceu:" + path));
    }

    static Category categorizeMachine(ResourceLocation id) {
        if (id == null) return null;
        String path = id.getPath();
        // "其他"必须最先判：嬗变总线的 id 是 me_block_conservation，按后面的 me_ 规则会被算成 ME 仓
        if (MISC_IDS.contains(id.toString())) return Category.MISC;
        // 中子加速器（中子活化器的仓室）：lv_neutron_accelerator … max_neutron_accelerator
        if (path.endsWith("neutron_accelerator")) return Category.NEUTRON_ACCELERATOR;
        if (path.contains("maintenance")) return Category.MAINTENANCE;
        if (path.contains("muffler")) return Category.MUFFLER;
        if (path.contains("rotor")) return Category.ROTOR;
        // 并行控制仓（Parallel Control Hatch）：GTCEu/GTLCore 注册 id 是
        // iv_parallel_hatch / luv_parallel_hatch / zpm_parallel_hatch / uv_parallel_hatch
        // （mk1~mk4）。它在图案里走 abilities(PartAbility.PARALLEL_HATCH)
        // （= autoAbilities 会带进来的仓室），只有按 id 前缀收进来才会出现在列表里。
        if (path.contains("parallel")) return Category.PARALLEL_HATCH;
        // 数据仓：光学靶/源仓（data_transmitter_hatch / data_receiver_hatch / optical_data_hatch）
        // 与 GTLCore 无线数据仓（wireless_data_transmitter_hatch / wireless_data_receiver_hatch）
        if (path.contains("optical") || path.contains("wireless_data") || path.contains("data_access")) {
            return Category.DATA;
        }
        // 算力仓：计算靶/源仓（computation_transmitter_hatch / computation_receiver_hatch）
        // 与 GTM Things 无线算力仓（wireless_computation_*_hatch）；
        // HPCA 内部组件与创造算力提供者不是仓室，排除
        if (path.contains("computation")) {
            return path.contains("component") || path.contains("creative") ? null : Category.COMPUTATION;
        }
        if (path.contains("data") && path.endsWith("hatch")) return Category.DATA;
        // ME 仓室：me_input_hatch / me_output_hatch / me_stocking_* / me_dual_hatch_* 等
        if (path.startsWith("me_")) return Category.ME;
        if (path.contains("dual") && path.contains("hatch")) return Category.DUAL_HATCH;
        // 能量仓室：能源仓/激光靶源仓/AH 电网仓/无线能源仓。
        // 排除单方块机器：能量转换器（*_energy_converter）与创造能量（creative_energy）
        if (path.contains("energy_converter") || path.equals("creative_energy")) return null;
        boolean energyHatch = path.contains("energy") || path.contains("net_energy")
                || path.contains("net_laser") || (path.contains("laser") && path.endsWith("hatch"));
        if (energyHatch) return Category.ENERGY_HATCH;
        // 变电站仓（用户第二十三轮：变电能源仓/变电动力仓跑错到流体仓去了）：
        // 它们 id 里没有 "energy"（ev_substation_input_hatch_64a），但确实是能量仓（带电流档）
        if (path.contains("substation")) return Category.ENERGY_HATCH;
        // 兜底：带电流档（_4a / _64a / _256a_）的"仓"都是能量仓 —— 多重流体仓是 _4x / _9x，不会命中
        if (path.contains("hatch") && amperageOf(path) > 1) return Category.ENERGY_HATCH;
        // 蒸汽<b>仓</b>（大型/特大/巨型蒸汽输入输出仓、蒸汽输入仓）：用户要求<b>取消"蒸汽"分类</b>，
        // 这些仓归到能量仓里，电压档用"蒸汽"（见 TIER_STEAM）。
        // 注意：蒸汽<b>总线</b>（steam_input_bus / steam_output_bus）不在这里 —— 它们要落到下面的
        // input_bus/output_bus 规则，进"总线"分类并带输入输出角色（电压档仍是"蒸汽"）。
        if (isSteamEnergyHatch(path)) return Category.ENERGY_HATCH;
        // 通行仓：物品通行仓是"总线"，流体通行仓是"流体仓"（它们不是 input/output 命名，得单独判）
        if (isItemPassthroughHatch(path)) return Category.BUS;
        if (isFluidPassthroughHatch(path)) return Category.FLUID_HATCH;
        // 总线：普通输入/输出总线 + 各种"导入/导出"命名的总线（巨型输入总线 huge_item_import_bus_lv 等）
        if (path.endsWith("input_bus") || path.endsWith("output_bus")
                || path.endsWith("import_bus") || path.endsWith("export_bus")
                || path.contains("_import_bus_") || path.contains("_export_bus_")) {
            return Category.BUS;
        }
        // 流体仓（含<b>多重流体仓</b>）：lv_input_hatch / ev_input_hatch_4x / max_input_hatch_9x /
        // ev_substation_input_hatch_64a 之类都要收，所以用 contains 看 "input_hatch"，
        // 不能只用 endsWith。导入/导出命名的流体仓（*_huge_input_hatch 等）一并归这里。
        if (path.contains("input_hatch") || path.contains("output_hatch")
                || path.contains("import_hatch") || path.contains("export_hatch")) {
            return Category.FLUID_HATCH;
        }
        // 收尾兜底：名字就像仓/总线的机器（焦炉仓 coke_oven_hatch、物料谱解析仓等）一律进"其他"，
        // 免得以后 GTCEu / 附属模组新增的仓室又被漏掉
        if (path.endsWith("_hatch") || path.endsWith("_bus")) return Category.MISC;
        return null;
    }

    /**
     * 输入输出角色（自检用；靶仓/源仓的判定规则见 {@link #ioRoleOf}）。
     */
    public static IoRole ioRoleOfPublic(String path, Category category) {
        return ioRoleOf(path, category);
    }

    /** 电流档解析（自检用，见 {@link #amperageOf}）。 */
    public static int amperageOfPublic(String path) {
        return amperageOf(path);
    }

    /**
     * id 电流：{@code _4a} → 4、{@code _256a_} → 256；无电流标记时：
     * <b>GT 标准的"能源仓/动力仓"是 2A</b>（{@code GTMachines}：
     * {@code new EnergyHatchPartMachine(holder, tier, IN, 2)}，每个电压档的基础仓都是 2A），
     * <b>无线版是 1A</b>（GTM Things 的无线能源仓），其余无标记的按 1A。
     */
    static int amperageOf(String path) {
        Matcher m = AMP_SUFFIX.matcher(path);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        m = AMP_MIDDLE.matcher(path);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        if (isStandardTwoAmpEnergyHatch(path)) return 2;
        return 1;
    }

    /**
     * 没带电流标记的"基础能源仓 / 动力仓"（{@code lv_energy_input_hatch} 这类）= GT 的 2A 仓。
     *
     * <p>无线能源仓（{@code wireless_energy_*_hatch}）与 AH 电网仓（{@code net_energy_*}）
     * 不是 GT 这个 2A 模板，保持 1A。</p>
     */
    public static boolean isStandardTwoAmpEnergyHatch(String path) {
        if (path.contains("wireless") || path.contains("net_")) return false;
        return path.endsWith("energy_input_hatch") || path.endsWith("energy_output_hatch");
    }

    /** 能源仓子类：gtmthings=无线；net_energy/net_laser=AH 电网；其余=有线。 */
    static EnergyType energyTypeOf(String path, String namespace) {
        if ("gtmthings".equals(namespace) || path.contains("wireless")) return EnergyType.WIRELESS;
        if (path.contains("net_energy") || path.contains("net_laser")) return EnergyType.ADV_NET;
        return EnergyType.WIRED;
    }

    /**
     * 输入输出角色：源仓=输出、靶仓=输入。
     * 靶仓/输入：target、*_input、transmitter、<b>import</b>（巨型输入总线之类）；
     * 源仓/输出：source、*_output、receiver、<b>export</b>。
     */
    static IoRole ioRoleOf(String path, Category category) {
        boolean input = path.contains("target") || path.contains("_input") || path.contains("transmitter")
                || path.contains("import");
        boolean output = path.contains("source") || path.contains("_output") || path.contains("receiver")
                || path.contains("export");
        if (input) return IoRole.INPUT;
        if (output) return IoRole.OUTPUT;
        return IoRole.NONE;
    }

    /** 电压 → 最近（不超过该电压的）等级。 */
    public static int voltageToTier(long voltage) {
        int tier = 0;
        for (int t = 0; t < GTValues.V.length; t++) {
            if (voltage >= GTValues.V[t]) {
                tier = t;
            }
        }
        return tier;
    }

    /** 电压分类字体：使用该电压自身颜色（与 GTM 物品名一致）。 */
    public static Component tierChip(int tier) {
        return MultiblockRegistry.tierChip(tier);
    }
}
