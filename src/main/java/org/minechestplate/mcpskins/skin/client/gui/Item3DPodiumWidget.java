package org.minechestplate.mcpskins.skin.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * "Подиум" - управляемый мышью 3D-просмотр {@link ItemStack} внутри GUI-экрана
 * ({@code SkinArmoryScreen}), реализующий §4.1 концепта "MCPSkins Armory": drag = вращение,
 * колесо = зум, тёмная "студийная" подложка вместо блюра, отказоустойчивый фолбэк на плоскую
 * иконку при любой ошибке рендера.
 *
 * <p><b>ПОЧЕМУ ТЁМНАЯ ПОДЛОЖКА, А НЕ БЛЮР (важно, раз это явно требовалось):</b>
 * <ol>
 *     <li>Ванильный Minecraft начиная с 1.21 умеет размывать фон позади GUI-экранов через
 *     настройку видео "Menu Background Blurriness" - этот эффект включается методом
 *     {@code Screen#renderBackground(...)} -> {@code renderTransparentBackground(...)}, если
 *     у игрока в видеонастройках выставлено ненулевое размытие. {@code SkinArmoryScreen}
 *     СОЗНАТЕЛЬНО не вызывает {@code super.renderBackground(...)} - вместо этого свой фон он
 *     рисует сам, через {@link #renderBackdropFill} (обычная тёмная заливка/градиент), поэтому
 *     блюр-эффект от видеонастройки на наш экран в принципе не может сработать, независимо от
 *     того, что выставлено у игрока в опциях.</li>
 *     <li>Отдельно от вышеописанного, есть ещё и сторонние клиентские моды-блюр (например,
 *     "Blur (Forge)"/"Reblured") - у самого TACZ уже была задокументированная проблема именно
 *     с ними: такой мод ошибочно применял блюр-шейдер поверх {@code GunRefitScreen}, ломая её
 *     визуал, и чинилось только через explicit exclusion-лист в конфиге стороннего мода (см.
 *     issue #25 в трекере MCModderAnchor/TACZ - автор TACZ сам добавил
 *     {@code com.tacz.guns.client.gui.GunRefitScreen} в {@code guiExclusions} блюр-мода). Мы
 *     не можем контролировать сторонние моды игрока, но если у кого-то из ваших игроков стоит
 *     такой мод и он всё же цепляет наш {@code SkinArmoryScreen} - решение то же самое:
 *     добавить {@code org.minechestplate.mcpskins.skin.client.gui.SkinArmoryScreen} в список
 *     исключений ЭТОГО стороннего мода. Со своей стороны мы блюр нигде не вызываем и не
 *     полагаемся на него.</li>
 * </ol>
 *
 * <p><b>Выбор {@code ItemDisplayContext} и направления оси Y - см. §4.1 концепта:</b> "какой
 * конкретно ItemDisplayContext даёт чистую модель без специфичных для рук/GUI урезаний -
 * вопрос одного вечера тестирования перебором значений enum на вашем форке, а не архитектурный
 * риск". Вместо того чтобы гадать один раз и жёстко зашивать результат, этот виджет даёт
 * ИНСТРУМЕНТ для такого тестирования прямо в игре: клавиша {@code C} по кругу перебирает
 * {@link #CONTEXT_CANDIDATES}, а {@code V} переключает знак масштаба по Y (нужен, потому что
 * GUI-координаты растут вниз, а большинство "мировых" (не-GUI) трансформов TACZ, вероятно,
 * расчитаны на обычную (Y вверх) систему координат мира - см. подробное рассуждение в javadoc
 * {@code SkinArmoryScreen}, раздел про подиум). Текущее сочетание печатается мелким текстом в
 * углу подиума при наведении курсора - как только вы подберёте контекст/флип, при которых
 * модель оружия выглядит чисто и правильной стороной вверх, дальнейшая правка кода не нужна:
 * достаточно один раз запомнить, на каком индексе контекста и в каком состоянии флипа модель
 * выглядит правильно, и (если хотите зафиксировать это как дефолт) поменять
 * {@link #DEFAULT_CONTEXT_INDEX}/{@link #DEFAULT_Y_FLIP} ниже.
 *
 * <p><b>ВАЖНО ПРИ КОМПИЛЯЦИИ:</b> сигнатура {@code ItemRenderer.renderStatic(ItemStack,
 * ItemDisplayContext, int, int, PoseStack, MultiBufferSource, Level, int)} соответствует
 * стандартному, задокументированному API Minecraft/NeoForge для веток до переработки GUI-рендера
 * в 1.21.2 (в 1.21.1, на которой основан ваш форк TACZ, эта переработка ещё не применялась - см.
 * официальные changelog'и миграции NeoForge между 1.21.1 и 1.21.2/1.21.4). Тем не менее, у меня
 * не было доступа к сборке вашего конкретного дерева зависимостей, чтобы прогнать компилятор -
 * если у вас другая минорная версия маппингов и имя метода/параметры отличаются, компилятор
 * укажет ровно на эту строку в {@link #renderItem3D}.
 */
public final class Item3DPodiumWidget {

    // ---- Кандидаты ItemDisplayContext для клавиши C (см. javadoc класса) ------------------
    private static final ItemDisplayContext[] CONTEXT_CANDIDATES = {
            ItemDisplayContext.FIXED,
            ItemDisplayContext.GROUND,
            ItemDisplayContext.NONE,
            ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
            ItemDisplayContext.HEAD,
            ItemDisplayContext.GUI
    };

    /** Индекс в {@link #CONTEXT_CANDIDATES}, с которого стартуем - поправьте, когда подберёте. */
    private static final int DEFAULT_CONTEXT_INDEX = 0; // FIXED
    /** Стартовое состояние флипа оси Y - поправьте, когда подберёте (см. javadoc класса). */
    private static final boolean DEFAULT_Y_FLIP = true;

    // Полный "яркий" packed light (blockLight=15, skyLight=15) - то же значение, которое
    // использует ванильный GuiGraphics#renderItem для GUI-иконок (см. его исходник), чтобы
    // модель не была затемнена как будто она стоит в тёмной пещере.
    private static final int FULL_BRIGHT_PACKED_LIGHT = 0xF000F0;

    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 2.5f;
    private static final float MAX_PITCH = 80f;
    private static final float AUTO_ROTATE_DEG_PER_SEC = 12f;
    // Градусов поворота на 1 пиксель мыши - CS2-style: заметно, но не дёргано.
    private static final float DRAG_SENSITIVITY = 0.5f;
    private static final float ZOOM_STEP = 0.12f;

    private ItemStack stack = ItemStack.EMPTY;
    private int x, y, width, height;

    private float yaw = 25f;
    private float pitch = -12f;
    private float zoom = 1f;
    private boolean dragging = false;
    // Как только игрок хоть раз потрогал подиум мышью, авто-вращение ("живая витрина", пока
    // никто не взаимодействует - см. §4.2 концепта про "не мёртвый кадр") выключается насовсем
    // для этого предмета, чтобы не мешать разглядывать конкретный ракурс.
    private boolean userHasInteracted = false;
    private long lastFrameNanos = -1L;

    private int contextIndex = DEFAULT_CONTEXT_INDEX;
    private boolean yFlip = DEFAULT_Y_FLIP;

    // Отказоустойчивость (см. §4.3 концепта, "не получилось - тихий даунгрейд, а не краш"):
    // если 3D-рендер для ТЕКУЩЕГО предмета один раз бросил исключение, для него мы больше не
    // повторяем попытку (не спамим try/catch каждый кадр) - используем плоскую иконку до тех
    // пор, пока не сменится сам предмет ИЛИ пользователь не попробует другой контекст клавишей C.
    private boolean renderFailed = false;
    private boolean warnedOnce = false;

    public void setStack(ItemStack newStack) {
        this.stack = newStack == null ? ItemStack.EMPTY : newStack;
        this.renderFailed = false;
    }

    public ItemStack getStack() {
        return stack;
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public boolean isInBounds(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public void onMouseClicked() {
        dragging = true;
    }

    public void onMouseReleased() {
        dragging = false;
    }

    public void onMouseDragged(double dragDeltaX, double dragDeltaY) {
        if (!dragging) return;
        userHasInteracted = true;
        yaw += (float) dragDeltaX * DRAG_SENSITIVITY;
        // CS2-style: питч намеренно зажат (см. §4.1 концепта, "не даём перевернуть модель
        // вверх ногами") - drag вверх/вниз наклоняет камеру, а не крутит оружие сальто.
        pitch = Mth.clamp(pitch - (float) dragDeltaY * DRAG_SENSITIVITY, -MAX_PITCH, MAX_PITCH);
    }

    public void onMouseScrolled(double scrollDeltaY) {
        zoom = Mth.clamp(zoom + (float) scrollDeltaY * ZOOM_STEP, MIN_ZOOM, MAX_ZOOM);
    }

    /** См. клавишу C в javadoc класса. */
    public void cycleContext() {
        contextIndex = (contextIndex + 1) % CONTEXT_CANDIDATES.length;
        renderFailed = false; // новый контекст заслуживает собственную попытку
    }

    /** См. клавишу V в javadoc класса. */
    public void toggleYFlip() {
        yFlip = !yFlip;
    }

    public void resetView() {
        yaw = 25f;
        pitch = -12f;
        zoom = 1f;
        userHasInteracted = false;
    }

    /**
     * @param accentColor цвет акцентной полосы подложки (обычно {@code labelColor} текущего
     *                    скина) - чисто декоративная деталь, продолжающая стиль карусели рефита
     */
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, int accentColor) {
        if (width <= 0 || height <= 0) return;

        // Только заливка-подложка рисуется ДО сцены - она и должна быть "под" моделью.
        // Рамка/акцентная полоса и debug-лейбл теперь рисуются В КОНЦЕ метода, ПОСЛЕ 3D-сцены
        // (см. renderFrame/renderDebugLabel ниже за подробным объяснением) - раньше рамка
        // рисовалась здесь же, ДО модели, из-за чего длинное оружие, чья геометрия достаёт
        // ровно до края бокса (что для моделей оружия - норма, а не редкость), просто
        // закрашивало собой уже нарисованную линию рамки поверх неё. scissor ниже и так не
        // даёт модели физически выйти ЗА пределы бокса - дело было именно в порядке отрисовки
        // относительно рамки, а не в границах сцены.
        renderBackdropFill(guiGraphics);

        if (stack.isEmpty()) {
            lastFrameNanos = -1L;
            renderFrame(guiGraphics, accentColor);
            return;
        }

        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos < 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        // Защита от одного гигантского "кадра" после лага/паузы/долгой загрузки - иначе
        // авто-вращение резко провернуло бы модель на десятки градусов за один тик.
        deltaSeconds = Mth.clamp(deltaSeconds, 0f, 0.25f);

        if (!dragging && !userHasInteracted) {
            yaw += AUTO_ROTATE_DEG_PER_SEC * deltaSeconds;
        }

        int centerX = x + width / 2;
        int centerY = y + height / 2;
        float baseScale = Math.min(width, height) * 0.55f;

        // Вырезаем 3D-сцену строго внутри её панели (см. §4.1, шаг 1 концепта) - иначе при
        // экстремальном зуме модель могла бы наехать на соседние панели каталога.
        guiGraphics.enableScissor(x, y, x + width, y + height);
        try {
            if (!renderFailed) {
                try {
                    renderItem3D(guiGraphics, centerX, centerY, baseScale);
                } catch (Throwable t) {
                    renderFailed = true;
                    if (!warnedOnce) {
                        warnedOnce = true;
                        MCPSkins.LOGGER.warn(
                                "[MCPSkins] 3D-подиум Armory не смог отрисовать предмет в контексте '{}' " +
                                        "(y-flip={}) - откатываемся на плоскую иконку. Попробуйте другой " +
                                        "ItemDisplayContext клавишей C (или флип осью Y клавишей V), пока " +
                                        "наведён курсор на подиум.",
                                currentContext(), yFlip, t);
                    }
                }
            }
        } finally {
            guiGraphics.disableScissor();
        }

        if (renderFailed) {
            renderFlatFallback(guiGraphics, centerX, centerY);
        }

        // Рамка - ПОСЛЕ сцены, чтобы всегда лежать поверх модели (см. javadoc renderFrame).
        renderFrame(guiGraphics, accentColor);
        renderDebugLabel(guiGraphics, mouseX, mouseY);
    }

    private void renderItem3D(GuiGraphics guiGraphics, int centerX, int centerY, float baseScale) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        try {
            pose.translate(centerX, centerY, 150.0);
            float scale = baseScale * zoom;
            // yFlip: GUI-координаты растут вниз, а большинство НЕ-GUI ItemDisplayContext
            // ожидают обычную (Y вверх) систему координат мира - см. подробный javadoc класса.
            pose.scale(scale, yFlip ? -scale : scale, scale);
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            pose.mulPose(Axis.YP.rotationDegrees(yaw));

            // "3D-освещение как в мире/руке", а НЕ плоское GUI-освещение (см. §4.1, шаг 5
            // концепта - иначе даже повёрнутая модель выглядела бы плоской карточкой).
            Lighting.setupFor3DItems();
            // ПОЧЕМУ ПРИ yFlip МОДЕЛЬ СТАНОВИЛАСЬ ЧАСТИЧНО ПРОЗРАЧНОЙ (баг, о котором просили
            // либо починить, либо убрать функцию - чиним): scale(scale, -scale, scale) выше -
            // это зеркальное отражение сцены по оси Y. Зеркало (в отличие от поворота) меняет
            // "хиральность" пространства и переворачивает порядок обхода вершин (winding order)
            // КАЖДОЙ грани модели. GPU по умолчанию отбраковывает ("culling") грани, обход
            // которых после трансформации оказывается "не тем" - именно поэтому у зеркально
            // отражённой модели пропадала (казалась прозрачной) значительная часть граней: они
            // не рендерились вообще, а не были буквально полупрозрачными. Раньше в этом месте
            // культинг не трогался, поэтому баг воспроизводился стабильно при yFlip=true.
            // Фикс: на время рендера именно зеркальной (yFlip) сцены отключаем отбраковку
            // тыльных граней - тогда рендерятся все грани независимо от их обхода. Для
            // НЕ-флипнутой сцены культинг остаётся включённым как обычно (там он не мешает и
            // его выключение не нужно).
            boolean cullDisabledForFlip = false;
            if (yFlip) {
                RenderSystem.disableCull();
                cullDisabledForFlip = true;
            }
            try {
                mc.getItemRenderer().renderStatic(
                        stack,
                        currentContext(),
                        FULL_BRIGHT_PACKED_LIGHT,
                        OverlayTexture.NO_OVERLAY,
                        pose,
                        guiGraphics.bufferSource(),
                        mc.level,
                        0
                );
                // Тот же flush(), что и в самом ванильном GuiGraphics#renderItem - без него
                // отрисовка предмета может остаться в буфере и всплыть поверх следующего кадра
                // не в том порядке, что и остальной 2D-UI.
                guiGraphics.flush();
            } finally {
                if (cullDisabledForFlip) {
                    RenderSystem.enableCull();
                }
                Lighting.setupForFlatItems();
            }
        } finally {
            pose.popPose();
        }
    }

    private void renderFlatFallback(GuiGraphics guiGraphics, int centerX, int centerY) {
        guiGraphics.renderItem(stack, centerX - 8, centerY - 8);
    }

    /**
     * Только фон-подложка (градиент) - рисуется ПЕРВЫМ, до 3D-сцены, потому что это "холст", на
     * котором стоит модель. Рамку/акцентную полосу сюда намеренно не включаем - см. {@link #renderFrame}.
     */
    private void renderBackdropFill(GuiGraphics guiGraphics) {
        int x1 = x + width;
        int y1 = y + height;
        // Тёмная "студийная" подложка вместо блюра (см. javadoc класса) - вертикальный
        // градиент чуть светлее в центре сцены, в том же визуальном языке, что и карусель
        // TACZRefitSkinOverlay.
        guiGraphics.fillGradient(x, y, x1, y1, 0xE0141414, 0xF2060606);
    }

    /**
     * Рамка бокса просмотра и акцентная полоса редкости сверху неё.
     *
     * <p><b>ПОЧЕМУ ЭТО РИСУЕТСЯ ПОСЛЕДНИМ, ПОСЛЕ 3D-МОДЕЛИ (фикс бага "модель рендерится поверх
     * бордера"):</b> раньше рамка рисовалась ДО модели, в одном методе с фоном. Модели оружия -
     * особенно длинные (винтовки, снайперки) - у многих {@code ItemDisplayContext} имеют
     * bounding box заметно больше "единичного кубика" обычного предмета, поэтому их геометрия на
     * дефолтном зуме нередко достаёт РОВНО до края бокса (это нормальное поведение, а не редкий
     * край случая - scissor в {@link #render} и так гарантирует, что модель физически не может
     * выйти ЗА пределы бокса, но не мешает ей закрасить пиксели ровно НА рамке, которая уже была
     * нарисована раньше неё и теперь просто перекрыта более поздним рисованием сцены поверх).
     * Из-за этого линия рамки (и акцентная полоса) в этих местах пропадала под моделью - именно
     * это и выглядело как "модель поверх бордера". Рисуя рамку ПОСЛЕДНЕЙ (после сцены, после
     * фолбэка), мы гарантируем, что она физически ложится поверх уже отрисованных пикселей модели
     * и остаётся видна всегда, независимо от того, насколько модель большая/длинная.
     *
     * <p>Тест глубины отключаем и делаем явный {@code flush()} ДО его включения обратно по той
     * же причине, что и в {@link #renderDebugLabel} (см. его javadoc) - иначе рамка рисковала бы
     * тем же багом "проваливания" под депс-буфер, оставшийся от 3D-рендера предмета.
     */
    private void renderFrame(GuiGraphics guiGraphics, int accentColor) {
        int x1 = x + width;
        int y1 = y + height;
        RenderSystem.disableDepthTest();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 300.0F);
        guiGraphics.fill(x, y, x1, y + 2, (accentColor & 0xFFFFFF) | 0x90000000);
        guiGraphics.renderOutline(x, y, width, height, 0x40FFFFFF);
        // Явный flush() ПОКА тест глубины ещё выключен - см. javadoc метода и renderDebugLabel.
        guiGraphics.flush();
        guiGraphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    /**
     * Debug-лейбл "Ctx: ... Y-flip: ... [C / V]" в углу подиума.
     *
     * <p><b>ПОЧЕМУ ЗДЕСЬ ОБЯЗАТЕЛЕН ЯВНЫЙ {@code guiGraphics.flush()} (фикс бага "длинное оружие
     * перекрывает текст Ctx"):</b> {@code GuiGraphics#drawString} не отправляет вершины текста на
     * GPU немедленно - он только кладёт их во внутренний буфер {@code GuiGraphics}, а реальная
     * отправка происходит при следующем {@code flush()} (или когда её вызовет что-то другое
     * позже). Раньше сразу после {@code drawString(...)} шёл {@code RenderSystem.enableDepthTest()}
     * - то есть тест глубины успевал включиться обратно ДО того, как текст физически долетал до
     * GPU. К моменту реальной отправки тест глубины уже был снова включён, и текстовые квады
     * сравнивались с depth-буфером, в который до этого писала 3D-модель оружия
     * ({@link #renderItem3D}) - из-за чего у длинных моделей, чья геометрия попадала в тот же
     * угол бокса, что и подпись, текст "проваливался" под оружием вместо того, чтобы лежать
     * поверх него. Явный {@code flush()} здесь, пока тест глубины ЕЩЁ выключен, гарантирует, что
     * подпись реально уйдёт на GPU без теста глубины и будет видна поверх модели любой длины.
     */
    private void renderDebugLabel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (!isInBounds(mouseX, mouseY)) return;
        Minecraft mc = Minecraft.getInstance();
        String text = "Ctx: " + currentContext().name() + "  Y-flip: " + (yFlip ? "on" : "off") + "  [C / V]";

        RenderSystem.disableDepthTest();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 300.0F);
        guiGraphics.drawString(mc.font, text, x + 4, y + height - mc.font.lineHeight - 4, 0x80FFFFFF, false);
        guiGraphics.flush();
        guiGraphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    public ItemDisplayContext currentContext() {
        return CONTEXT_CANDIDATES[contextIndex];
    }

    public boolean isYFlip() {
        return yFlip;
    }
}