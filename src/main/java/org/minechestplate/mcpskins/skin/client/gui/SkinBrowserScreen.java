package org.minechestplate.mcpskins.skin.client.gui;

import net.minecraft.client.gui.GuiGraphics;
// import net.minecraft.client.gui.components.Button; // Убрать ванильную кнопку
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.ArrayList;
import java.util.List;

public class SkinBrowserScreen extends Screen {
    private final List<SkinDataModels.WeaponSkins> weapons;

    private final int slotSize = 64;
    private final int spacing = 16;
    private final int cols = 5;
    private double scrollOffset = 0;

    public SkinBrowserScreen() {
        super(Component.literal("Weapon Skin Browser"));
        this.weapons = new ArrayList<>(SkinManager.INSTANCE.getRegistry().values());
    }

    @Override
    protected void init() {
        // Убрали ванильную кнопку, будем рендерить пользовательский текст
        // addRenderableWidget(Button.builder(Component.literal("✕ CLOSE"), btn -> this.onClose())
        //         .bounds(width - 75, 12, 60, 18).build());
    }

    // --- ВАЖНОЕ ИЗМЕНЕНИЕ ---
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 1. Заливаем экран полностью непрозрачным черным цветом (фон)
        guiGraphics.fill(0, 0, width, height, 0xFF121212);

        // 2. Если хотите легкое затемнение поверх (если есть текстура мира),
        // добавьте этот слой, он будет непрозрачным по альфа-каналу
        guiGraphics.fill(0, 0, width, height, 0xCC000000);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 1. Рисуем мягкое премиальное затемнение заднего фона.
        // 0xB3000000 - это черный цвет с прозрачностью 70% (B3), что даст красивый эффект тонировки поверх мира.
        guiGraphics.fill(0, 0, width, height, 0xB3000000);

        int totalRows = (int) Math.ceil((double) weapons.size() / cols); //[cite: 6]
        int contentHeight = totalRows * (slotSize + spacing); //[cite: 6]
        int viewHeight = height - 60; //[cite: 6]
        int maxScroll = Math.max(0, contentHeight - viewHeight); //[cite: 6]

        // Обрезка контента (Scissor Test) под хедером[cite: 6]
        guiGraphics.enableScissor(0, 45, width, height); //[cite: 6]

        int totalWidth = (cols * slotSize) + ((cols - 1) * spacing);
        int startX = (width - totalWidth) / 2;
        int startY = 60;

        int col = 0, row = 0;

        for (SkinDataModels.WeaponSkins weapon : weapons) {
            int xPos = startX + (col * (slotSize + spacing));
            int yPos = (int) (startY + (row * (slotSize + spacing)) - scrollOffset);

            if (yPos + slotSize > 45 && yPos < height) {
                boolean isHovered = mouseX >= xPos && mouseX <= xPos + slotSize && mouseY >= yPos && mouseY <= yPos + slotSize;

                // --- НОВЫЙ ДИЗАЙН (OPAQUE) ---
                // Заливаем фон слота сплошным непрозрачным цветом.
                int bgColor = isHovered ? 0xFF282828 : 0xFF1A1A1A;
                // Рисуем рамку слота сплошным непрозрачным цветом.
                int borderColor = isHovered ? 0xFFFFFFFF : 0xFF363636;

                guiGraphics.fill(xPos, yPos, xPos + slotSize, yPos + slotSize, bgColor);
                guiGraphics.renderOutline(xPos, yPos, slotSize, slotSize, borderColor);

                ItemStack stack = TACZSkinHelper.createGunStack(weapon.baseGun());

                guiGraphics.pose().pushPose();

                // 1. Увеличиваем Z-перевод (со 50f до 150f или 200f),
                // чтобы объемная модель не проваливалась сквозь фон (clipping).
                guiGraphics.pose().translate(xPos + (slotSize / 2f), yPos + (slotSize / 2f), 150f);

                // 2. Масштабируем Z пропорционально X и Y, чтобы 3D-модель не сплющивалась!
                guiGraphics.pose().scale(2.5F, 2.5F, 2.5F);

                // 3. Отрисовка предмета. Математика -8 правильная, так как
                // базовый размер предмета 16x16, и мы оттягиваем его назад на половину после центрирования.
                guiGraphics.renderItem(stack, -8, -8);

                guiGraphics.pose().popPose();
            }

            col++;
            if (col >= cols) { col = 0; row++; }
        }

