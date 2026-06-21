package org.minechestplate.mcpskins.skin.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;
import org.minechestplate.mcpskins.skin.client.RenderHelper;
import org.minechestplate.mcpskins.skin.network.ApplySkinPayload;

import java.util.ArrayList;
import java.util.List;

public class SkinViewerScreen extends Screen {
    private final List<SkinDataModels.WeaponSkins> allWeapons;
    private int currentWeaponIndex = 0;
    private int currentSkinIndex = 0;

    private float rotationX = 15f;
    private float rotationY = 0f;
    private float autoRotY = 0f;

    private float modelScale = 140f;
    private final float MIN_SCALE = 40f;
    private final float MAX_SCALE = 400f;

    private boolean isDragging = false;
    private double lastMouseX, lastMouseY;

    public SkinViewerScreen(String initialWeaponId) {
        super(Component.literal("Skin Viewer"));
        this.allWeapons = new ArrayList<>(SkinManager.INSTANCE.getRegistry().values());

        for (int i = 0; i < allWeapons.size(); i++) {
            if (allWeapons.get(i).baseGun().equals(initialWeaponId)) {
                this.currentWeaponIndex = i;
                break;
            }
        }
    }

    @Override
    protected void init() {
        int bottomY = height - 35;
        int center = width / 2;

        addRenderableWidget(Button.builder(Component.literal("EQUIP SKIN"), btn -> applySkin())
                .bounds(center - 60, height - 75, 120, 20).build());

        addRenderableWidget(Button.builder(Component.literal("< SKIN"), btn -> cycleSkin(-1))
                .bounds(center - 130, height - 75, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("SKIN >"), btn -> cycleSkin(1))
                .bounds(center + 70, height - 75, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal("PREV WEAPON"), btn -> cycleWeapon(-1))
                .bounds(20, bottomY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("NEXT WEAPON"), btn -> cycleWeapon(1))
                .bounds(130, bottomY, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("BACK TO BROWSER"), btn -> this.minecraft.setScreen(new SkinBrowserScreen()))
                .bounds(width - 130, bottomY, 110, 20).build());
    }

    private void cycleSkin(int dir) {
        if (allWeapons.isEmpty()) return;
        SkinDataModels.WeaponSkins currentWeapon = allWeapons.get(currentWeaponIndex);
        if (currentWeapon.skins().isEmpty()) return;
        currentSkinIndex = (currentSkinIndex + dir) % currentWeapon.skins().size();
        if (currentSkinIndex < 0) currentSkinIndex = currentWeapon.skins().size() - 1;
    }

    private void cycleWeapon(int dir) {
        if (allWeapons.isEmpty()) return;
        currentWeaponIndex = (currentWeaponIndex + dir) % allWeapons.size();
        if (currentWeaponIndex < 0) currentWeaponIndex = allWeapons.size() - 1;
        currentSkinIndex = 0;
    }

    private void applySkin() {
        if (allWeapons.isEmpty()) return;
        SkinDataModels.WeaponSkins currentWeapon = allWeapons.get(currentWeaponIndex);
        if (currentWeapon.skins().isEmpty()) return;

        SkinDataModels.SkinEntry skin = currentWeapon.skins().get(currentSkinIndex);
        if (SkinAttachment.hasSkin(Minecraft.getInstance().player, skin.id())) {
            PacketDistributor.sendToServer(new ApplySkinPayload(skin.id()));
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Рендерим стандартный фон без деструктивных эффектов размытия
        this.renderTransparentBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (allWeapons.isEmpty()) return;
        SkinDataModels.WeaponSkins currentWeapon = allWeapons.get(currentWeaponIndex);
        if (currentWeapon.skins().isEmpty()) return;

        SkinDataModels.SkinEntry skin = currentWeapon.skins().get(currentSkinIndex);
        boolean isUnlocked = SkinAttachment.hasSkin(Minecraft.getInstance().player, skin.id());

        // --- УЛУЧШЕННЫЙ ХЕДЕР И ФУТЕР ---
        // Мягкий рассеивающийся градиент сверху вместо плотной плашки
        guiGraphics.fillGradient(0, 0, width, 45, 0xC5101010, 0x00101010);
        // Нижнее затемнение сделано значительно прозрачнее для чистоты экрана
        guiGraphics.fillGradient(0, height - 100, width, height, 0x00000000, 0x9A000000);

        Component skinName = Component.literal(skin.name().toUpperCase()).withStyle(style -> style.withBold(true));
        guiGraphics.drawCenteredString(font, skinName, width / 2, 15, skin.labelColor());

        // Тонкая аккуратная линия-разделитель (уменьшена непрозрачность)
        guiGraphics.fill(width / 2 - 60, 28, width / 2 + 60, 29, 0x33FFFFFF);

        Component statusText = Component.literal(isUnlocked ? "■ UNLOCKED" : "■ LOCKED").withStyle(style -> style.withBold(true));
        int statusColor = isUnlocked ? 0x00FFAA : 0xFF4444;
        guiGraphics.drawCenteredString(font, statusText, width / 2, height - 100, statusColor);

        // Индикатор зума в углу экрана
        String zoomText = "ZOOM: " + (int)((modelScale / 140f) * 100) + "%";
        guiGraphics.drawString(font, zoomText, 15, 15, 0x66FFFFFF);

        if (!isDragging) autoRotY += 1.5f * partialTick;
        float finalRotY = rotationY + autoRotY;

        ItemStack stack = TACZSkinHelper.createGunStack(skin.id());
        RenderHelper.render3DItem(guiGraphics, stack, width / 2, height / 2, (int)modelScale, rotationX, finalRotY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        modelScale += (float) scrollY * 20f;
        modelScale = Mth.clamp(modelScale, MIN_SCALE, MAX_SCALE);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY > 45 && mouseY < height - 100) {
            isDragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) isDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDragging) {
            rotationY += (mouseX - lastMouseX) * 2.0f;
            rotationX -= (mouseY - lastMouseY) * 2.0f;
            rotationX = Mth.clamp(rotationX, -45f, 45f);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
}