package dev.uninery.quickhatch.client.screen;

import dev.uninery.quickhatch.client.ClientEvents;
import dev.uninery.quickhatch.client.KeyBindings;
import dev.uninery.quickhatch.network.QuickHatchNetwork;
import dev.uninery.quickhatch.network.ServerboundCraftItemPacket;
import dev.uninery.quickhatch.network.ServerboundPullItemPacket;
import dev.uninery.quickhatch.network.ServerboundReplaceHatchPacket;
import dev.uninery.quickhatch.platform.HatchIndex;
import dev.uninery.quickhatch.platform.MultiblockRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 仓室选择界面（=ME 终端）。
 *
 * <p>布局：标题 + <b>输入输出横排</b>（标题右边）+ 搜索框 /
 * 子筛选行（能量仓或 AE）/ 左侧三列（分类、电压、电流）/ <b>仓室网格</b>。</p>
 *
 * <p>交互（拉取：物品栏 → 身上精妙背包 → 无线终端所在 ME 网络，取到快捷栏并切过去）：</p>
 * <ul>
 *   <li><b>左键</b>：拉取一整组并关闭界面</li>
 *   <li><b>右键</b>：拉取 1 个并关闭界面</li>
 *   <li>shift+左键：拉取一整组（界面不关）</li>
 *   <li>shift+右键：拉取 1 个（界面不关）</li>
 *   <li>中键：AE2 原生下单界面</li>
 * </ul>
 *
 * <p><b>替换模式</b>：<b>Ctrl+左键</b>对着"可替换方块"（多方块里能换成仓室的方块，
 * 或者本身已是仓室，样板总成之类能放样板的仓室除外）打开的界面带目标坐标，
 * 此时<b>直接左键</b>点一个仓室 = 原地破坏那个方块并放下它、旧方块还给玩家；
 * 其余点击仍然是拉取。G 键打开的是普通模式（只拉取）。</p>
 *
 * <p>E / G / ESC 关闭。</p>
 */
public class HatchSelectScreen extends Screen {

    private static final int WIN_W = 356;
    private static final int WIN_H = 390;
    private static final int CHIP_H = 10;
    private static final int COL_GAP = 1;
    /** 左侧三列：分类 / 电压 / 电流。 */
    private static final int[] COL_W = {70, 50, 44};
    private static final int COL_COUNT = 3;
    private static final int SLOT = 18;

    private HatchIndex.Category activeCategory;
    private Integer tierFilter;
    private Integer ampFilter;
    private HatchIndex.IoRole ioFilter;
    /** 当前生效的子筛选（能量仓 有线/无线/电网、总线 普通/通行/巨型/留存、流体仓 普通/通行/巨型/四重/九重、AE 方块/线缆）。 */
    private HatchIndex.SubOption subFilter;
    private String search = "";

    private EditBox searchBox;
    private int scrollRows;
    private final int[] colScroll = new int[COL_COUNT];
    private List<HatchIndex.Entry> visible;
    private List<Integer> amperages;
    private List<HatchIndex.IoRole> ioRoles;

    private int winX;
    private int winY;
    private int gridX;
    private int gridTop;
    private int gridBottom;
    private final int[] colX = new int[COL_COUNT];
    private int cols;
    private int rows;

    private record Chip(int x, int y, int w, int h, Runnable onClick) {}

    /** 手动筛选状态（供"退出时是否保留手动筛选"开关保存/恢复）。 */
    private record FilterState(HatchIndex.Category category, Integer tier, Integer amp,
                               HatchIndex.IoRole io, HatchIndex.SubOption sub, String search) {}

    /** 退出界面时是否保留手动筛选（整个游戏会话内记住）。默认关闭 = 每次全新筛选。 */
    private static boolean keepFilters = false;

    /** 上次关闭界面时的手动筛选（keepFilters 打开时用于恢复）。 */
    private static FilterState lastFilters;

    private final List<Chip> ioChips = new ArrayList<>();
    private final List<Chip> subChips = new ArrayList<>();
    private int[] keepFilterButtonBounds;