        guiGraphics.disableScissor();

        // --- НОВЫЙ Скроллбар (OPAQUE) ---
        if (maxScroll > 0) {
            int scrollbarX = startX + totalWidth + 15;
            int scrollbarY = startY;
            int scrollbarHeight = height - startY - 20;

            // Сплошной трек скроллбара
            guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + 2, scrollbarY + scrollbarHeight, 0xFF363636);

            int thumbHeight = Math.max(15, (int) (scrollbarHeight * ((float) viewHeight / contentHeight)));
            int thumbY = scrollbarY + (int) ((scrollbarHeight - thumbHeight) * (scrollOffset / maxScroll));
            // Сплошной ползунок скроллбара
            guiGraphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbHeight, 0xFF999999);
        }

        // --- НОВЫЙ Хедер (OPAQUE) ---
        // Сплошной темный бар без градиента.
        guiGraphics.fill(0, 0, width, 45, 0xFF1A1A1A);
        guiGraphics.drawCenteredString(font, Component.literal("SELECT A WEAPON").withStyle(style -> style.withBold(true)), width / 2, 16, 0xFFFFFFFF);

        // --- НОВАЯ Кнопка CLOSE (OPAQUE) ---
        // Рендерим текст кнопки CLOSE с пользовательским изменением цвета при наведении.
        String closeText = "✕ CLOSE";
        int closeTextWidth = font.width(closeText);
        int closeX = width - closeTextWidth - 16;
        int closeY = 16;
        int closeButtonHeight = font.lineHeight;

        boolean closeButtonHovered = mouseX >= closeX && mouseX <= closeX + closeTextWidth && mouseY >= closeY && mouseY <= closeY + closeButtonHeight;
        int closeTextColor = closeButtonHovered ? 0xFFCCCCCC : 0xFFFFFFFF;

        guiGraphics.drawString(font, closeText, closeX, closeY, closeTextColor);

        // Убрали super.render(), так как мы не используем ванильные виджеты.
        // super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int totalRows = (int) Math.ceil((double) weapons.size() / cols);
        int contentHeight = totalRows * (slotSize + spacing);
        int viewHeight = height - 60;
        int maxScroll = Math.max(0, contentHeight - viewHeight);

        scrollOffset -= scrollY * 24;
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        // Проверяем клик по кнопке CLOSE.
        String closeText = "✕ CLOSE";
        int closeTextWidth = font.width(closeText);
        int closeX = width - closeTextWidth - 16;
        int closeY = 16;
        int closeButtonHeight = font.lineHeight;

        if (mouseX >= closeX && mouseX <= closeX + closeTextWidth && mouseY >= closeY && mouseY <= closeY + closeButtonHeight) {
            this.onClose();
            return true;
        }

        // Проверяем клики по сетке слотов.
        if (mouseY <= 45) return super.mouseClicked(mouseX, mouseY, button);

        int totalWidth = (cols * slotSize) + ((cols - 1) * spacing);
        int startX = (width - totalWidth) / 2;
        int startY = 60;
        int col = 0, row = 0;

        for (SkinDataModels.WeaponSkins weapon : weapons) {
            int xPos = startX + (col * (slotSize + spacing));
            int yPos = (int) (startY + (row * (slotSize + spacing)) - scrollOffset);

            if (mouseX >= xPos && mouseX <= xPos + slotSize && mouseY >= yPos && mouseY <= yPos + slotSize) {
                this.minecraft.setScreen(new SkinViewerScreen(weapon.baseGun()));
                return true;
            }

            col++;
            if (col >= cols) { col = 0; row++; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}