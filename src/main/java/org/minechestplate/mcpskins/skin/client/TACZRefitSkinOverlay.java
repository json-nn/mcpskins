package org.minechestplate.mcpskins.skin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinComponents;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;
import org.minechestplate.mcpskins.skin.network.ApplySkinPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Встраивает просмотр/выбор скинов прямо в родной экран доработки оружия TACZ
 * (в терминологии TACZ - "refit screen", класс {@code com.tacz.guns.client.gui.GunRefitScreen}).
 *
 * <p><b>ПЕРЕХОД НА ТЕКСТУРНЫЙ ОВЕРЛЕЙ:</b> раньше "какой скин надет" определялось тем,
 * НА КАКОЙ GunId подменено оружие (скин == отдельная зарегистрированная в TACZ пушка).
 * Теперь GunId оружия в руке ВООБЩЕ НИКОГДА не меняется - ни при настоящем применении
 * скина (см. {@link TACZSkinHelper#applySkin}), ни при клиентском предпросмотре ниже.
 * Вместо этого "какой скин надет" читается из компонента {@link SkinComponents#SKIN_ID}
 * через {@link TACZSkinHelper#getSkinId(ItemStack)} - миксин
 * {@code org.minechestplate.mcpskins.mixin.TimelessAPIMixin} сам подменяет только
 * текстуру (и, опционально, иконку предмета - см. javadoc миксина), если для (gunId,
 * skinId) в активных ресурспаках есть соответствующие PNG.
 *
 * <p><b>Почему предпросмотр стал сильно проще:</b> раньше для показа непроверенного
 * скина приходилось временно подменять GunId РЕАЛЬНОГО предмета в руке на отдельную
 * скин-пушку и потом аккуратно возвращать обратно. Теперь предпросмотр - это просто
 * временная запись/удаление того же самого компонента SKIN_ID на клиентской копии
 * предмета в руке, БЕЗ единого пакета на сервер - ровно тот же код-путь, что и у
 * настоящего применения скина, только не отправленный на сервер и не влияющий на
 * владение скином.
 *
 * <p><b>ИСПРАВЛЕНИЕ "ИСЧЕЗАЮЩЕЙ КНОПКИ" (важно, если правите этот класс дальше):</b>
 * раньше кнопка-переключатель добавлялась ОДИН РАЗ как обычный {@code Button}-виджет
 * через {@code event.addListener(...)} внутри {@link #onScreenInit}. Проблема: у
 * {@code GunRefitScreen} переключение вкладок обвеса (GRIP/SCOPE/MUZZLE/...) само чистит
 * и заново строит СВОЙ список виджетов, но делает это в обход полноценного повторного
 * {@code Screen.init()} - то есть событие {@code ScreenEvent.Init.Post}, в котором кнопка
 * добавлялась, повторно не приходит, а сам виджет к этому моменту уже стёрт внутренней
 * логикой экрана. Итог - кнопка бесследно пропадает при первом же клике по любой другой
 * вкладке обвеса.
 * <p>
 * Карусель скинов и тост-уведомление в этом же классе НИКОГДА не страдали от этой
 * проблемы, потому что они не виджеты Screen'а, а просто рисуются вручную в
 * {@link #onScreenRenderPost} и обрабатываются вручную в {@link #onMouseClicked} - то есть
 * не зависят от того, что там делает со своим списком виджетов сам GunRefitScreen.
 * Поэтому кнопка-переключатель теперь устроена ТАК ЖЕ: она больше не {@code Button}-виджет,
 * а обычные пиксели, которые мы сами рисуем каждый кадр ({@link #renderToggleButton}) и сами
 * же хит-тестим на клик ({@link #onMouseClicked}, самая первая проверка, ДО проверки
 * {@code skinModeActive} - иначе кнопку нельзя было бы включить обратно). Так она переживает
 * любое количество переключений вкладок обвеса, потому что просто ничего не хранит внутри
 * Screen'а, откуда её могли бы стереть.
 *
 * <p><b>Важные допущения (проверьте после установки, при необходимости поправьте):</b>
 * <ul>
 *     <li>Экран рефита у TACZ всегда работает с оружием, которое сейчас в руке игрока
 *     (основной или второй руке) - см. {@link #getViewedGunStack()}. Если ваш форк
 *     открывает рефит иначе (например, через слот меню верстака), этот метод нужно
 *     переписать под вашу реализацию.</li>
 *     <li>Кнопка переключения ({@link #TOGGLE_MARGIN_TOP}/{@link #TOGGLE_MARGIN_RIGHT}) размещена
 *     "на глаз" по скриншоту - подвиньте константы, если она перекрывает родные иконки.</li>
 * </ul>
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public class TACZRefitSkinOverlay {

    // Полное имя класса родного экрана TACZ, за которым мы следим.
    private static final String GUN_REFIT_SCREEN_CLASS = "com.tacz.guns.client.gui.GunRefitScreen";

    // ---- Настройка расположения (подгоните под свой TACZ-пак/разрешение) --------------
    private static final int TOGGLE_SIZE = 20;
    private static final int TOGGLE_MARGIN_RIGHT = 8;
    private static final int TOGGLE_MARGIN_TOP = 108;

    private static final int CAROUSEL_HEIGHT = 96;
    private static final int CAROUSEL_SLOT_BASE = 44;
    private static final int CAROUSEL_SPACING = 60;
    private static final int PANEL_BOTTOM_MARGIN = 14;
    private static final int PANEL_FADE_HEIGHT = 24;

    // ---- Тост-уведомление о РЕАЛЬНОМ применении скина (не о предпросмотре) -------------
    private static final long TOAST_DURATION_MS = 2200L;
    private static final long TOAST_FADE_MS = 350L;
    // Component, а не String - чтобы тост мог быть переводимым (Font.width(FormattedText)
    // и GuiGraphics.drawCenteredString(..., Component, ...) прекрасно работают с Component).
    private static Component toastText = null;
    private static long toastStartTime = 0L;

    // ---- Состояние карусели (одно активное окно рефита за раз, поэтому статик достаточно) --
    private static boolean skinModeActive = false;
    private static int focusedSkinIndex = 0;
    private static float animatedSkinIndex = 0f;
    // Последний виденный "надетый" bare skin id (или baseGun, если скина нет) - нужен,
    // чтобы отличить "оружие/скин реально сменились" от "игрок просто листает карусель".
    private static String lastSeenSkinId = null;

    // ---- Состояние клиентского предпросмотра (без разблокировки/выдачи скина) ---------
    // previewOriginalSkinId - настоящий (серверный) bare skin id оружия (или null, если
    // скина не было), каким он был ДО того, как мы временно подменили компонент SKIN_ID
    // на клиенте ради предпросмотра. previewActive=false означает, что предпросмотр сейчас
    // неактивен (используем отдельный флаг, а не null-проверку - у "нет скина" и "нет
    // предпросмотра" разный смысл, и оба легитимно бывают null).
    private static boolean previewActive = false;
    private static String previewOriginalSkinId = null;
    private static String previewedSkinId = null;
    private static InteractionHand previewHand = null;

    private TACZRefitSkinOverlay() {
    }

    // -----------------------------------------------------------------------------------
    // Инициализация экрана - только синхронизация фокуса карусели.
    // Кнопка-переключатель здесь больше НЕ регистрируется как виджет - см. javadoc класса,
    // раздел "ИСПРАВЛЕНИЕ ИСЧЕЗАЮЩЕЙ КНОПКИ" и renderToggleButton()/onMouseClicked() ниже.
    // -----------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;

        // Центрируем карусель на текущем реально надетом скине при (пере)открытии экрана.
        // Во время активного предпросмотра фокус карусели не трогаем (см. проверку внутри
        // syncFocusedSkinToEquipped) - иначе он бы сбрасывался при каждом клике по вкладке
        // GRIP/SCOPE/... пока игрок разглядывает скин.
        syncFocusedSkinToEquipped();
    }

    // "✕"/"SK" намеренно НЕ переведены (Component.literal, не translatable): это
    // иконка-глиф в квадратной кнопке 20x20px, а не предложение - перевод "SK" на
    // другой язык обычно даёт более длинную строку и просто не влезет в кнопку.
    // Тултип при наведении на кнопку (см. renderToggleButton) переведён полностью.
    private static Component toggleLabel() {
        return Component.literal(skinModeActive ? "✕" : "SK");
    }

    /**
     * init() у GunRefitScreen дёргается многократно за сессию (при каждом переключении
     * вкладки атачмента), поэтому возврат настоящего скина после предпросмотра завязан
     * именно на закрытие экрана целиком, а не на его (пере)инициализацию.
     */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!isGunRefitScreen(event.getScreen())) return;
        restorePreviewIfActive();
    }

    // -----------------------------------------------------------------------------------
    // Отрисовка карусели, тоста и кнопки-переключателя поверх родного экрана
    // -----------------------------------------------------------------------------------

    // priority = LOWEST: этот метод и так вызывается уже ПОСЛЕ Screen.render() (это
    // Render.Post), но сам GunRefitScreen - не единственный, кто может рисовать поверх
    // экрана в этой же фазе события: TACZ вешает на Render.Post свои собственные
    // хендлеры (иконки атачментов, подсветки вкладок и т.п.), и порядок вызова НЕСКОЛЬКИХ
    // подписчиков одного события между модами определяется приоритетом, а не тем, кто
    // раньше зарегистрировался. При приоритете по умолчанию (NORMAL) TACZ мог отрисоваться
    // ПОСЛЕ нас и лечь поверх тоста/кнопки/карусели - именно это и давало эффект "тост на
    // одном слое с кнопками TACZ и заходит в них". LOWEST гарантирует, что из ВСЕХ
    // подписчиков Render.Post на этом экране мы рисуемся самыми последними.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();

        // Тост о РЕАЛЬНОМ применении скина рисуем независимо от того, открыта ли сейчас
        // карусель - чтобы игрок увидел подтверждение, даже если сразу после клика закрыл
        // панель скинов кнопкой "✕".
        renderToast(guiGraphics, screen);

        // Кнопка-переключатель тоже рисуется ВСЕГДА, пока открыт GunRefitScreen (а не только
        // пока skinModeActive), ровно как и раньше вела себя Button-версия - иначе включить
        // режим скинов будет нечем. Рисуется вручную пикселями - см. javadoc класса.
        renderToggleButton(guiGraphics, screen, mouseX, mouseY);

        if (!skinModeActive) return;

        ItemStack heldGun = getViewedGunStack();
        if (heldGun.isEmpty()) return;

        String baseGun = TACZSkinHelper.getGunId(heldGun);
        if (baseGun == null) return;

        SkinDataModels.WeaponSkins weapon = SkinManager.INSTANCE.getRegistry().get(baseGun);
        if (weapon == null || weapon.skins().isEmpty()) return;

        String equippedSkinId = normalizeEquipped(getRealSkinId(), baseGun);

        if (!previewActive && !equippedSkinId.equals(lastSeenSkinId)) {
            // Сменилось оружие (или скин применён по-настоящему) - перецентрируем карусель.
            // Во время активного предпросмотра фокус карусели намеренно не трогаем.
            centerOnSkin(weapon, equippedSkinId);
        }

        renderPanel(guiGraphics, screen, weapon, equippedSkinId, mouseX, mouseY);
    }

    /**
     * Границы кнопки-переключателя в экранных координатах (те же, в которых приходят
     * {@code event.getMouseX()/getMouseY()} у ScreenEvent). Вынесено в отдельный метод, чтобы
     * рендер ({@link #renderToggleButton}) и хит-тест клика ({@link #onMouseClicked}) не могли
     * разойтись между собой.
     *
     * @return {x0, y0, size}
     */
    private static int[] toggleButtonBounds(Screen screen) {
        int x = screen.width - TOGGLE_MARGIN_RIGHT - TOGGLE_SIZE;
        int y = TOGGLE_MARGIN_TOP;
        return new int[]{x, y, TOGGLE_SIZE};
    }

    /**
     * Ручная отрисовка кнопки-переключателя - см. javadoc класса, раздел "ИСПРАВЛЕНИЕ
     * ИСЧЕЗАЮЩЕЙ КНОПКИ". Визуально повторяет то, что раньше рисовал ванильный Button
     * (прямоугольник + centered-строка), плюс лёгкая подсветка под курсором и рамка другого
     * цвета, когда режим скинов включён - чтобы состояние было видно без чтения символа.
     */
    private static void renderToggleButton(GuiGraphics guiGraphics, Screen screen, int mouseX, int mouseY) {
        int[] bounds = toggleButtonBounds(screen);
        int x0 = bounds[0], y0 = bounds[1], size = bounds[2];
        boolean hovered = mouseX >= x0 && mouseX <= x0 + size && mouseY >= y0 && mouseY <= y0 + size;

        int bg = skinModeActive ? 0xE02A2020 : (hovered ? 0xE02A2A2A : 0xC0151515);
        int borderRgb = skinModeActive ? 0xFFB347 : (hovered ? 0xAAAAAA : 0x5FD3FF);

        guiGraphics.fill(x0, y0, x0 + size, y0 + size, bg);
        guiGraphics.renderOutline(x0, y0, size, size, 0xFF000000 | borderRgb);

        Minecraft mc = Minecraft.getInstance();
        Component label = toggleLabel();
        int textY = y0 + (size - mc.font.lineHeight) / 2 + 1;
        guiGraphics.drawCenteredString(mc.font, label, x0 + size / 2, textY, 0xFFFFFFFF);

        if (hovered) {
            guiGraphics.renderTooltip(mc.font, Component.translatable("gui.mcpskins.weapon_skins_tooltip"), mouseX, mouseY);
        }
    }

    private static void renderToast(GuiGraphics guiGraphics, Screen screen) {
        if (toastText == null) return;
        long elapsed = System.currentTimeMillis() - toastStartTime;
        if (elapsed > TOAST_DURATION_MS) {
            toastText = null;
            return;
        }

        float alpha;
        if (elapsed < TOAST_FADE_MS) {
            alpha = elapsed / (float) TOAST_FADE_MS;
        } else if (elapsed > TOAST_DURATION_MS - TOAST_FADE_MS) {
            alpha = (TOAST_DURATION_MS - elapsed) / (float) TOAST_FADE_MS;
        } else {
            alpha = 1f;
        }
        alpha = Mth.clamp(alpha, 0f, 1f);

        Minecraft mc = Minecraft.getInstance();
        int textWidth = mc.font.width(toastText);
        int paddingX = 10, paddingY = 5;
        int boxW = textWidth + paddingX * 2;
        int boxH = mc.font.lineHeight + paddingY * 2;
        int x0 = screen.width / 2 - boxW / 2;
        int y0 = 8;

        int bgAlpha = Math.round(alpha * 0xD0) << 24;
        int borderAlpha = Math.round(alpha * 255) << 24;
        int textAlpha = Math.round(alpha * 255) << 24;

        // Отключаем depth-тест и дополнительно выносим тост далеко вперёд по Z на время
        // отрисовки. Приоритет LOWEST у onScreenRenderPost (см. его javadoc) уже чинит
        // порядок между РАЗНЫМИ подписчиками Render.Post, но depth-буфер - это отдельная,
        // более низкоуровневая вещь: если TACZ где-то в своём render() рисует что-то через
        // 3D-конвейер (например, превью модели оружия или иконки атачментов через
        // GuiGraphics.renderItem/renderFakeItem) с включённым depth-тестом, в буфере глубины
        // могут остаться значения, из-за которых наши обычные 2D-квады (fill/drawString,
        // тоже проходящие depth-тест по LEQUAL в этой версии MC) не пройдут проверку и
        // окажутся "под" уже нарисованными пикселями TACZ, даже несмотря на правильный
        // порядок вызовов. RenderSystem.disableDepthTest() снимает эту зависимость: тост
        // рисуется поверх содержимого буфера цвета как есть, без сравнения глубины.
        RenderSystem.disableDepthTest();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 900.0F);

        guiGraphics.fill(x0, y0, x0 + boxW, y0 + boxH, bgAlpha | 0x101418);
        guiGraphics.fill(x0, y0, x0 + boxW, y0 + 1, borderAlpha | 0x5FD3FF);
        guiGraphics.drawCenteredString(mc.font, toastText, screen.width / 2, y0 + paddingY, textAlpha | 0xFFFFFF);

        guiGraphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    private static void renderPanel(GuiGraphics guiGraphics, Screen screen, SkinDataModels.WeaponSkins weapon,
                                    String equippedSkinId, int mouseX, int mouseY) {
        int width = screen.width;
        int height = screen.height;
        int panelTop = height - CAROUSEL_HEIGHT - PANEL_BOTTOM_MARGIN;
        int centerY = panelTop + CAROUSEL_HEIGHT / 2;
        int centerX = width / 2;

        // Полупрозрачная плашка-подложка, чтобы карусель читалась поверх 3D-модели/мира.
        guiGraphics.fillGradient(0, panelTop - PANEL_FADE_HEIGHT, width, panelTop, 0x00000000, 0x9A000000);
        guiGraphics.fill(0, panelTop, width, height, 0xB4000000);
        guiGraphics.fill(0, panelTop, width, panelTop + 1, 0x405FD3FF);

        animatedSkinIndex += (focusedSkinIndex - animatedSkinIndex) * 0.35f;
        if (Math.abs(focusedSkinIndex - animatedSkinIndex) < 0.01f) animatedSkinIndex = focusedSkinIndex;

        List<CarouselSlot> slots = computeSlots(weapon, centerX, centerY);
        Minecraft mc = Minecraft.getInstance();

        // Плавная пульсация для рамки надетого/просматриваемого скина в центре карусели.
        float pulse = 0.5f + 0.5f * Mth.sin((System.currentTimeMillis() % 1200L) / 1200f * ((float) Math.PI * 2f));

        for (CarouselSlot slot : slots) {
            SkinDataModels.SkinEntry entry = weapon.skins().get(slot.skinIndex());
            boolean isCurrentlyEquipped = bareId(entry.id()).equals(equippedSkinId);
            boolean unlocked = mc.player != null && SkinAttachment.hasSkin(mc.player, entry.id());
            boolean isPreviewed = previewedSkinId != null && bareId(entry.id()).equals(previewedSkinId);
            boolean isCenter = slot.distance() < 0.05f;

            int half = slot.size() / 2;
            int x0 = slot.centerX() - half, y0 = slot.centerY() - half;
            boolean hovered = mouseX >= x0 && mouseX <= x0 + slot.size() && mouseY >= y0 && mouseY <= y0 + slot.size();
            int alphaByte = Math.round(slot.alpha() * 255) << 24;

            int bg = (isCenter ? 0x2A2A2A : 0x1B1B1B) | alphaByte;
            int borderRgb = isCurrentlyEquipped ? 0x5FD3FF
                    : isPreviewed ? 0xFFB347
                    : (isCenter ? entry.labelColor() : (hovered ? 0xAAAAAA : 0x3A3A3A));
            int borderAlpha = Math.round(slot.alpha() * 255) << 24;

            guiGraphics.fill(x0, y0, x0 + slot.size(), y0 + slot.size(), bg);
            guiGraphics.renderOutline(x0, y0, slot.size(), slot.size(), (borderRgb & 0xFFFFFF) | borderAlpha);
            guiGraphics.fill(x0, y0, x0 + slot.size(), y0 + 2, (entry.labelColor() & 0xFFFFFF) | alphaByte);

            // Пульсирующее внешнее кольцо у надетого/просматриваемого скина в центре -
            // помогает сразу отличить "это правда стоит" от "просто листаю карусель".
            if (isCenter && (isCurrentlyEquipped || isPreviewed)) {
                int glowRgb = isCurrentlyEquipped ? 0x5FD3FF : 0xFFB347;
                int glowAlpha = Math.round(slot.alpha() * (0x40 + Math.round(pulse * 0x60))) << 24;
                guiGraphics.renderOutline(x0 - 2, y0 - 2, slot.size() + 4, slot.size() + 4, (glowRgb & 0xFFFFFF) | glowAlpha);
            }

            // ВАЖНО: миниатюра здесь берётся тем же путём createGunStack -> TimelessAPI ->
            // наш миксин, поэтому если у скина рядом с UV-текстурой лежит необязательный
            // "<skinId>_icon.png" (см. SkinAssetResolver.resolveIcon), миниатюра в карусели
            // ПОДХВАТИТ и его - никакого отдельного кода тут для этого не нужно.
            ItemStack thumb = TACZSkinHelper.createGunStack(weapon.baseGun(), entry.id());
            int iconOffset = (slot.size() - 16) / 2;
            guiGraphics.renderItem(thumb, x0 + iconOffset, y0 + iconOffset);

            if (!unlocked && !isPreviewed) {
                guiGraphics.fill(x0, y0, x0 + slot.size(), y0 + slot.size(), 0x80000000);
                // Маленькая иконка замка в углу слота - "заблокировано" считывается с
                // одного взгляда, а не только по затемнению.
                int lockW = 8, lockH = 8;
                int lx = x0 + slot.size() - lockW - 2;
                int ly = y0 + slot.size() - lockH - 2;
                int lockColor = (Math.round(slot.alpha() * 255) << 24) | 0xE8E8E8;
                guiGraphics.renderOutline(lx + 1, ly, lockW - 2, 4, lockColor);
                guiGraphics.fill(lx, ly + 3, lx + lockW, ly + lockH, lockColor);
            } else if (!unlocked) {
                // Слот сейчас предпросматривается, но не куплен - лёгкая янтарная
                // подсветка вместо тёмного затемнения, чтобы не путать с "недоступно".
                guiGraphics.fill(x0, y0, x0 + slot.size(), y0 + slot.size(), (Math.round(slot.alpha() * 0x30) << 24) | 0xFFB347);
            }

            if (isCenter) {
                Component name = Component.literal(entry.name());
                guiGraphics.drawCenteredString(mc.font, name, centerX, panelTop + 4, entry.labelColor());

                Component status;
                int statusColor;
                if (isCurrentlyEquipped) {
                    status = Component.translatable("gui.mcpskins.status_equipped");
                    statusColor = 0x5FD3FF;
                } else if (isPreviewed) {
                    status = Component.translatable("gui.mcpskins.status_preview_locked");
                    statusColor = 0xFFB347;
                } else if (unlocked) {
                    status = Component.translatable("gui.mcpskins.status_click_to_equip");
                    statusColor = 0xAAAAAA;
                } else {
                    status = Component.translatable("gui.mcpskins.status_click_to_preview");
                    statusColor = 0xFF8080;
                }
                guiGraphics.drawCenteredString(mc.font, status, centerX, panelTop + CAROUSEL_HEIGHT - 12, statusColor);

                // Счётчик "N / всего" в правом верхнем углу панели.
                String counter = (slot.skinIndex() + 1) + " / " + weapon.skins().size();
                guiGraphics.drawString(mc.font, counter, width - mc.font.width(counter) - 8, panelTop + 4, 0x80FFFFFF, false);
            }
        }

        // Стрелки-подсказки по краям панели, если список скинов продолжается за кадром.
        if (focusedSkinIndex > 0) {
            guiGraphics.drawCenteredString(mc.font, Component.literal("‹"), 14, centerY - 4, 0x80FFFFFF);
        }
        if (focusedSkinIndex < weapon.skins().size() - 1) {
            guiGraphics.drawCenteredString(mc.font, Component.literal("›"), width - 14, centerY - 4, 0x80FFFFFF);
        }
    }

    // -----------------------------------------------------------------------------------
    // Обработка ввода: клик по кнопке-переключателю и по слоту карусели
    // -----------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;
        if (event.getButton() != 0) return;

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();

        // Клик по кнопке-переключателю проверяем ПЕРВЫМ и ВСЕГДА, независимо от
        // skinModeActive - иначе включить режим скинов будет попросту нечем (см. javadoc
        // класса, "ИСПРАВЛЕНИЕ ИСЧЕЗАЮЩЕЙ КНОПКИ" - кнопка больше не Button-виджет, поэтому
        // сама себя через стандартный Screen.mouseClicked() уже не обрабатывает).
        int[] bounds = toggleButtonBounds(screen);
        if (mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2]
                && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[2]) {
            skinModeActive = !skinModeActive;
            if (!skinModeActive) {
                // Выходя из режима скинов, обязательно возвращаем оружию его настоящий вид,
                // если в этот момент шёл предпросмотр.
                restorePreviewIfActive();
            }
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.2f);
            }
            event.setCanceled(true);
            return;
        }

        if (!skinModeActive) return;

        ItemStack heldGun = getViewedGunStack();
        if (heldGun.isEmpty()) return;
        String baseGun = TACZSkinHelper.getGunId(heldGun);
        if (baseGun == null) return;
        SkinDataModels.WeaponSkins weapon = SkinManager.INSTANCE.getRegistry().get(baseGun);
        if (weapon == null || weapon.skins().isEmpty()) return;

        int panelTop = screen.height - CAROUSEL_HEIGHT - PANEL_BOTTOM_MARGIN;
        if (mouseY < panelTop - PANEL_FADE_HEIGHT) return; // клик мимо нашей панели вообще

        int centerX = screen.width / 2;
        int centerY = panelTop + CAROUSEL_HEIGHT / 2;

        for (CarouselSlot slot : computeSlots(weapon, centerX, centerY)) {
            int half = slot.size() / 2;
            if (mouseX >= slot.centerX() - half && mouseX <= slot.centerX() + half
                    && mouseY >= slot.centerY() - half && mouseY <= slot.centerY() + half) {
                focusedSkinIndex = slot.skinIndex();
                SkinDataModels.SkinEntry entry = weapon.skins().get(slot.skinIndex());

                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null && SkinAttachment.hasSkin(player, entry.id())) {
                    // Настоящее применение скина идёт через сервер, НО перед отправкой пакета
                    // сразу же оптимистично проставляем тот же компонент SKIN_ID на клиентской
                    // копии предмета в руке - тем же кодом-путём, что и previewLockedSkin().
                    //
                    // Почему это обязательно: если до этого клика шёл предпросмотр ЗАКРЫТОГО
                    // скина, previewLockedSkin() уже подменил компонент SKIN_ID на клиентском
                    // ItemStack локально (без пакета на сервер). clearPreviewState() ниже лишь
                    // сбрасывает служебные флаги previewActive/previewedSkinId и т.п. - он
                    // НИКОГДА не трогал сам ItemStack. Из-за этого клиентский предмет в руке
                    // оставался с компонентом от предпросмотренного скина вплоть до ответного
                    // пакета синхронизации инвентаря с сервера, а это не мгновенно. Проставляя
                    // правильный компонент здесь же, немедленно, убираем этот разрыв - сервер
                    // всё равно следом пришлёт авторитетное значение и перезапишет то же самое.
                    InteractionHand hand = resolveGunHand(player);
                    if (hand != null) {
                        ItemStack heldGunNow = player.getItemInHand(hand);
                        if (!heldGunNow.isEmpty()) {
                            ItemStack optimistic = TACZSkinHelper.applySkin(heldGunNow, entry.id());
                            if (!optimistic.isEmpty()) {
                                player.setItemInHand(hand, optimistic);
                            }
                        }
                    }

                    PacketDistributor.sendToServer(new ApplySkinPayload(entry.id()));
                    clearPreviewState();
                    toastText = Component.translatable("gui.mcpskins.toast_skin_applied", entry.name());
                    toastStartTime = System.currentTimeMillis();
                    player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.4f);
                } else if (player != null) {
                    // Скин не куплен - только клиентский предпросмотр на настоящей
                    // 3D-модели оружия, без обращения к серверу и без выдачи скина.
                    previewLockedSkin(bareId(entry.id()));
                    player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.0f);
                }
                event.setCanceled(true);
                return;
            }
        }

        // Любой другой клик внутри полосы карусели не должен "проваливаться" на слоты
        // атачментов TACZ, которые могут физически находиться под нашей панелью.
        if (mouseY >= panelTop) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!skinModeActive) return;
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;

        int panelTop = screen.height - CAROUSEL_HEIGHT - PANEL_BOTTOM_MARGIN;
        if (event.getMouseY() < panelTop) return;

        ItemStack heldGun = getViewedGunStack();
        if (heldGun.isEmpty()) return;
        String baseGun = TACZSkinHelper.getGunId(heldGun);
        if (baseGun == null) return;
        SkinDataModels.WeaponSkins weapon = SkinManager.INSTANCE.getRegistry().get(baseGun);
        if (weapon == null || weapon.skins().isEmpty()) return;

        focusedSkinIndex = Mth.clamp(focusedSkinIndex - (int) Math.signum(event.getScrollDeltaY()), 0, weapon.skins().size() - 1);
        event.setCanceled(true);
    }

    // -----------------------------------------------------------------------------------
    // Клиентский предпросмотр скина (без разблокировки/выдачи)
    // -----------------------------------------------------------------------------------

    /**
     * Временно записывает/убирает компонент {@link SkinComponents#SKIN_ID} у РЕАЛЬНОГО
     * предмета в руке игрока (только на клиенте, без единого пакета на сервер) - тот же
     * код-путь, что и у настоящего применения скина (см. {@link TACZSkinHelper#applySkin}),
     * поэтому TACZ отрисовывает предпросмотр абсолютно так же, как настоящий надетый скин.
     * Владение скином при этом не меняется - как только предпросмотр завершается
     * ({@link #restorePreviewIfActive()}), оружию возвращается его настоящее значение
     * компонента.
     *
     * @param skinIdBare bare (без "default:") id скина для предпросмотра, либо значение,
     *                   равное baseGun'у оружия - тогда это предпросмотр "без скина".
     */
    private static void previewLockedSkin(String skinIdBare) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        InteractionHand hand = resolveGunHand(mc.player);
        if (hand == null) return;

        ItemStack heldGun = mc.player.getItemInHand(hand);
        if (heldGun.isEmpty()) return;

        if (!previewActive) {
            // Запоминаем настоящее значение компонента ТОЛЬКО перед первой подменой в
            // этой сессии предпросмотра, чтобы потом было куда возвращаться.
            previewOriginalSkinId = TACZSkinHelper.getSkinId(heldGun);
            previewHand = hand;
            previewActive = true;
        }

        ItemStack previewStack = TACZSkinHelper.applySkin(heldGun, skinIdBare);
        if (!previewStack.isEmpty()) {
            mc.player.setItemInHand(hand, previewStack);
            previewedSkinId = skinIdBare;
        }
    }

    /**
     * Возвращает оружию его настоящее значение компонента скина, если сейчас идёт
     * клиентский предпросмотр. Вызывается при закрытии экрана рефита и при выключении
     * режима скинов.
     */
    private static void restorePreviewIfActive() {
        if (!previewActive) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && previewHand != null) {
            ItemStack heldGun = mc.player.getItemInHand(previewHand);
            if (!heldGun.isEmpty()) {
                // previewOriginalSkinId==null значит "скина не было" - applySkin(...) с
                // null корректно уберёт компонент (см. TACZSkinHelper.applySkinComponent).
                ItemStack restored = TACZSkinHelper.applySkin(heldGun, previewOriginalSkinId);
                if (!restored.isEmpty()) {
                    mc.player.setItemInHand(previewHand, restored);
                }
            }
        }
        clearPreviewState();
    }

    private static void clearPreviewState() {
        previewActive = false;
        previewOriginalSkinId = null;
        previewedSkinId = null;
        previewHand = null;
    }

    /**
     * "Настоящий" (серверный) bare skin id оружия, либо {@code null} для "без скина" -
     * используется для определения, что сейчас реально надето. В отличие от прямого
     * чтения {@link #getViewedGunStack()}, во время предпросмотра вернёт не подменённое,
     * а исходное значение.
     */
    private static String getRealSkinId() {
        if (previewActive) return previewOriginalSkinId;
        return TACZSkinHelper.getSkinId(getViewedGunStack());
    }

    /**
     * "Без скина" (skinId == null) для целей сравнения в карусели эквивалентно записи
     * bare-id дефолтной записи скина, который у SkinManager всегда равен самому baseGun -
     * см. {@code SkinManager.apply()}, где дефолтная запись создаётся как
     * {@code "default:" + baseGun}, а {@link #bareId} снимает с неё префикс.
     */
    private static String normalizeEquipped(String skinIdOrNull, String baseGun) {
        return skinIdOrNull == null ? baseGun : skinIdOrNull;
    }

    // -----------------------------------------------------------------------------------
    // Вспомогательные методы
    // -----------------------------------------------------------------------------------

    private static boolean isGunRefitScreen(Screen screen) {
        return screen != null && GUN_REFIT_SCREEN_CLASS.equals(screen.getClass().getName());
    }

    private static InteractionHand resolveGunHand(LocalPlayer player) {
        if (TACZSkinHelper.getGunId(player.getMainHandItem()) != null) return InteractionHand.MAIN_HAND;
        if (TACZSkinHelper.getGunId(player.getOffhandItem()) != null) return InteractionHand.OFF_HAND;
        return null;
    }

    /**
     * Экран рефита TACZ всегда работает с оружием в руке игрока. Проверяем сначала
     * основную руку, затем вторую - на наличие NBT-тега GunId от TACZ.
     */
    private static ItemStack getViewedGunStack() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return ItemStack.EMPTY;
        InteractionHand hand = resolveGunHand(mc.player);
        if (hand == null) return ItemStack.EMPTY;
        return mc.player.getItemInHand(hand);
    }

    private static void syncFocusedSkinToEquipped() {
        if (previewActive) return;
        ItemStack heldGun = getViewedGunStack();
        if (heldGun.isEmpty()) return;
        String baseGun = TACZSkinHelper.getGunId(heldGun);
        if (baseGun == null) return;
        SkinDataModels.WeaponSkins weapon = SkinManager.INSTANCE.getRegistry().get(baseGun);
        if (weapon != null) centerOnSkin(weapon, normalizeEquipped(getRealSkinId(), baseGun));
    }

    private static void centerOnSkin(SkinDataModels.WeaponSkins weapon, String equippedSkinId) {
        lastSeenSkinId = equippedSkinId;
        List<SkinDataModels.SkinEntry> skins = weapon.skins();
        for (int i = 0; i < skins.size(); i++) {
            if (bareId(skins.get(i).id()).equals(equippedSkinId)) {
                focusedSkinIndex = i;
                animatedSkinIndex = i;
                return;
            }
        }
        focusedSkinIndex = 0;
        animatedSkinIndex = 0;
    }

    private static String bareId(String skinId) {
        return skinId.startsWith("default:") ? skinId.substring(8) : skinId;
    }

    // -----------------------------------------------------------------------------------
    // Геометрия карусели (тот же принцип "coverflow", что и в SkinHubScreen)
    // -----------------------------------------------------------------------------------

    private record CarouselSlot(int skinIndex, int centerX, int centerY, int size, float alpha, float distance) {
    }

    private static List<CarouselSlot> computeSlots(SkinDataModels.WeaponSkins weapon, int centerX, int centerY) {
        List<CarouselSlot> slots = new ArrayList<>();
        List<SkinDataModels.SkinEntry> skins = weapon.skins();

        for (int i = 0; i < skins.size(); i++) {
            float offset = i - animatedSkinIndex;
            float dist = Math.abs(offset);
            if (dist > 3.2f) continue;

            float scale = Mth.clamp(1.35f - dist * 0.3f, 0.4f, 1.35f);
            float alpha = Mth.clamp(1.2f - dist * 0.4f, 0f, 1f);
            int size = Math.round(CAROUSEL_SLOT_BASE * scale);
            int cx = centerX + Math.round(offset * CAROUSEL_SPACING);

            slots.add(new CarouselSlot(i, cx, centerY, size, alpha, dist));
        }
        slots.sort(Comparator.comparingDouble((CarouselSlot s) -> -s.distance()));
        return slots;
    }
}