package org.minechestplate.mcpskins.client.gui.settings;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.RefitToggleButtonRenderer;
import org.minechestplate.mcpskins.client.gui.TooltipPlacement;
import org.minechestplate.mcpskins.config.MCPSkinsClientConfig;
import org.minechestplate.mcpskins.config.ScreenAnchor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Lets the player drag the refit toggle button anywhere on screen before saving.
 * <p>
 * Background is whatever the player drops in as {@code assets/mcpskins/textures/gui/setting.png}
 * (a screenshot of TACZ's refit screen works well), stretched to fill the window. Falls back
 * to a plain dark fill if it's missing.
 * <p>
 * The attachment slot row is drawn separately on top, from TACZ's real textures at its
 * actual position (see {@link TACZAttachmentRowPreview}) - that's what the button should
 * line up against, not the background image.
 */
public class RefitButtonPositionScreen extends Screen {

    private static final ResourceLocation BACKGROUND =
            ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "textures/gui/setting.png");

    private static final int CONTROL_BAR_HEIGHT = 26;
    private static final int DEFAULT_SIZE = 18;
    private static final int DEFAULT_OFFSET_X = 120;
    private static final int DEFAULT_OFFSET_Y = 10;

    // Mirrors TACZRefitSkinOverlay.LABEL_Y_NUDGE
    private static final int LABEL_Y_NUDGE = -2;

    // Native pixel size of setting.png, resolved once. -1 = not resolved yet, 0 = missing.
    private static int backgroundWidth = -1;
    private static int backgroundHeight = -1;

    private final Screen parent;

    private int workingX0;
    private int workingY0;
    private int workingSize;
    private boolean dragging;
    private int dragGrabX;
    private int dragGrabY;

    public RefitButtonPositionScreen(Screen parent) {
        super(Component.translatable("gui.mcpskins.config.position_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        workingSize = MCPSkinsClientConfig.refitButtonSize();
        ScreenAnchor anchor = MCPSkinsClientConfig.refitButtonAnchor();
        workingX0 = anchor.resolveX(this.width, workingSize, MCPSkinsClientConfig.refitButtonOffsetX());
        workingY0 = anchor.resolveY(this.height, workingSize, MCPSkinsClientConfig.refitButtonOffsetY());
        clampToBounds();

        int barY = this.height - CONTROL_BAR_HEIGHT + 3;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.mcpskins.config.position_save"), b -> save())
                .bounds(this.width / 2 - 154, barY, 100, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.mcpskins.config.position_reset"), b -> resetToDefault())
                .bounds(this.width / 2 - 50, barY, 100, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.mcpskins.config.position_cancel"), b -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 + 54, barY, 100, 20)
                .build());
    }

    private void clampToBounds() {
        workingX0 = Mth.clamp(workingX0, 0, Math.max(0, this.width - workingSize));
        workingY0 = Mth.clamp(workingY0, 0, Math.max(0, this.height - workingSize - CONTROL_BAR_HEIGHT));
    }

    private void save() {
        ScreenAnchor anchor = ScreenAnchor.nearest(workingX0, workingY0, workingSize, workingSize, this.width, this.height);
        MCPSkinsClientConfig.trySet(MCPSkinsClientConfig.REFIT_BUTTON_ANCHOR, anchor);
        MCPSkinsClientConfig.trySet(MCPSkinsClientConfig.REFIT_BUTTON_OFFSET_X, anchor.offsetXFor(this.width, workingSize, workingX0));
        MCPSkinsClientConfig.trySet(MCPSkinsClientConfig.REFIT_BUTTON_OFFSET_Y, anchor.offsetYFor(this.height, workingSize, workingY0));
        this.minecraft.setScreen(parent);
    }

    private void resetToDefault() {
        workingSize = DEFAULT_SIZE;
        workingX0 = ScreenAnchor.TOP_RIGHT.resolveX(this.width, workingSize, DEFAULT_OFFSET_X);
        workingY0 = ScreenAnchor.TOP_RIGHT.resolveY(this.height, workingSize, DEFAULT_OFFSET_Y);
        clampToBounds();
    }

    // -----------------------------------------------------------------------------------
    // Dragging
    // -----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= workingX0 && mouseX <= workingX0 + workingSize
                && mouseY >= workingY0 && mouseY <= workingY0 + workingSize) {
            dragging = true;
            dragGrabX = (int) mouseX - workingX0;
            dragGrabY = (int) mouseY - workingY0;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            workingX0 = (int) mouseX - dragGrabX;
            workingY0 = (int) mouseY - dragGrabY;
            clampToBounds();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // -----------------------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        drawBackground(guiGraphics);
        TACZAttachmentRowPreview.render(guiGraphics, this.width);

        boolean hovered = !dragging && mouseX >= workingX0 && mouseX <= workingX0 + workingSize
                && mouseY >= workingY0 && mouseY <= workingY0 + workingSize;
        // active=false: this screen has no "skin mode on" concept, so the lit state
        // depends only on hover/drag
        RefitToggleButtonRenderer.render(guiGraphics, workingX0, workingY0, workingSize, hovered || dragging, false);

        if ((hovered || dragging) && MCPSkinsClientConfig.refitButtonTooltip()) {
            Component label = Component.translatable("gui.mcpskins.weapon_skins_tooltip");
            int labelWidth = this.font.width(label);
            TooltipPlacement.Result pos = TooltipPlacement.compute(workingX0, workingX0 + workingSize,
                    workingY0, workingY0 + workingSize, labelWidth, this.font.lineHeight,
                    this.width, this.height - CONTROL_BAR_HEIGHT, 4);
            guiGraphics.drawString(this.font, label, pos.x(), pos.y() + LABEL_Y_NUDGE, 0xFFFFFFFF);
        }

        drawCoordinateReadout(guiGraphics);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * Live readout of the button's current position and the anchor/offset pair that
     * {@link #save()} would write right now.
     */
    private void drawCoordinateReadout(GuiGraphics guiGraphics) {
        ScreenAnchor liveAnchor = ScreenAnchor.nearest(workingX0, workingY0, workingSize, workingSize, this.width, this.height);
        int liveOffsetX = liveAnchor.offsetXFor(this.width, workingSize, workingX0);
        int liveOffsetY = liveAnchor.offsetYFor(this.height, workingSize, workingY0);

        Component coords = Component.translatable("gui.mcpskins.config.position_coords",
                workingX0, workingY0, liveAnchor.name(), liveOffsetX, liveOffsetY);
        int y = this.height - CONTROL_BAR_HEIGHT - this.font.lineHeight - 6;
        guiGraphics.drawCenteredString(this.font, coords, this.width / 2, y, 0xFFFFFFFF);
    }

    /** Draws setting.png stretched to fill the window, or a dark fill if it's missing. */
    private void drawBackground(GuiGraphics guiGraphics) {
        resolveBackgroundSizeIfNeeded();
        if (backgroundWidth > 0 && backgroundHeight > 0) {
            guiGraphics.blit(BACKGROUND, 0, 0, this.width, this.height,
                    0f, 0f, backgroundWidth, backgroundHeight, backgroundWidth, backgroundHeight);
        } else {
            guiGraphics.fill(0, 0, this.width, this.height, 0xFF101418);
        }
    }

    /** Called on resource reload so a changed setting.png is picked up without a restart. */
    public static void clearBackgroundCache() {
        backgroundWidth = -1;
        backgroundHeight = -1;
    }

    private static void resolveBackgroundSizeIfNeeded() {
        if (backgroundWidth >= 0) return;
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(BACKGROUND);
        if (resource.isEmpty()) {
            backgroundWidth = 0;
            backgroundHeight = 0;
            return;
        }
        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            backgroundWidth = image.getWidth();
            backgroundHeight = image.getHeight();
        } catch (IOException e) {
            backgroundWidth = 0;
            backgroundHeight = 0;
        }
    }

    /** No-op - avoids the vanilla blurred background under our own opaque fill. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }
}