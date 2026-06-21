package org.minechestplate.mcpskins.skin.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
        addRenderableWidget(Button.builder(Component.literal("✕ CLOSE"), btn -> this.onClose())
                .bounds(width - 75, 12, 60, 18).build());
    }

    // --- ВАЖНОЕ ИЗМЕНЕНИЕ ---
    // Переопределяем метод отрисовки фона, чтобы убить ванильный блюр
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Вместо ванильного размытия рисуем наше чистое индустриальное затемнение
        guiGraphics.fill(0, 0, width, height, 0x990A0A0A);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Фон теперь рисуется движком автоматически через переопределенный renderBackground,
        // поэтому сразу переходим к математике и рендеру контента.

        int totalRows = (int) Math.ceil((double) weapons.size() / cols);
        int contentHeight = totalRows * (slotSize + spacing);
        int viewHeight = height - 60;
        int maxScroll = Math.max(0, contentHeight - viewHeight);

        // Обрезка контента (Scissor Test) под хедером
        guiGraphics.enableScissor(0, 45, width, height);

        int totalWidth = (cols * slotSize) + ((cols - 1) * spacing);
        int startX = (width - totalWidth) / 2;
        int startY = 60;

        int col = 0, row = 0;

        for (SkinDataModels.WeaponSkins weapon : weapons) {
            int xPos = startX + (col * (slotSize + spacing));
            int yPos = (int) (startY + (row * (slotSize + spacing)) - scrollOffset);

            if (yPos + slotSize > 45 && yPos < height) {
                boolean isHovered = mouseX >= xPos && mouseX <= xPos + slotSize && mouseY >= yPos && mouseY <= yPos + slotSize;

                int bgColor = isHovered ? 0x22FFFFFF : 0x22000000;
                int borderColor = isHovered ? 0xFFFFFFFF : 0x44FFFFFF;

                guiGraphics.fill(xPos, yPos, xPos + slotSize, yPos + slotSize, bgColor);
                guiGraphics.renderOutline(xPos, yPos, slotSize, slotSize, borderColor);

                ItemStack stack = TACZSkinHelper.createGunStack(weapon.baseGun());

                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(xPos + (slotSize / 2f), yPos + (slotSize / 2f), 50f);
                guiGraphics.pose().scale(2.5F, 2.5F, 1.0F);
                guiGraphics.renderItem(stack, -8, -8);
                guiGraphics.pose().popPose();
            }

            col++;
            if (col >= cols) { col = 0; row++; }
        }

        guiGraphics.disableScissor();

        // Скроллбар
        if (maxScroll > 0) {
            int scrollbarX = startX + totalWidth + 15;
            int scrollbarY = startY;
            int scrollbarHeight = height - startY - 20;

            guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + 2, scrollbarY + scrollbarHeight, 0x22FFFFFF);

            int thumbHeight = Math.max(15, (int) (scrollbarHeight * ((float) viewHeight / contentHeight)));
            int thumbY = scrollbarY + (int) ((scrollbarHeight - thumbHeight) * (scrollOffset / maxScroll));
            guiGraphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbHeight, 0x88FFFFFF);
        }

        // Верхний хедер с градиентом
        guiGraphics.fillGradient(0, 0, width, 45, 0xC5101010, 0x00101010);
        guiGraphics.drawCenteredString(font, Component.literal("SELECT A WEAPON").withStyle(style -> style.withBold(true)), width / 2, 16, 0xFFFFFFFF);

        // Рендер кнопки CLOSE
        super.render(guiGraphics, mouseX, mouseY, partialTick);
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