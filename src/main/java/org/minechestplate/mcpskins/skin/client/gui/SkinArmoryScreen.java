package org.minechestplate.mcpskins.skin.client.gui;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;
import org.minechestplate.mcpskins.skin.network.ApplySkinPayload;
import org.minechestplate.mcpskins.skin.render.GunModelPatcher;
import org.minechestplate.mcpskins.skin.render.SkinAssetResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Полноэкранный самостоятельный каталог/инспектор скинов ("Уровень 2" из концепта
 * "MCPSkins Armory") - НЕ привязан к тому, что сейчас в руке игрока (в отличие от
 * {@link org.minechestplate.mcpskins.skin.client.TACZRefitSkinOverlay}, который остаётся как
 * есть и архитектурно не меняется). Открывается по хоткею
 * ({@link org.minechestplate.mcpskins.skin.client.ArmoryKeybinds}) или командой
 * {@code /mcpskins armory}.
 *
 * <p><b>Раскладка</b> (см. §3 концепта, ASCII-схема) - четыре зоны плюс верхняя панель
 * фильтров:
 * <pre>
 * [ поиск ...................... ] [Все][Мои][Заблокир.] [С моделью] [Сортировка]
 * ┌───────────┬──────────────────────────────┬──────────────────┐
 * │  Оружия    │        3D-ПОДИУМ             │   Скины <оружие>  │
 * │ (левая     │  (Item3DPodiumWidget -       │  (сетка, не       │
 * │  колонка)  │   drag=вращение, колесо=зум) │   карусель)       │
 * └───────────┴──────────────────────────────┴──────────────────┘
 * [ имя скина · редкость · коллекция · [Custom model]     [Экипировать] ]
 * </pre>
 *
 * <p><b>ПОЧЕМУ РАСКЛАДКА СЧИТАЕТСЯ ОТНОСИТЕЛЬНО {@code this.width}/{@code this.height}, А НЕ
 * ФИКСИРОВАННЫМИ ПИКСЕЛЯМИ:</b> {@code TACZRefitSkinOverlay} намеренно использует захардкоженные
 * отступы вроде {@code TOGGLE_MARGIN_TOP = 108} - и это ПРАВИЛЬНО именно там, потому что тот
 * оверлей рисуется поверх ЧУЖОГО экрана (родного {@code GunRefitScreen} у TACZ) с фиксированным
 * по пикселям фоновым изображением, под которое нужно физически подстроиться. Здесь же экран
 * ПОЛНОСТЬЮ наш собственный - у нас нет чужой текстуры, под которую нужно подгонять пиксели, а
 * есть только `this.width`/`this.height` (это уже логические, отмасштабированные под GUI Scale
 * координаты, а не сырые пиксели монитора - Minecraft сам пересчитывает их с учётом GUI Scale и
 * разрешения экрана). Поэтому весь layout здесь считается через {@link #computeLayout()} как
 * доли/отступы от ширины и высоты экрана - это и есть "правильный", а не "на глаз" подход для
 * СОБСТВЕННОГО экрана: он одинаково корректно выглядит на любом разрешении и при любом GUI
 * Scale, а не только на том, под которое его настраивали.
 *
 * <p><b>Почему НЕТ блюра фона</b> - подробно объяснено в javadoc {@link Item3DPodiumWidget}
 * (класс, который явным образом заменяет блюр на тёмную "студийную" подложку). Экран не
 * получает ванильный блюр "Menu Background Blurriness", потому что {@link #renderBackground}
 * здесь переопределён как no-op - см. javadoc этого метода за объяснением, почему просто "не
 * вызывать" {@code renderBackground(...)} самим недостаточно ({@code Screen#render(...)}
 * вызывает его сам).
 */
public class SkinArmoryScreen extends Screen {

    private enum FocusPane { WEAPONS, SKINS }

    private enum StatusFilter { ALL, OWNED, LOCKED }

    private enum SortMode { RARITY, ALPHABETICAL, NEWEST }

    private static final int WEAPON_ROW_HEIGHT = 24;
    // Кнопки хедера немного увеличены (18 -> 20 высота, 4 -> 6 зазор) - см. также
    // pillWidthFor/statusPillRect/customModelToggleRect/sortButtonRect ниже, где ширина
    // каждой кнопки теперь считается ДИНАМИЧЕСКИ по фактическому тексту, а не фиксированным
    // числом - именно фиксированная ширина 70 была причиной того, что "Заблокированные"
    // вылезала за рамки своей кнопки.
    private static final int PILL_HEIGHT = 20;
    private static final int PILL_GAP = 6;
    private static final int PILL_TEXT_PADDING = 22;

    // Хедер (поиск + кнопки фильтров/сортировки) теперь считается ПОЛНОСТЬЮ независимо от
    // трёх колонок ниже - см. computeHeaderLayout() и init(). Раньше ширина
    // поиска считалась через podiumX (величину из совсем другой части раскладки), из-за
    // чего на многих разрешениях поиск схлопывался в минимум, а блок кнопок уезжал далеко
    // направо и "Заблокированные" вылезала за экран - это и есть баг из §1 задачи.
    private static final int HEADER_MARGIN = 8;
    private static final int HEADER_GROUP_GAP = 14;
    private static final int HEADER_MIN_SEARCH_WIDTH = 90;
    private static final int PILL_ROW_GAP = 6;
    // Раньше у всех трёх статус-фильтров был один общий минимум (76) - разумный для длинного
    // "Заблокированные", но избыточный для коротких "Все"/"Мои" (они просто раздувались до
    // этого минимума, хотя их текст в разы короче). Уменьшили минимум специально под короткие
    // подписи - "Заблокированные" эта величина не касается вовсе, т.к. её реальный текст и так
    // шире любого разумного минимума (см. pillWidthFor - минимум это только НИЖНЯЯ граница).
    // За счёт этого "Все"+"Мои" суммарно стали компактнее и в первую строку хедера теперь
    // помещается ещё и кнопка сортировки (см. computeHeaderLayout()).
    private static final int STATUS_PILL_MIN_WIDTH = 30;

    private record Rect(int x0, int y0, int x1, int y1) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        }

        int width() {
            return x1 - x0;
        }

        int height() {
            return y1 - y0;
        }
    }

    /** Все геометрические зоны экрана на этот кадр - единый источник правды для рендера И клика. */
    private record Layout(int topBarHeight,
                          int leftX, int leftY, int leftWidth, int leftHeight,
                          int podiumX, int podiumY, int podiumWidth, int podiumHeight,
                          int rightX, int rightY, int rightWidth, int rightHeight,
                          int bottomY, int bottomHeight,
                          int gridColumns, int gridCellSize, int gridSpacing,
                          int gridPaddingX, int gridPaddingY) {
    }

    private final List<String> weaponKeys = new ArrayList<>();
    private final Map<String, String> weaponNameCache = new HashMap<>();
    private final Map<String, Boolean> customModelCache = new HashMap<>();
    private final Item3DPodiumWidget podium = new Item3DPodiumWidget();

    private String selectedWeapon;
    private List<SkinDataModels.SkinLookupResult> visibleEntries = new ArrayList<>();
    private int selectedSkinIndex = 0;
    private EditBox searchBox;
    private StatusFilter statusFilter = StatusFilter.ALL;
    private boolean customModelOnly = false;
    private SortMode sortMode = SortMode.RARITY;
    private FocusPane focusPane = FocusPane.SKINS;
    private int weaponScrollPixels = 0;
    private int gridScrollPixels = 0;
    private String statusMessage;

    public SkinArmoryScreen() {
        super(Component.translatable("gui.mcpskins.armory.title"));
    }

    // -----------------------------------------------------------------------------------
    // Инициализация / layout
    // -----------------------------------------------------------------------------------

    @Override
    protected void init() {
        podium.resetView();

        // Поиск теперь - отдельная строка на всю ширину хедера (см. javadoc
        // computeHeaderLayout()), а не узкая полоска рядом с кнопками фильтров - кнопки
        // рисуются НИЖЕ неё и физически не могут её перекрыть, сколько бы строк им ни
        // потребовалось.
        HeaderLayout header = computeHeaderLayout();
        this.searchBox = new EditBox(this.font, header.searchX(), header.searchY(),
                header.searchWidth(), header.searchHeight(),
                Component.translatable("gui.mcpskins.armory.search"));
        this.searchBox.setHint(Component.translatable("gui.mcpskins.armory.search_hint"));
        this.searchBox.setResponder(s -> refreshVisibleEntries());
        this.addRenderableWidget(searchBox);

        rebuildWeaponList();
        if (selectedWeapon == null || !weaponKeys.contains(selectedWeapon)) {
            selectedWeapon = defaultWeaponSelection();
        }
        refreshVisibleEntries();
    }

    /**
     * Считает все зоны экрана заново каждый кадр из {@code this.width}/{@code this.height} -
     * дёшево (десяток целочисленных операций), поэтому пересчитывать при каждом обращении
     * безопаснее, чем кэшировать и рисковать рассинхронизацией после resize окна (см. javadoc
     * класса про то, почему это вообще пропорционально, а не "на глаз").
     */
    private Layout computeLayout() {
        int margin = 6;
        // topBarHeight больше не константа - он равен фактической высоте хедера (строка
        // поиска + все строки кнопок, включая перенесённые - см. computeHeaderLayout()),
        // поэтому весь контент ниже (левая колонка/подиум/сетка) сам сдвигается вниз ровно
        // настолько, насколько хедеру реально потребовалось места на этом экране.
        int topBarHeight = computeHeaderLayout().totalHeight();
        int bottomBarHeight = 46;

        // Левая колонка чуть шире, чем раньше (130-210 -> 140-230) - см. renderWeaponList,
        // где это вместе с усечением текста ("...") чинит вылезающие за рамки названия оружий.
        int leftWidth = Mth.clamp(this.width / 6, 140, 230);
        // Правая колонка со скинами теперь считается "от боксов", а не наоборот: сперва
        // фиксируем компактный размер бокса скина (gridCellSize) под 2 колонки в ряд - боксы
        // были слишком большими относительно 16px иконки предмета внутри (§3 задачи), - а
        // rightWidth это ТОЧНО столько пикселей, сколько нужно двум таким боксам плюс
        // отступы, без лишнего "воздуха" по краям. Раньше rightWidth был отдельной, гораздо
        // более широкой величиной (168-220) и не был согласован с реальной шириной сетки
        // 76px-боксов - отсюда и был огромный пустой отступ справа от неё (§2 задачи). Вся
        // высвобожденная ширина уходит подиуму автоматически, т.к. podiumWidth ниже считается
        // как остаток между левой и правой колонками - соответственно подиум становится
        // заметно больше.
        int gridPaddingX = 6, gridPaddingY = 6, gridSpacing = 6, gridCellSize = 56;
        int gridColumnsTarget = 2;
        int rightWidth = gridPaddingX * 2 + gridCellSize * gridColumnsTarget + gridSpacing * (gridColumnsTarget - 1);

        int contentTop = topBarHeight + margin;
        int contentBottom = Math.max(contentTop + 20, this.height - bottomBarHeight - margin);
        int contentHeight = contentBottom - contentTop;

        int leftX = margin;
        int rightX = this.width - margin - rightWidth;
        int podiumX = leftX + leftWidth + margin;
        int podiumWidth = Math.max(40, rightX - margin - podiumX);

        // gridColumns пересчитывается динамически на случай очень узких экранов (тогда
        // usableGridWidth не наберёт даже на gridColumnsTarget колонок) - на обычных экранах
        // это всегда даст ровно gridColumnsTarget (2), т.к. rightWidth выше уже посчитан
        // ИМЕННО под них.
        int usableGridWidth = Math.max(gridCellSize, rightWidth - gridPaddingX * 2);
        int gridColumns = Mth.clamp((usableGridWidth + gridSpacing) / (gridCellSize + gridSpacing), 1, gridColumnsTarget);

        return new Layout(topBarHeight,
                leftX, contentTop, leftWidth, contentHeight,
                podiumX, contentTop, podiumWidth, contentHeight,
                rightX, contentTop, rightWidth, contentHeight,
                this.height - bottomBarHeight, bottomBarHeight,
                gridColumns, gridCellSize, gridSpacing, gridPaddingX, gridPaddingY);
    }

    /** Ширина кнопки под её фактический (локализованный) текст + отступы, но не меньше минимума. */
    private int pillWidthFor(Component label, int minWidth) {
        return Math.max(minWidth, this.font.width(label) + PILL_TEXT_PADDING);
    }

    /**
     * Раскладка всего хедера - поиск и кнопки фильтров/сортировки. Раньше поиск и все 5
     * кнопок (3 статус-фильтра + "С моделью" + сортировка) обязаны были уместиться в ОДНУ
     * строку - на не очень широких экранах (или высоком GUI Scale) им не хватало места, и
     * кнопки либо вылезали за правый край экрана, либо (после первого фикса, прижимавшего
     * их к правому краю) наезжали прямо на поиск, если суммарно не помещались вообще. Так
     * ломаться раскладка больше не может, потому что:
     *  - поиск - это ОТДЕЛЬНАЯ строка на всю ширину хедера; кнопки физически рисуются НИЖЕ
     *    неё и наехать на неё не могут;
     *  - кнопки выстроены простым построчным потоком (см. {@link #advancePillCursor}):
     *    добавляются слева направо, и как только очередная не помещается до правого края -
     *    поток сам переходит на следующую строку. Поэтому кнопки никогда не вылезают за
     *    экран, сколько бы строк для этого ни потребовалось.
     * Высота хедера (topBarHeight в {@link Layout}) считается из {@code totalHeight()} этой
     * раскладки, то есть сама подстраивается под число строк кнопок.
     */
    private record HeaderLayout(int searchX, int searchY, int searchWidth, int searchHeight,
                                Rect[] statusRects, Rect customRect, Rect sortRect, int totalHeight) {
    }

    private HeaderLayout computeHeaderLayout() {
        int searchHeight = PILL_HEIGHT;
        int searchY = 4;
        int[] cursorX = {HEADER_MARGIN};
        int[] cursorY = {searchY + searchHeight + PILL_ROW_GAP};

        Rect[] statusRects = new Rect[StatusFilter.values().length];
        boolean first = true;
        for (StatusFilter f : StatusFilter.values()) {
            int width = pillWidthFor(statusFilterLabel(f), STATUS_PILL_MIN_WIDTH);
            statusRects[f.ordinal()] = advancePillCursor(cursorX, cursorY, width, first ? 0 : PILL_GAP);
            first = false;
        }
        int customWidth = pillWidthFor(Component.translatable("gui.mcpskins.armory.filter_custom_model"), 90);
        Rect customRect = advancePillCursor(cursorX, cursorY, customWidth, HEADER_GROUP_GAP);

        int sortWidth = pillWidthFor(sortModeLabel(sortMode), 134);
        Rect sortRect = advancePillCursor(cursorX, cursorY, sortWidth, HEADER_GROUP_GAP);

        int totalHeight = cursorY[0] + PILL_HEIGHT + PILL_ROW_GAP;
        int searchWidth = Math.max(HEADER_MIN_SEARCH_WIDTH, this.width - HEADER_MARGIN * 2);
        return new HeaderLayout(HEADER_MARGIN, searchY, searchWidth, searchHeight,
                statusRects, customRect, sortRect, totalHeight);
    }

    /**
     * Двигает "курсор" построчного потока кнопок на одну кнопку вперёд: если кнопка шириной
     * {@code width} (с отступом {@code gapBefore} от предыдущей в той же строке) не
     * помещается до правого края экрана - переносит её на новую строку вместо того, чтобы
     * вылезти за экран. {@code cursorX}/{@code cursorY} - массивы из одного элемента,
     * играющие роль "выходных параметров" (в Java нет ссылочных int-параметров).
     */
    private Rect advancePillCursor(int[] cursorX, int[] cursorY, int width, int gapBefore) {
        int rowRight = this.width - HEADER_MARGIN;
        boolean atRowStart = cursorX[0] == HEADER_MARGIN;
        int x0 = atRowStart ? cursorX[0] : cursorX[0] + gapBefore;
        if (!atRowStart && x0 + width > rowRight) {
            cursorY[0] += PILL_HEIGHT + PILL_ROW_GAP;
            x0 = HEADER_MARGIN;
        }
        Rect rect = new Rect(x0, cursorY[0], x0 + width, cursorY[0] + PILL_HEIGHT);
        cursorX[0] = rect.x1();
        return rect;
    }

    private Rect statusPillRect(Layout layout, StatusFilter filter) {
        return computeHeaderLayout().statusRects()[filter.ordinal()];
    }

    private Rect customModelToggleRect(Layout layout) {
        return computeHeaderLayout().customRect();
    }

    private Rect sortButtonRect(Layout layout) {
        return computeHeaderLayout().sortRect();
    }

    private Rect equipButtonRect(Layout layout) {
        int width = 150, height = 26;
        int x1 = this.width - 12;
        int y0 = layout.bottomY() + (layout.bottomHeight() - height) / 2;
        return new Rect(x1 - width, y0, x1, y0 + height);
    }

    // -----------------------------------------------------------------------------------
    // Рендер
    // -----------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Свой тёмный фон рисуем сами (см. fillGradient ниже) - размытие видеонастройки
        // "Menu Background Blurriness" нам не нужно (см. javadoc Item3DPodiumWidget).
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xD8101010, 0xF2060606);

        Layout layout = computeLayout();

        renderTopBar(guiGraphics, layout, mouseX, mouseY);
        renderWeaponList(guiGraphics, layout, mouseX, mouseY);

        podium.setBounds(layout.podiumX(), layout.podiumY(), layout.podiumWidth(), layout.podiumHeight());
        podium.render(guiGraphics, mouseX, mouseY, partialTick, currentAccentColor());

        renderSkinGrid(guiGraphics, layout, mouseX, mouseY);
        renderBottomBar(guiGraphics, layout, mouseX, mouseY);

        // Рисуем реальные виджеты (сейчас это только строка поиска) поверх нашей ручной
        // отрисовки топ-бара, иначе фон топ-бара лёг бы поверх текстового курсора EditBox.
        // ВАЖНО: super.render(...) - это базовая реализация Screen, а она САМА в начале
        // вызывает this.renderBackground(...) (даже если мы её отсюда явно не зовём!). Раз
        // этот вызов неизбежен, мы переопределяем renderBackground(...) ниже как no-op -
        // иначе он включил бы ванильный блюр видеонастройки "Menu Background Blurriness"
        // ПОВЕРХ уже нарисованного здесь контента (топ-бар/подиум/сетка/нижняя панель), что
        // и выглядит как "блюр, закрывающий всё".
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        renderHoverTooltip(guiGraphics, layout, mouseX, mouseY);
        renderWeaponHoverTooltip(guiGraphics, layout, mouseX, mouseY);
    }

    /**
     * Намеренный no-op. Стандартная реализация {@code Screen#renderBackground(...)} рисует
     * либо панораму главного меню, либо (когда {@code Minecraft.level != null}, как здесь)
     * идёт по цепочке {@code renderBlurredBackground(...)} -> {@code GameRenderer
     * #processBlurEffect(...)} - это и есть ванильный блюр "Menu Background Blurriness".
     * {@code Screen#render(...)} вызывает {@code this.renderBackground(...)} САМ, в самом
     * начале своего тела - то есть он сработал бы через {@link #render} -> {@code
     * super.render(...)} выше, даже при том что мы нигде не зовём его явно. Единственный
     * надёжный способ полностью отключить блюр для этого экрана - переопределить сам метод,
     * а не просто "не звать" его откуда-то.
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // ничего не делаем - фон уже нарисован вручную в render() выше
    }

    private void renderTopBar(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        guiGraphics.fill(0, 0, this.width, layout.topBarHeight(), 0xE8181818);
        guiGraphics.fill(0, layout.topBarHeight() - 1, this.width, layout.topBarHeight(), 0x405FD3FF);

        for (StatusFilter filter : StatusFilter.values()) {
            Rect rect = statusPillRect(layout, filter);
            boolean active = statusFilter == filter;
            boolean hovered = rect.contains(mouseX, mouseY);
            int bg = active ? 0xE02A4A5A : hovered ? 0xE02A2A2A : 0xC0151515;
            int border = active ? 0x5FD3FF : hovered ? 0xAAAAAA : 0x3A3A3A;
            guiGraphics.fill(rect.x0(), rect.y0(), rect.x1(), rect.y1(), bg);
            guiGraphics.renderOutline(rect.x0(), rect.y0(), rect.width(), rect.height(), 0xFF000000 | border);
            int textY = rect.y0() + (rect.height() - this.font.lineHeight) / 2;
            guiGraphics.drawCenteredString(this.font, statusFilterLabel(filter), (rect.x0() + rect.x1()) / 2, textY, 0xFFFFFFFF);
        }

        Rect customRect = customModelToggleRect(layout);
        boolean customHovered = customRect.contains(mouseX, mouseY);
        int customBg = customModelOnly ? 0xE0553A1A : customHovered ? 0xE02A2A2A : 0xC0151515;
        int customBorder = customModelOnly ? 0xFFB347 : customHovered ? 0xAAAAAA : 0x3A3A3A;
        guiGraphics.fill(customRect.x0(), customRect.y0(), customRect.x1(), customRect.y1(), customBg);
        guiGraphics.renderOutline(customRect.x0(), customRect.y0(), customRect.width(), customRect.height(), 0xFF000000 | customBorder);
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.mcpskins.armory.filter_custom_model"),
                (customRect.x0() + customRect.x1()) / 2, customRect.y0() + (customRect.height() - this.font.lineHeight) / 2, 0xFFFFFFFF);

        Rect sortRect = sortButtonRect(layout);
        boolean sortHovered = sortRect.contains(mouseX, mouseY);
        guiGraphics.fill(sortRect.x0(), sortRect.y0(), sortRect.x1(), sortRect.y1(), sortHovered ? 0xE02A2A2A : 0xC0151515);
        guiGraphics.renderOutline(sortRect.x0(), sortRect.y0(), sortRect.width(), sortRect.height(), 0xFF000000 | (sortHovered ? 0xAAAAAA : 0x3A3A3A));
        guiGraphics.drawCenteredString(this.font, sortModeLabel(sortMode),
                (sortRect.x0() + sortRect.x1()) / 2, sortRect.y0() + (sortRect.height() - this.font.lineHeight) / 2, 0xFFFFFFFF);
    }

    private void renderWeaponList(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        int panelX0 = layout.leftX() - 4, panelY0 = layout.leftY() - 2;
        int panelX1 = layout.leftX() + layout.leftWidth() + 2, panelY1 = layout.leftY() + layout.leftHeight() + 2;
        guiGraphics.fill(panelX0, panelY0, panelX1, panelY1, 0xB0101010);

        guiGraphics.enableScissor(panelX0, panelY0, panelX1, panelY1);
        try {
            int rowY = layout.leftY() - weaponScrollPixels;
            for (String baseGun : weaponKeys) {
                if (rowY + WEAPON_ROW_HEIGHT >= layout.leftY() && rowY <= layout.leftY() + layout.leftHeight()) {
                    boolean selected = baseGun.equals(selectedWeapon);
                    boolean hovered = mouseX >= layout.leftX() && mouseX < layout.leftX() + layout.leftWidth()
                            && mouseY >= rowY && mouseY < rowY + WEAPON_ROW_HEIGHT;
                    boolean heldNow = isWeaponCurrentlyHeld(baseGun);

                    if (selected) {
                        guiGraphics.fill(layout.leftX(), rowY, layout.leftX() + layout.leftWidth(), rowY + WEAPON_ROW_HEIGHT, 0xE02A3A4A);
                        guiGraphics.fill(layout.leftX(), rowY, layout.leftX() + 2, rowY + WEAPON_ROW_HEIGHT, 0xFF5FD3FF);
                    } else if (hovered) {
                        guiGraphics.fill(layout.leftX(), rowY, layout.leftX() + layout.leftWidth(), rowY + WEAPON_ROW_HEIGHT, 0xE0242424);
                    }

                    ItemStack icon = TACZSkinHelper.createGunStack(baseGun);
                    guiGraphics.renderItem(icon, layout.leftX() + 4, rowY + (WEAPON_ROW_HEIGHT - 16) / 2);

                    // Названия оружий раньше просто рисовались как есть и обрезались "вслепую"
                    // границей scissor'а панели - у длинных названий часть текста физически
                    // пропадала (обрубалась на полуслове). Теперь измеряем доступную ширину и,
                    // если имя не влезает, обрезаем его до этой ширины и добавляем "..." - имя
                    // никогда не выходит за пределы строки. Полное имя при этом не теряется - оно
                    // доступно через тултип при наведении (см. renderWeaponHoverTooltip).
                    String fullName = weaponDisplayName(baseGun);
                    int nameTextX = layout.leftX() + 26;
                    int availableNameWidth = layout.leftX() + layout.leftWidth() - nameTextX - 4;
                    String displayName = truncateToWidth(fullName, availableNameWidth);
                    Component name = Component.literal(displayName);
                    int textColor = heldNow ? 0x5FD3FF : 0xFFFFFF;
                    guiGraphics.drawString(this.font, name, nameTextX,
                            rowY + (WEAPON_ROW_HEIGHT - this.font.lineHeight) / 2, textColor, false);
                }
                rowY += WEAPON_ROW_HEIGHT;
            }
        } finally {
            guiGraphics.disableScissor();
        }
    }

    private void renderSkinGrid(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        int panelX0 = layout.rightX() - 4, panelY0 = layout.rightY() - 2;
        int panelX1 = layout.rightX() + layout.rightWidth() + 2, panelY1 = layout.rightY() + layout.rightHeight() + 2;
        guiGraphics.fill(panelX0, panelY0, panelX1, panelY1, 0xB0101010);

        guiGraphics.enableScissor(panelX0, panelY0, panelX1, panelY1);
        try {
            Player player = Minecraft.getInstance().player;
            int cell = layout.gridCellSize();
            int spacing = layout.gridSpacing();
            int columns = layout.gridColumns();
            int startX = layout.rightX() + layout.gridPaddingX();
            int startY = layout.rightY() + layout.gridPaddingY() - gridScrollPixels;

            for (int i = 0; i < visibleEntries.size(); i++) {
                int col = i % columns;
                int row = i / columns;
                int cellX = startX + col * (cell + spacing);
                int cellY = startY + row * (cell + spacing);
                if (cellY + cell < layout.rightY() || cellY > layout.rightY() + layout.rightHeight()) continue;

                SkinDataModels.SkinLookupResult lookup = visibleEntries.get(i);
                SkinDataModels.SkinEntry entry = lookup.skin();
                boolean unlocked = player != null && SkinAttachment.hasSkin(player, entry.id());
                boolean selected = i == selectedSkinIndex;
                boolean equippedNow = isSkinCurrentlyEquipped(lookup);
                boolean hovered = mouseX >= cellX && mouseX < cellX + cell && mouseY >= cellY && mouseY < cellY + cell;

                guiGraphics.fill(cellX, cellY, cellX + cell, cellY + cell, selected ? 0xF02A2A2A : 0xF01B1B1B);
                int borderRgb = equippedNow ? 0x5FD3FF : selected ? 0xFFFFFF : hovered ? 0xAAAAAA : (entry.labelColor() & 0xFFFFFF);
                guiGraphics.renderOutline(cellX, cellY, cell, cell, 0xFF000000 | borderRgb);
                guiGraphics.fill(cellX, cellY, cellX + cell, cellY + 2, 0xFF000000 | (entry.labelColor() & 0xFFFFFF));

                ItemStack thumb = TACZSkinHelper.createGunStack(lookup.weapon().baseGun(), entry.id());
                guiGraphics.renderItem(thumb, cellX + cell / 2 - 8, cellY + cell / 2 - 8);

                if (!unlocked) {
                    guiGraphics.fill(cellX, cellY, cellX + cell, cellY + cell, 0x80000000);
                    int lockSize = 8;
                    int lx = cellX + cell - lockSize - 3, ly = cellY + cell - lockSize - 3;
                    guiGraphics.renderOutline(lx + 1, ly, lockSize - 2, 4, 0xFFE8E8E8);
                    guiGraphics.fill(lx, ly + 3, lx + lockSize, ly + lockSize, 0xFFE8E8E8);
                }
                if (entry.isNew()) {
                    guiGraphics.fill(cellX, cellY, cellX + 20, cellY + 9, 0xFF3A8F3A);
                    guiGraphics.drawString(this.font, Component.translatable("gui.mcpskins.armory.badge_new"),
                            cellX + 2, cellY + 1, 0xFFFFFFFF, false);
                }
                if (hasCustomModel(lookup.weapon(), entry)) {
                    guiGraphics.fill(cellX + cell - 20, cellY, cellX + cell, cellY + 9, 0xFF3A5A8F);
                    guiGraphics.drawString(this.font, Component.translatable("gui.mcpskins.armory.badge_model"),
                            cellX + cell - 18, cellY + 1, 0xFFFFFFFF, false);
                }
            }
        } finally {
            guiGraphics.disableScissor();
        }
    }

    private void renderBottomBar(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        guiGraphics.fill(0, layout.bottomY(), this.width, this.height, 0xE8181818);
        guiGraphics.fill(0, layout.bottomY(), this.width, layout.bottomY() + 1, 0x405FD3FF);

        if (!visibleEntries.isEmpty()) {
            SkinDataModels.SkinLookupResult lookup = visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1));
            SkinDataModels.SkinEntry entry = lookup.skin();

            Component nameLine = Component.literal(entry.name()).withStyle(s -> s.withColor(entry.labelColor()).withBold(true));
            guiGraphics.drawString(this.font, nameLine, 10, layout.bottomY() + 5, 0xFFFFFFFF, false);

            // ИСПРАВЛЕНО: раньше редкость всегда красилась в статичный ChatFormatting.GRAY,
            // из-за чего поле Rarity.accentColor фактически никогда не применялось - текст
            // редкости всегда оставался серым независимо от того, common это или legendary.
            // Теперь берём акцентный цвет из самой Rarity (см. её javadoc - именно для этого
            // он и заводился).
            MutableComponent detail = Component.translatable("gui.mcpskins.armory.rarity_" + entry.rarity().name().toLowerCase(Locale.ROOT))
                    .withStyle(s -> s.withColor(entry.rarity().accentColor));
            if (entry.hasCollection()) {
                detail = detail.copy().append(Component.literal("  \u2022  " + entry.collection()).withStyle(ChatFormatting.GRAY));
            }
            if (hasCustomModel(lookup.weapon(), entry)) {
                detail = detail.copy().append(Component.literal("  \u2022  ")
                        .append(Component.translatable("gui.mcpskins.armory.badge_model_full")).withStyle(ChatFormatting.AQUA));
            }
            guiGraphics.drawString(this.font, detail, 10, layout.bottomY() + 5 + this.font.lineHeight + 2, 0xA0FFFFFF, false);

            String thirdLine = statusMessage != null ? statusMessage : entry.hasDescription() ? entry.description() : null;
            if (thirdLine != null) {
                int color = statusMessage != null ? 0xFFFF8080 : 0x80FFFFFF;
                guiGraphics.drawString(this.font, thirdLine, 10, layout.bottomY() + 5 + (this.font.lineHeight + 2) * 2, color, false);
            }
        }

        Rect equipRect = equipButtonRect(layout);
        boolean hovered = equipRect.contains(mouseX, mouseY);
        boolean enabled = canEquipSelected();
        int bg = !enabled ? 0x80303030 : hovered ? 0xE02A5A2A : 0xE01B3A1B;
        guiGraphics.fill(equipRect.x0(), equipRect.y0(), equipRect.x1(), equipRect.y1(), bg);
        guiGraphics.renderOutline(equipRect.x0(), equipRect.y0(), equipRect.width(), equipRect.height(), enabled ? 0xFF5FD3FF : 0xFF555555);
        guiGraphics.drawCenteredString(this.font, equipButtonLabel(),
                (equipRect.x0() + equipRect.x1()) / 2, equipRect.y0() + (equipRect.height() - this.font.lineHeight) / 2, 0xFFFFFFFF);
    }

    /**
     * Полное название оружия при наведении - показывается ТОЛЬКО если строка в самой панели
     * реально была обрезана (см. {@link #truncateToWidth}); иначе тултип бесполезно дублировал
     * бы уже полностью видимый текст на каждое наведение.
     */
    private void renderWeaponHoverTooltip(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        if (mouseX < layout.leftX() || mouseX >= layout.leftX() + layout.leftWidth()
                || mouseY < layout.leftY() || mouseY >= layout.leftY() + layout.leftHeight()) {
            return;
        }
        int relativeY = (int) (mouseY - layout.leftY() + weaponScrollPixels);
        int index = relativeY / WEAPON_ROW_HEIGHT;
        if (index < 0 || index >= weaponKeys.size()) return;

        String fullName = weaponDisplayName(weaponKeys.get(index));
        int nameTextX = layout.leftX() + 26;
        int availableNameWidth = layout.leftX() + layout.leftWidth() - nameTextX - 4;
        if (this.font.width(fullName) <= availableNameWidth) return;

        guiGraphics.renderTooltip(this.font, Component.literal(fullName), mouseX, mouseY);
    }

    /** Обрезает {@code text} до {@code maxWidth} пикселей, добавляя "..." при обрезке. */
    private String truncateToWidth(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int ellipsisWidth = this.font.width(ellipsis);
        int fitWidth = Math.max(0, maxWidth - ellipsisWidth);
        return this.font.plainSubstrByWidth(text, fitWidth) + ellipsis;
    }

    private void renderHoverTooltip(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        int index = gridIndexAt(layout, mouseX, mouseY);
        if (index < 0) return;
        SkinDataModels.SkinLookupResult lookup = visibleEntries.get(index);
        SkinDataModels.SkinEntry entry = lookup.skin();
        Player player = Minecraft.getInstance().player;
        boolean unlocked = player != null && SkinAttachment.hasSkin(player, entry.id());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(entry.name()).withStyle(s -> s.withColor(entry.labelColor())));
        lines.add(Component.literal(weaponDisplayName(lookup.weapon().baseGun())).withStyle(ChatFormatting.GRAY));
        // Тот же фикс, что и в renderBottomBar() - используем реальный акцентный цвет
        // редкости вместо статичного ChatFormatting.DARK_GRAY.
        lines.add(Component.translatable("gui.mcpskins.armory.rarity_" + entry.rarity().name().toLowerCase(Locale.ROOT))
                .withStyle(s -> s.withColor(entry.rarity().accentColor)));
        if (!unlocked) {
            lines.add(Component.translatable("gui.mcpskins.armory.status_locked").withStyle(ChatFormatting.RED));
        }
        guiGraphics.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
    }

    // -----------------------------------------------------------------------------------
    // Ввод: мышь
    // -----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) return false;

        Layout layout = computeLayout();

        for (StatusFilter filter : StatusFilter.values()) {
            if (statusPillRect(layout, filter).contains(mouseX, mouseY)) {
                statusFilter = filter;
                playClick();
                refreshVisibleEntries();
                return true;
            }
        }
        if (customModelToggleRect(layout).contains(mouseX, mouseY)) {
            customModelOnly = !customModelOnly;
            playClick();
            refreshVisibleEntries();
            return true;
        }
        if (sortButtonRect(layout).contains(mouseX, mouseY)) {
            sortMode = nextSortMode(sortMode);
            playClick();
            refreshVisibleEntries();
            return true;
        }

        if (mouseX >= layout.leftX() && mouseX < layout.leftX() + layout.leftWidth()
                && mouseY >= layout.leftY() && mouseY < layout.leftY() + layout.leftHeight()) {
            int relativeY = (int) (mouseY - layout.leftY() + weaponScrollPixels);
            int index = relativeY / WEAPON_ROW_HEIGHT;
            if (index >= 0 && index < weaponKeys.size()) {
                focusPane = FocusPane.WEAPONS;
                selectWeapon(weaponKeys.get(index));
                playClick();
            }
            return true;
        }

        if (podium.isInBounds(mouseX, mouseY)) {
            podium.onMouseClicked();
            return true;
        }

        if (mouseX >= layout.rightX() && mouseX < layout.rightX() + layout.rightWidth()
                && mouseY >= layout.rightY() && mouseY < layout.rightY() + layout.rightHeight()) {
            int index = gridIndexAt(layout, mouseX, mouseY);
            if (index >= 0) {
                selectedSkinIndex = index;
                focusPane = FocusPane.SKINS;
                updatePodiumStack();
                playClick();
            }
            return true;
        }

        if (equipButtonRect(layout).contains(mouseX, mouseY)) {
            equipSelected();
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && podium.isInBounds(mouseX, mouseY)) {
            podium.onMouseDragged(dragX, dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            podium.onMouseReleased();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Layout layout = computeLayout();

        if (podium.isInBounds(mouseX, mouseY)) {
            podium.onMouseScrolled(scrollY);
            return true;
        }
        if (mouseX >= layout.rightX() && mouseX < layout.rightX() + layout.rightWidth()
                && mouseY >= layout.rightY() && mouseY < layout.rightY() + layout.rightHeight()) {
            gridScrollPixels = Mth.clamp(gridScrollPixels - (int) (scrollY * 24), 0, maxGridScroll(layout));
            return true;
        }
        if (mouseX >= layout.leftX() && mouseX < layout.leftX() + layout.leftWidth()
                && mouseY >= layout.leftY() && mouseY < layout.leftY() + layout.leftHeight()) {
            int maxScroll = Math.max(0, weaponKeys.size() * WEAPON_ROW_HEIGHT - layout.leftHeight());
            weaponScrollPixels = Mth.clamp(weaponScrollPixels - (int) (scrollY * 24), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // -----------------------------------------------------------------------------------
    // Ввод: клавиатура (см. javadoc Item3DPodiumWidget про C/V - переключение контекста
    // рендера/флипа оси Y подиума)
    // -----------------------------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (keyCode == GLFW.GLFW_KEY_C) {
            podium.cycleContext();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_V) {
            podium.toggleYFlip();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            focusPane = focusPane == FocusPane.WEAPONS ? FocusPane.SKINS : FocusPane.WEAPONS;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN
                || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            handleNavigation(keyCode);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (focusPane == FocusPane.SKINS) {
                equipSelected();
            } else {
                focusPane = FocusPane.SKINS;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void handleNavigation(int keyCode) {
        if (focusPane == FocusPane.WEAPONS) {
            if (weaponKeys.isEmpty()) return;
            int index = Math.max(0, weaponKeys.indexOf(selectedWeapon));
            if (keyCode == GLFW.GLFW_KEY_UP) index = Math.max(0, index - 1);
            else if (keyCode == GLFW.GLFW_KEY_DOWN) index = Math.min(weaponKeys.size() - 1, index + 1);
            selectWeapon(weaponKeys.get(index));
        } else {
            if (visibleEntries.isEmpty()) return;
            Layout layout = computeLayout();
            int columns = layout.gridColumns();
            int index = selectedSkinIndex;
            if (keyCode == GLFW.GLFW_KEY_LEFT) index -= 1;
            else if (keyCode == GLFW.GLFW_KEY_RIGHT) index += 1;
            else if (keyCode == GLFW.GLFW_KEY_UP) index -= columns;
            else if (keyCode == GLFW.GLFW_KEY_DOWN) index += columns;
            selectedSkinIndex = Mth.clamp(index, 0, visibleEntries.size() - 1);
            updatePodiumStack();
            scrollGridToSelection(layout);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    // -----------------------------------------------------------------------------------
    // Состояние / данные
    // -----------------------------------------------------------------------------------

    private void rebuildWeaponList() {
        weaponKeys.clear();
        weaponKeys.addAll(SkinManager.INSTANCE.getRegistry().keySet());
        weaponKeys.sort(Comparator.comparing(this::weaponDisplayName, String.CASE_INSENSITIVE_ORDER));
    }

    private String defaultWeaponSelection() {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            String heldMain = TACZSkinHelper.getGunId(player.getMainHandItem());
            if (heldMain != null && weaponKeys.contains(heldMain)) return heldMain;
            String heldOff = TACZSkinHelper.getGunId(player.getOffhandItem());
            if (heldOff != null && weaponKeys.contains(heldOff)) return heldOff;
        }
        return weaponKeys.isEmpty() ? null : weaponKeys.get(0);
    }

    private void selectWeapon(String baseGun) {
        this.selectedWeapon = baseGun;
        this.selectedSkinIndex = 0;
        this.gridScrollPixels = 0;
        refreshVisibleEntries();
    }

    /**
     * Пересобирает {@link #visibleEntries} с учётом поиска/фильтров/сортировки - см. §6
     * концепта. Поиск (если строка не пуста) намеренно ГЛОБАЛЬНЫЙ - по всем оружиям сразу
     * (как в Steam Market), а не только по выбранному в левой колонке; если строка пуста,
     * сетка справа возвращается к обычному режиму "скины выбранного оружия".
     */
    private void refreshVisibleEntries() {
        List<SkinDataModels.SkinLookupResult> result = new ArrayList<>();
        Player player = Minecraft.getInstance().player;
        String query = searchBox != null ? searchBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        boolean globalSearch = !query.isEmpty();

        Collection<SkinDataModels.WeaponSkins> weaponsToScan;
        if (globalSearch) {
            weaponsToScan = SkinManager.INSTANCE.getRegistry().values();
        } else if (selectedWeapon != null && SkinManager.INSTANCE.getRegistry().containsKey(selectedWeapon)) {
            weaponsToScan = List.of(SkinManager.INSTANCE.getRegistry().get(selectedWeapon));
        } else {
            weaponsToScan = List.of();
        }

        for (SkinDataModels.WeaponSkins weapon : weaponsToScan) {
            for (SkinDataModels.SkinEntry entry : weapon.skins()) {
                boolean unlocked = player != null && SkinAttachment.hasSkin(player, entry.id());
                if (statusFilter == StatusFilter.OWNED && !unlocked) continue;
                if (statusFilter == StatusFilter.LOCKED && unlocked) continue;

                if (globalSearch) {
                    boolean nameMatch = entry.name().toLowerCase(Locale.ROOT).contains(query);
                    boolean weaponMatch = weaponDisplayName(weapon.baseGun()).toLowerCase(Locale.ROOT).contains(query);
                    if (!nameMatch && !weaponMatch) continue;
                }
                if (customModelOnly && !hasCustomModel(weapon, entry)) continue;

                result.add(new SkinDataModels.SkinLookupResult(weapon, entry));
            }
        }

        Comparator<SkinDataModels.SkinLookupResult> byName =
                Comparator.comparing(r -> r.skin().name(), String.CASE_INSENSITIVE_ORDER);
        Comparator<SkinDataModels.SkinLookupResult> sortModeComparator = switch (sortMode) {
            case ALPHABETICAL -> byName;
            case NEWEST -> Comparator.<SkinDataModels.SkinLookupResult>comparingInt(r -> r.skin().isNew() ? 0 : 1).thenComparing(byName);
            case RARITY -> Comparator.<SkinDataModels.SkinLookupResult>comparingInt(r -> r.skin().rarity().ordinal()).reversed().thenComparing(byName);
        };
        // Дефолтный (базовый, без кастомной модели) скин оружия всегда должен идти ПЕРВЫМ в
        // правой панели, независимо от выбранного режима сортировки - иначе он мог оказаться
        // где-то в середине/конце списка, что и было исходной проблемой. Это первичный ключ
        // сортировки (comparingInt 0/1 доминирует над всем остальным), а внутри "не дефолтных"
        // и внутри "дефолтных" группы (на практике дефолтный скин один) применяется обычный
        // sortModeComparator как раньше.
        Comparator<SkinDataModels.SkinLookupResult> comparator =
                Comparator.<SkinDataModels.SkinLookupResult>comparingInt(r -> isDefaultSkin(r) ? 0 : 1)
                        .thenComparing(sortModeComparator);
        result.sort(comparator);

        this.visibleEntries = result;
        this.selectedSkinIndex = visibleEntries.isEmpty() ? 0 : Mth.clamp(selectedSkinIndex, 0, visibleEntries.size() - 1);
        this.statusMessage = null;
        updatePodiumStack();
    }

    private void updatePodiumStack() {
        if (visibleEntries.isEmpty()) {
            podium.setStack(ItemStack.EMPTY);
            return;
        }
        SkinDataModels.SkinLookupResult lookup = visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1));
        podium.setStack(TACZSkinHelper.createGunStack(lookup.weapon().baseGun(), lookup.skin().id()));
        // Если глобальный поиск выбрал скин с ДРУГОГО оружия, чем то, что было в левой
        // колонке - синхронизируем подсветку колонки с тем, что реально показано.
        this.selectedWeapon = lookup.weapon().baseGun();
    }

    private void scrollGridToSelection(Layout layout) {
        if (visibleEntries.isEmpty()) return;
        int columns = layout.gridColumns();
        int cell = layout.gridCellSize();
        int spacing = layout.gridSpacing();
        int row = selectedSkinIndex / columns;
        int cellTop = row * (cell + spacing);
        int cellBottom = cellTop + cell;
        int viewHeight = layout.rightHeight() - layout.gridPaddingY() * 2;

        if (cellTop < gridScrollPixels) {
            gridScrollPixels = cellTop;
        } else if (cellBottom > gridScrollPixels + viewHeight) {
            gridScrollPixels = cellBottom - viewHeight;
        }
        gridScrollPixels = Mth.clamp(gridScrollPixels, 0, maxGridScroll(layout));
    }

    private int maxGridScroll(Layout layout) {
        int columns = layout.gridColumns();
        int rows = (int) Math.ceil(visibleEntries.size() / (double) columns);
        int contentHeight = rows * (layout.gridCellSize() + layout.gridSpacing());
        int viewHeight = layout.rightHeight() - layout.gridPaddingY() * 2;
        return Math.max(0, contentHeight - viewHeight);
    }

    private int gridIndexAt(Layout layout, double mouseX, double mouseY) {
        if (mouseX < layout.rightX() || mouseX >= layout.rightX() + layout.rightWidth()) return -1;
        if (mouseY < layout.rightY() || mouseY >= layout.rightY() + layout.rightHeight()) return -1;

        int cell = layout.gridCellSize();
        int spacing = layout.gridSpacing();
        int columns = layout.gridColumns();
        int startX = layout.rightX() + layout.gridPaddingX();
        int startY = layout.rightY() + layout.gridPaddingY() - gridScrollPixels;

        int relX = (int) (mouseX - startX);
        int relY = (int) (mouseY - startY);
        if (relX < 0 || relY < 0) return -1;

        int col = relX / (cell + spacing);
        int row = relY / (cell + spacing);
        if (col >= columns) return -1;
        if (relX - col * (cell + spacing) > cell) return -1;
        if (relY - row * (cell + spacing) > cell) return -1;

        int index = row * columns + col;
        return index >= 0 && index < visibleEntries.size() ? index : -1;
    }

    // -----------------------------------------------------------------------------------
    // Экипировка
    // -----------------------------------------------------------------------------------

    /**
     * В отличие от {@code TACZRefitSkinOverlay}, этот экран НЕ требует держать оружие в руке,
     * чтобы им пользоваться (см. §3 концепта) - листать каталог и вращать 3D-модель можно
     * всегда, т.к. подиум показывает синтетический превью-стак, а не реальный предмет игрока.
     * Требование "оружие в руке" появляется ТОЛЬКО в момент нажатия "Экипировать", потому что
     * именно так устроен существующий серверный протокол ({@code ApplySkinPayload} применяет
     * скин к {@code player.getMainHandItem()}) - переписывать этот протокол на "применить к
     * любому стаку в инвентаре" уже выходит за рамки просмотрщика и является отдельной, более
     * рискованной задачей (пришлось бы сканировать/трогать чужой инвентарь по сети). Вместо
     * этого при отсутствии нужного оружия в руке мы честно объясняем это в статус-строке -
     * тот же принцип "не крашиться и не делать вид, что сработало", что и везде в проекте.
     */
    private void equipSelected() {
        if (visibleEntries.isEmpty()) return;
        SkinDataModels.SkinLookupResult lookup = visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1));
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        if (!SkinAttachment.hasSkin(player, lookup.skin().id())) {
            statusMessage = Component.translatable("gui.mcpskins.armory.status_locked").getString();
            playFail();
            return;
        }

        InteractionHand hand = resolveHand(player, lookup.weapon().baseGun());
        if (hand == null) {
            statusMessage = Component.translatable("gui.mcpskins.armory.status_need_hand",
                    weaponDisplayName(lookup.weapon().baseGun())).getString();
            playFail();
            return;
        }

        // Тот же оптимистичный клиентский приём, что и в TACZRefitSkinOverlay - мгновенно
        // проставляем компонент скина локально, не дожидаясь ответа сервера, который следом
        // всё равно пришлёт авторитетное значение и перезапишет то же самое.
        ItemStack held = player.getItemInHand(hand);
        ItemStack optimistic = TACZSkinHelper.applySkin(held, lookup.skin().id());
        if (!optimistic.isEmpty()) {
            player.setItemInHand(hand, optimistic);
        }
        PacketDistributor.sendToServer(new ApplySkinPayload(lookup.skin().id()));
        statusMessage = null;
        player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.4f);
    }

    private boolean canEquipSelected() {
        if (visibleEntries.isEmpty()) return false;
        SkinDataModels.SkinLookupResult lookup = visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1));
        Player player = Minecraft.getInstance().player;
        if (player == null) return false;
        if (!SkinAttachment.hasSkin(player, lookup.skin().id())) return false;
        return resolveHand(player, lookup.weapon().baseGun()) != null;
    }

    private Component equipButtonLabel() {
        if (!visibleEntries.isEmpty()) {
            SkinDataModels.SkinLookupResult lookup = visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1));
            if (isSkinCurrentlyEquipped(lookup)) {
                return Component.translatable("gui.mcpskins.armory.equipped");
            }
        }
        return Component.translatable("gui.mcpskins.armory.equip");
    }

    private InteractionHand resolveHand(Player player, String baseGun) {
        if (baseGun.equals(TACZSkinHelper.getGunId(player.getMainHandItem()))) return InteractionHand.MAIN_HAND;
        if (baseGun.equals(TACZSkinHelper.getGunId(player.getOffhandItem()))) return InteractionHand.OFF_HAND;
        return null;
    }

    private boolean isWeaponCurrentlyHeld(String baseGun) {
        Player player = Minecraft.getInstance().player;
        return player != null && resolveHand(player, baseGun) != null;
    }

    private boolean isSkinCurrentlyEquipped(SkinDataModels.SkinLookupResult lookup) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return false;
        InteractionHand hand = resolveHand(player, lookup.weapon().baseGun());
        if (hand == null) return false;
        ItemStack held = player.getItemInHand(hand);
        String equippedSkinId = TACZSkinHelper.getSkinId(held);
        String normalizedEquipped = equippedSkinId == null ? lookup.weapon().baseGun() : equippedSkinId;
        return normalizedEquipped.equals(TACZSkinHelper.bareSkinId(lookup.skin().id()));
    }

    // -----------------------------------------------------------------------------------
    // Вспомогательное
    // -----------------------------------------------------------------------------------

    private String weaponDisplayName(String baseGun) {
        return weaponNameCache.computeIfAbsent(baseGun, key -> {
            ItemStack stack = TACZSkinHelper.createGunStack(key);
            return stack.isEmpty() ? key : stack.getHoverName().getString();
        });
    }

    /**
     * Бейдж "Custom model" (см. §3/§4.3 концепта) - проверяется ТЕМ ЖЕ путём, что и реальный
     * рендер ({@code SkinAssetResolver.resolveModel}), поэтому бейдж никогда не "врёт" о том,
     * есть ли у скина отдельная geo-модель. Результат кэшируется на весь сеанс экрана - вызывать
     * {@code TimelessAPI.getGunDisplay(...)} на каждый кадр для каждой видимой ячейки сетки было
     * бы расточительно (см. аналогичную заботу о производительности в {@code SkinAssetResolver}).
     */
    /**
     * "Дефолтный" скин оружия - тот, чей {@code bareSkinId(id)} совпадает с {@code baseGun}
     * самого оружия (тот же признак, что {@link #hasCustomModel} использует, чтобы понять
     * "это базовый вид ствола, без отдельной geo-модели"). Используется, чтобы принудительно
     * поставить такой скин первым в правой панели (см. {@link #refreshVisibleEntries}).
     */
    private boolean isDefaultSkin(SkinDataModels.SkinLookupResult lookup) {
        return TACZSkinHelper.bareSkinId(lookup.skin().id()).equals(lookup.weapon().baseGun());
    }

    private boolean hasCustomModel(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry entry) {
        String bare = TACZSkinHelper.bareSkinId(entry.id());
        if (bare.equals(weapon.baseGun())) return false;

        String cacheKey = weapon.baseGun() + '\u0000' + bare;
        Boolean cached = customModelCache.get(cacheKey);
        if (cached != null) return cached;

        boolean result = false;
        try {
            ItemStack bareStack = TACZSkinHelper.createGunStack(weapon.baseGun());
            Optional<GunDisplayInstance> base = TimelessAPI.getGunDisplay(bareStack);
            if (base.isPresent()) {
                ResourceLocation baseModelLocation = GunModelPatcher.getBaseModelLocation(base.get());
                result = baseModelLocation != null && SkinAssetResolver.resolveModel(baseModelLocation, bare) != null;
            }
        } catch (Exception ignored) {
            // Тихий даунгрейд (см. §4.3 концепта) - бейдж просто не покажется, экран не падает.
        }
        customModelCache.put(cacheKey, result);
        return result;
    }

    private int currentAccentColor() {
        if (visibleEntries.isEmpty()) return 0x5FD3FF;
        return visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1)).skin().labelColor();
    }

    private Component statusFilterLabel(StatusFilter filter) {
        return switch (filter) {
            case ALL -> Component.translatable("gui.mcpskins.armory.filter_all");
            case OWNED -> Component.translatable("gui.mcpskins.armory.filter_owned");
            case LOCKED -> Component.translatable("gui.mcpskins.armory.filter_locked");
        };
    }

    private Component sortModeLabel(SortMode mode) {
        return switch (mode) {
            case RARITY -> Component.translatable("gui.mcpskins.armory.sort_rarity");
            case ALPHABETICAL -> Component.translatable("gui.mcpskins.armory.sort_alphabetical");
            case NEWEST -> Component.translatable("gui.mcpskins.armory.sort_newest");
        };
    }

    private SortMode nextSortMode(SortMode mode) {
        SortMode[] values = SortMode.values();
        return values[(mode.ordinal() + 1) % values.length];
    }

    private void playClick() {
        Player player = Minecraft.getInstance().player;
        if (player != null) player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.2f);
    }

    private void playFail() {
        Player player = Minecraft.getInstance().player;
        if (player != null) player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 0.7f);
    }
}