    /** 替换模式的目标方块（对着可替换方块右键/Ctrl+左键打开时非 null；G 键打开时为 null）。 */
    private final BlockPos replaceTarget;

    public HatchSelectScreen() {
        this(null);
    }

    public HatchSelectScreen(BlockPos replaceTarget) {
        super(Component.translatable("quickhatch.screen.title"));
        this.replaceTarget = replaceTarget;
    }

    /** 是不是"对着可替换方块"打开的替换模式。 */
    private boolean replaceMode() {
        return replaceTarget != null;
    }

    @Override
    protected void init() {
        winX = (this.width - WIN_W) / 2;
        winY = (this.height - WIN_H) / 2;
        colX[0] = winX + 6;
        for (int c = 1; c < COL_COUNT; c++) {
            colX[c] = colX[c - 1] + COL_W[c - 1] + 4;
        }
        gridX = colX[COL_COUNT - 1] + COL_W[COL_COUNT - 1] + 6;
        gridTop = winY + 36;
        // 底部留一条给"筛选保留"开关
        gridBottom = winY + 356;
        cols = Math.max(1, (winX + WIN_W - 6 - gridX) / SLOT);
        rows = Math.max(1, (gridBottom - gridTop) / SLOT);

        searchBox = new EditBox(this.font, winX + WIN_W - 92, winY + 5, 86, 12,
                Component.translatable("quickhatch.screen.search"));
        searchBox.setMaxLength(32);
        searchBox.setBordered(false);
        searchBox.setResponder(s -> {
            search = s.toLowerCase(Locale.ROOT);
            scrollRows = 0;
        });
        addRenderableWidget(searchBox);

        // 开局先清空手动筛选；开关打开时才恢复上次的
        activeCategory = null;
        tierFilter = null;
        ampFilter = null;
        ioFilter = null;
        subFilter = null;
        search = "";
        FilterState restored = keepFilters ? lastFilters : null;
        if (restored != null) {
            activeCategory = restored.category();
            tierFilter = restored.tier();
            ampFilter = restored.amp();
            ioFilter = restored.io();
            subFilter = restored.sub();
            search = restored.search() == null ? "" : restored.search();
            searchBox.setValue(search);
        }

        amperages = HatchIndex.amperages();
        ioRoles = HatchIndex.ioRoles();
        visible = null;
        ClientEvents.requestItemCounts();
    }

    private List<HatchIndex.Entry> visibleEntries() {
        if (visible == null) {
            List<HatchIndex.Entry> out = new ArrayList<>();
            for (HatchIndex.Entry e : HatchIndex.get()) {
                if (activeCategory != null && e.category() != activeCategory) continue;
                if (tierFilter != null && e.tier() != tierFilter) continue;
                if (ampFilter != null && e.amperage() != ampFilter) continue;
                if (ioFilter != null && e.io() != ioFilter) continue;
                if (subFilter != null && !subFilter.matches(e)) continue;
                if (!search.isEmpty() && !e.searchText().contains(search)) continue;
                out.add(e);
            }
            visible = out;
        }
        return visible;
    }

    private void invalidate() {
        visible = null;
        scrollRows = 0;
    }

    // ------------------------------------------------------------------ //

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        gfx.fill(winX, winY, winX + WIN_W, winY + WIN_H, 0xC010141A);
        gfx.fill(winX, winY, winX + WIN_W, winY + 1, 0x50FFFFFF);
        gfx.fill(winX, winY + WIN_H - 1, winX + WIN_W, winY + WIN_H, 0x50FFFFFF);
        gfx.fill(winX, winY, winX + 1, winY + WIN_H, 0x50FFFFFF);
        gfx.fill(winX + WIN_W - 1, winY, winX + WIN_W, winY + WIN_H, 0x50FFFFFF);

        gfx.drawString(this.font, this.title, winX + 6, winY + 6, 0xFFFFFFFF, false);

        // 输入输出横排（标题右边）
        renderIoRow(gfx, mouseX, mouseY);

        // 子筛选行（能量仓 / 总线 / 流体仓 / AE，各自一组）
        subChips.clear();
        if (subRowActive()) {
            renderSubRow(gfx, mouseX, mouseY);
        }

        for (int col = 0; col < COL_COUNT; col++) {
            renderColumn(gfx, mouseX, mouseY, col);
        }

        renderGrid(gfx, mouseX, mouseY);

        searchBox.render(gfx, mouseX, mouseY, partialTick);
        renderKeepFilterToggle(gfx, mouseX, mouseY);
    }

    /** 底部"筛选: 保留 / 不保留"开关（需求 6 恢复）。 */
    private void renderKeepFilterToggle(GuiGraphics gfx, int mx, int my) {
        String label = Component.translatable(keepFilters
                ? "quickhatch.screen.keep_filter.on" : "quickhatch.screen.keep_filter.off").getString();
        int w = this.font.width(label) + 10;
        int h = CHIP_H + 4;
        int x = winX + 6;
        int y = gridBottom + 6;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        gfx.fill(x, y, x + w, y + h,
                keepFilters ? (hover ? 0xFF3D6E96 : 0xFF2F5A7A) : (hover ? 0xFF2A2E36 : 0xFF20242C));
        gfx.drawString(this.font, label, x + 5, y + 3,
                keepFilters ? 0xFFFFFFFF : 0xFFB8BCC4, false);
        keepFilterButtonBounds = new int[]{x, y, w, h};
    }

    /** 记录当前手动筛选（关闭界面时调用）。 */
    private void rememberFilters() {
        lastFilters = new FilterState(activeCategory, tierFilter, ampFilter,
                ioFilter, subFilter, search);
    }

    @Override
    public void onClose() {
        if (keepFilters) {
            rememberFilters();
        }
        super.onClose();
    }

    /**
     * "点完就关界面"的点击先记下来，等<b>鼠标松开</b>再关。
     *
     * <p>原因：如果在左键还按着的时候把界面关掉，世界的 tick 会把"左键按住"
     * 当成攻击/挖掘（{@code Minecraft#continueAttack}），于是手上冒出一个挥手动画；
     * 我们的左键/中键点击不能留下这种副作用。</p>
     */
    private boolean closeOnMouseRelease;

    private void closeAfterMouseRelease() {
        closeOnMouseRelease = true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (closeOnMouseRelease) {
            closeOnMouseRelease = false;
            onClose();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 输入输出：横排小 chip，放在标题右边（需求 7：按钮调大一点）。 */
    private void renderIoRow(GuiGraphics gfx, int mx, int my) {
        ioChips.clear();
        int x = winX + 6 + this.font.width(this.title) + 10;
        int y = winY + 4;
        final int h = CHIP_H + 5;
        for (HatchIndex.IoRole role : ioRoles) {
            Component label = role.label();
            int w = this.font.width(label) + 16;
            boolean active = ioFilter == role;
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
            gfx.fill(x, y, x + w, y + h, active ? 0xFF3D6E96 : (hover ? 0xFF2A2E36 : 0xFF20242C));
            gfx.drawString(this.font, label, x + 8, y + 3,
                    active ? 0xFFFFFFFF : 0xFFB8BCC4, false);
            final HatchIndex.IoRole value = role;
            ioChips.add(new Chip(x, y, w, h, () -> {
                ioFilter = ioFilter == value ? null : value;
                invalidate();
            }));
            x += w + 4;
        }
    }

    /** 当前分类有没有子筛选行（能量仓/总线/流体仓/AE 都有自己的一组）。 */
    private boolean subRowActive() {
        return !HatchIndex.subOptionsOf(activeCategory).isEmpty();
    }

    private void renderSubRow(GuiGraphics gfx, int mx, int my) {
        int y = winY + 22;
        int x = gridX;
        final int w = 46;
        for (HatchIndex.SubOption option : HatchIndex.subOptionsOf(activeCategory)) {
            if (!option.available()) continue;
            boolean active = subFilter == option;
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + CHIP_H;
            gfx.fill(x, y, x + w, y + CHIP_H, active ? 0xFF3D6E96 : (hover ? 0xFF2A2E36 : 0xFF20242C));
            gfx.drawString(this.font, option.label(), x + 3, y + 1,
                    active ? 0xFFFFFFFF : 0xFFB8BCC4, false);
            final HatchIndex.SubOption value = option;
            subChips.add(new Chip(x, y, w, CHIP_H, () -> toggleSub(value)));
            x += w + 4;
        }
    }

    private void toggleSub(HatchIndex.SubOption value) {
        subFilter = subFilter == value ? null : value;
        invalidate();
    }

    /** 三列筛选；统一单选、点击已选项取消。shift+左键=只留这一项（清掉其它手动筛选）。 */
    private void renderColumn(GuiGraphics gfx, int mx, int my, int col) {
        int x = colX[col];
        int w = COL_W[col];
        // 电压/电流两列的标题字已按要求去掉
        int chipTop = winY + 36;
        int chipBottom = gridBottom;
        int viewport = Math.max(1, (chipBottom - chipTop) / (CHIP_H + COL_GAP));

        List<?> chips = chipsOf(col);
        int maxScroll = Math.max(0, chips.size() - viewport);
        if (colScroll[col] > maxScroll) colScroll[col] = maxScroll;
        gfx.enableScissor(x, chipTop, x + w, chipBottom);
        for (int i = 0; i < chips.size(); i++) {
            int row = i - colScroll[col];
            if (row < 0 || row >= viewport) continue;
            int y = chipTop + row * (CHIP_H + COL_GAP);
            boolean active = isActive(col, chips.get(i));
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + CHIP_H;
            gfx.fill(x, y, x + w, y + CHIP_H, active ? 0xFF3D6E96 : (hover ? 0xFF2A2E36 : 0xFF20242C));
            if (col == 1 && !active) {
                // 电压列：drawString 不认 Component 的 style 颜色，必须用 § 颜色码；
                // 颜色就是 GT 自己的 GTValues.VC（每档对应的原版颜色码）
                gfx.drawString(this.font, MultiblockRegistry.tierColoredText((Integer) chips.get(i)),
                        x + 3, y + 1, 0xFFB8BCC4, false);
            } else {
                gfx.drawString(this.font, chipLabel(col, chips.get(i)), x + 3, y + 1,
                        active ? 0xFFFFFFFF : 0xFFB8BCC4, false);
            }
        }
        gfx.disableScissor();
    }

    private List<?> chipsOf(int col) {
        return switch (col) {
            case 0 -> List.of(HatchIndex.Category.values());
            // 电压列每次现算：索引里有蒸汽档（-2）就一定会出现，不会因为缓存/时机漏掉
            case 1 -> HatchIndex.tiers();
            default -> amperages;
        };
    }

    private boolean isActive(int col, Object chip) {
        return switch (col) {
            case 0 -> chip == activeCategory;
            case 1 -> chip.equals(tierFilter);
            default -> chip.equals(ampFilter);
        };
    }

    /** 电压列文字用 GTM 自己的电压色（GTValues.VC）。 */
    private Component chipLabel(int col, Object chip) {
        if (col == 0) return ((HatchIndex.Category) chip).label();
        if (col == 1) return MultiblockRegistry.tierChip((Integer) chip);
        return Component.literal(chip + "A");
    }

    private void renderGrid(GuiGraphics gfx, int mx, int my) {
        List<HatchIndex.Entry> entries = visibleEntries();
        int maxScroll = Math.max(0, (entries.size() + cols - 1) / cols - rows);
        if (scrollRows > maxScroll) scrollRows = maxScroll;
        gfx.enableScissor(gridX - 1, gridTop - 1, gridX + cols * SLOT + 1, gridTop + rows * SLOT + 1);

        HatchIndex.Entry hovered = null;
        Map<ResourceLocation, Long> counts = ClientEvents.itemCounts();
        for (int i = 0; i < entries.size(); i++) {
            int col = i % cols;
            int row = i / cols - scrollRows;
            if (row < 0 || row >= rows) continue;
            int x = gridX + col * SLOT;
            int y = gridTop + row * SLOT;
            drawSlot(gfx, x, y);
            gfx.renderItem(entries.get(i).display(), x + 1, y + 1);
            ResourceLocation entryId = entries.get(i).id();
            Long count = counts.get(entryId);
            if (count != null && count > 0) {
                drawSlotCount(gfx, formatCount(count), x, y);
            }
            // AE 终端做法：可合成（有样板）的物品在左上角画 "+"
            // （MEStorageScreen 对 isCraftable 的槽位调 renderSizeLabel(x-11, y-11, "+")）
            if (ClientEvents.isCraftable(entryId)) {
                renderPlusBadge(gfx, x, y);
            }
            if (mx >= x && mx < x + SLOT && my >= y && my < y + SLOT) {
                hovered = entries.get(i);
                gfx.fill(x + 1, y + 1, x + 17, y + 17, 0x60FFFFFF);
            }
        }
        gfx.disableScissor();
        if (hovered != null) {
            gfx.renderTooltip(this.font, hovered.display(), mx, my);
        }
    }

    private void drawSlot(GuiGraphics gfx, int x, int y) {
        gfx.fill(x, y, x + SLOT, y + SLOT, 0xFF262A32);
        gfx.fill(x, y, x + SLOT, y + 1, 0x30FFFFFF);
        gfx.fill(x, y, x + 1, y + SLOT, 0x30FFFFFF);
    }

    /** AE 的 {@code StackSizeRenderer#renderSizeLabel} 等价实现。 */
    private void renderCountLabel(GuiGraphics gfx, String text, int itemX, int itemY) {
        final float scale = 0.5f;
        final int offset = 1;
        int tx = (int) ((itemX + offset + 16.0f - this.font.width(text) * scale) / scale);
        int ty = (int) ((itemY + offset + 16.0f - 7.0f * scale) / scale);
        var pose = gfx.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1.0f);
        gfx.drawString(this.font, text, tx, ty, 0xFFFFFF, true);
        pose.popPose();
    }

    /** 数量角标画在槽位右下角（在物品<b>里面</b>，不是物品后面）。 */
    private void drawSlotCount(GuiGraphics gfx, String text, int slotX, int slotY) {
        renderCountLabel(gfx, text, slotX, slotY);
    }

    /**
     * 可合成角标：左上角的 "+"。
     *
     * <p>照 AE2 {@code MEStorageScreen#renderSlot}：
     * {@code StackSizeRenderer.renderSizeLabel(guiGraphics, font, x - 11, y - 11, "+", false)}，
     * 也就是同样 0.5 倍缩放、以 (x-11, y-11) 为槽位原点画在左上角外侧。</p>
     */
    private void renderPlusBadge(GuiGraphics gfx, int slotX, int slotY) {
        final float scale = 0.5f;
        final int offset = -1;
        int baseX = slotX - 11;
        int baseY = slotY - 11;
        int tx = (int) ((baseX + offset + 16.0f - this.font.width("+") * scale) / scale);
        int ty = (int) ((baseY + offset + 16.0f - 7.0f * scale) / scale);
        var pose = gfx.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1.0f);
        gfx.drawString(this.font, "+", tx, ty, 0xFFFFFF, true);
        pose.popPose();
    }

    /** AE 的 {@code ReadableNumberConverter.format(number, 3)} 等价实现。 */
    static String formatCount(long count) {
        if (count <= 0) return "0";
        String plain = Long.toString(count);
        int width = 3;
        if (plain.length() <= width) return plain;
        String[] postfixes = {"K", "M", "G", "T", "P", "E"};
        long base = count;
        double last = base * 1000.0;
        int exponent = -1;
        String postfix = "";
        int size = plain.length();
        while (size > width && exponent < postfixes.length - 1) {
            last = base;
            base /= 1000;
            exponent++;
            size = Long.toString(base).length() + 1;
            postfix = postfixes[exponent];
        }
        String withPrecision = plain1dp(last / 1000.0) + postfix;
        String withoutPrecision = base + postfix;
        return withPrecision.length() <= width ? withPrecision : withoutPrecision;
    }

    private static String plain1dp(double value) {
        long truncated = (long) Math.floor(value * 10.0 + 1.0e-6);
        if (truncated % 10 == 0) {
            return Long.toString(truncated / 10);
        }
        return (truncated / 10) + "." + Math.abs(truncated % 10);
    }

    // ------------------------------------------------------------------ //
    // 点击
    // ------------------------------------------------------------------ //

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.minecraft == null) return super.mouseClicked(mouseX, mouseY, button);

        // 输入输出横排
        for (Chip chip : ioChips) {
            if (hit(chip, mouseX, mouseY)) {
                chip.onClick().run();
                return true;
            }
        }
        // "筛选保留"开关
        if (keepFilterButtonBounds != null) {
            int[] b = keepFilterButtonBounds;
            if (mouseX >= b[0] && mouseX < b[0] + b[2]
                    && mouseY >= b[1] && mouseY < b[1] + b[3]) {
                keepFilters = !keepFilters;
                if (keepFilters) {
                    rememberFilters();
                } else {
                    lastFilters = null;
                }
                return true;
            }
        }
        // 子筛选行
        for (Chip chip : subChips) {
            if (hit(chip, mouseX, mouseY)) {
                chip.onClick().run();
                return true;
            }
        }
        // 列筛选
        if (button == 0 || button == 1) {
            // 列筛选
            for (int col = 0; col < COL_COUNT; col++) {
                if (mouseX >= colX[col] && mouseX < colX[col] + COL_W[col]) {
                    int chipTop = winY + 36;
                    int viewport = Math.max(1, (gridBottom - chipTop) / (CHIP_H + COL_GAP));
                    int idx = chipAt(col, mouseY, chipTop, viewport);
                    if (idx >= 0) {
                        Object chip = chipsOf(col).get(idx);
                        if (hasShiftDown()) {
                            // 需求 8：shift+左键某一项 = 应用它，并清掉其它手动筛选栏位的选项
                            setFilterExclusive(col, chip);
                        } else if (isActive(col, chip)) {
                            clearFilter(col);
                        } else {
                            setFilter(col, chip);
                        }
                        invalidate();
                        return true;
                    }
                }
            }
            // 仓室网格
            HatchIndex.Entry entry = entryAt(mouseX, mouseY);
            if (entry != null) {
                handleGridClick(entry, button);
                return true;
            }
        } else if (button == 2) {
            HatchIndex.Entry entry = entryAt(mouseX, mouseY);
            if (entry != null) {
                QuickHatchNetwork.CHANNEL.sendToServer(
                        new ServerboundCraftItemPacket(entry.id()));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 仓室网格点击。
     *
     * <ul>
     *   <li><b>替换模式</b>（对着可替换方块打开的界面）+ 直接左键：
     *       原地破坏目标方块并放下这个仓室（旧方块还给玩家），然后关闭界面</li>
     *   <li><b>左键</b>：拉取一整组<b>并关闭界面</b>（快捷栏已有就切过去）</li>
     *   <li><b>右键</b>：拉取 1 个<b>并关闭界面</b></li>
     *   <li>shift+左键：拉取一整组但不替换背包里已有的同类物品（界面不关）</li>
     *   <li>shift+右键：拉取 1 个但不替换（界面不关）</li>
     *   <li>中键：AE2 原生下单界面</li>
     * </ul>
     */
    private void handleGridClick(HatchIndex.Entry entry, int button) {
        if (replaceMode() && button == 0 && !hasShiftDown()) {
            // 替换：把目标方块换成选中的仓室，旧方块由服务端还给玩家
            QuickHatchNetwork.CHANNEL.sendToServer(
                    new ServerboundReplaceHatchPacket(entry.id(), replaceTarget));
            closeAfterMouseRelease();
            return;
        }
        if (hasShiftDown()) {
            // 不替换背包里已有的同类物品（界面不关）
            QuickHatchNetwork.CHANNEL.sendToServer(button == 1
                    ? ServerboundPullItemPacket.pullSingleKeep(entry.id())
                    : ServerboundPullItemPacket.pullStackKeep(entry.id()));
            return;
        }
        // 左键拉一整组、右键拉 1 个，并且关闭界面
        QuickHatchNetwork.CHANNEL.sendToServer(button == 1
                ? ServerboundPullItemPacket.pullSingle(entry.id())
                : ServerboundPullItemPacket.pullStack(entry.id()));
        closeAfterMouseRelease();
    }

    private boolean hit(Chip chip, double mx, double my) {
        return mx >= chip.x() && mx < chip.x() + chip.w()
                && my >= chip.y() && my < chip.y() + chip.h();
    }

    private int chipAt(int col, double my, int chipTop, int viewport) {
        List<?> chips = chipsOf(col);
        int row = (int) ((my - chipTop) / (CHIP_H + COL_GAP));
        if (row < 0 || row >= viewport) return -1;
        int idx = row + colScroll[col];
        return idx < chips.size() ? idx : -1;
    }

    private void setFilter(int col, Object chip) {
        switch (col) {
            case 0 -> {
                activeCategory = (HatchIndex.Category) chip;
                // 换分类 = 换了一组子筛选，清掉旧的
                subFilter = null;
            }
            case 1 -> tierFilter = (Integer) chip;
            default -> ampFilter = (Integer) chip;
        }
    }

    /**
     * 需求 8：shift+左键筛选栏某项时，在"应用这一项"的基础上
     * <b>清掉其它手动筛选栏位的选项</b>（分类/电压/电流/输入输出/子筛选/搜索都清掉，
     * 只留刚点的这一项）。
     */
    private void setFilterExclusive(int col, Object chip) {
        activeCategory = null;
        tierFilter = null;
        ampFilter = null;
        ioFilter = null;
        subFilter = null;
        search = "";
        if (searchBox != null) {
            searchBox.setValue("");
        }
        setFilter(col, chip);
    }

    private void clearFilter(int col) {
        switch (col) {
            case 0 -> {
                activeCategory = null;
                subFilter = null;
            }
            case 1 -> tierFilter = null;
            default -> ampFilter = null;
        }
    }

    private HatchIndex.Entry entryAt(double mouseX, double mouseY) {
        List<HatchIndex.Entry> entries = visibleEntries();
        int maxScroll = Math.max(0, (entries.size() + cols - 1) / cols - rows);
        if (scrollRows > maxScroll) scrollRows = maxScroll;
        for (int i = 0; i < entries.size(); i++) {
            int col = i % cols;
            int row = i / cols - scrollRows;
            if (row < 0 || row >= rows) continue;
            int x = gridX + col * SLOT;
            int y = gridTop + row * SLOT;
            if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) {
                return entries.get(i);
            }
        }
        return null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double yDelta) {
        for (int col = 0; col < COL_COUNT; col++) {
            if (mouseX >= colX[col] && mouseX < colX[col] + COL_W[col]) {
                colScroll[col] -= (int) Math.signum(yDelta);
                if (colScroll[col] < 0) colScroll[col] = 0;
                int viewport = Math.max(1, (gridBottom - (winY + 36)) / (CHIP_H + COL_GAP));
                int maxScroll = Math.max(0, chipsOf(col).size() - viewport);
                if (colScroll[col] > maxScroll) colScroll[col] = maxScroll;
                return true;
            }
        }
        if (mouseX >= gridX && mouseY >= gridTop && mouseY < gridBottom) {
            scrollRows -= (int) Math.signum(yDelta);
            if (scrollRows < 0) scrollRows = 0;
            List<HatchIndex.Entry> entries = visibleEntries();
            int maxScroll = Math.max(0, (entries.size() + cols - 1) / cols - rows);
            if (scrollRows > maxScroll) scrollRows = maxScroll;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, yDelta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.minecraft != null) {
            if (this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
                onClose();
                return true;
            }
            if (KeyBindings.OPEN_SELECT.matches(keyCode, scanCode)) {
                onClose();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics gfx) {
        // 半透明简约风：不绘制全屏暗色背景
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
