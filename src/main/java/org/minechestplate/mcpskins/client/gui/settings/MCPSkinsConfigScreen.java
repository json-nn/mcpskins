package org.minechestplate.mcpskins.client.gui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.minechestplate.mcpskins.config.MCPSkinsClientConfig;
import org.minechestplate.mcpskins.config.MCPSkinsServerConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side settings screen. Server-authoritative values are shown read-only at the
 * bottom for reference.
 * <p>
 * Rows live in a scrollable region between the title and a reserved bottom strip
 * (server info + "Done") - see {@link #scrollRows}. Rows can never scroll into that strip.
 */
public class MCPSkinsConfigScreen extends Screen {

    private static final int ROW_WIDTH = 300;
    private static final int ROW_HEIGHT = 24;
    private static final int STEP_BUTTON_WIDTH = 20;
    private static final int CONTENT_TOP = 32;
    private static final int RESERVED_BOTTOM = 50;
    private static final int SCROLLBAR_WIDTH = 4;
    private static final double SCROLL_SPEED = 16.0;

    private final Screen parent;
    private final List<ScrollRow> scrollRows = new ArrayList<>();

    private int viewportTop;
    private int viewportBottom;
    private int contentHeight;
    private int maxScroll;
    private int scrollOffset;

    public MCPSkinsConfigScreen(Screen parent) {
        super(Component.translatable("gui.mcpskins.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        scrollRows.clear();
        scrollOffset = 0;

        int centerX = this.width / 2;
        int y = CONTENT_TOP;

        y = addToggleRow(y, centerX, "gui.mcpskins.config.refit_button_enabled", MCPSkinsClientConfig.REFIT_BUTTON_ENABLED, true);
        y = addStepperRow(y, centerX, "gui.mcpskins.config.refit_button_size", MCPSkinsClientConfig.REFIT_BUTTON_SIZE, 18, 1, 12, 32);
        y = addToggleRow(y, centerX, "gui.mcpskins.config.refit_button_tooltip", MCPSkinsClientConfig.REFIT_BUTTON_TOOLTIP, true);

        Button positionButton = Button.builder(Component.translatable("gui.mcpskins.config.position_button"),
                        b -> this.minecraft.setScreen(new RefitButtonPositionScreen(this)))
                .bounds(centerX - ROW_WIDTH / 2, y, ROW_WIDTH, 20)
                .build();
        addScrollWidget(positionButton, y);
        y += ROW_HEIGHT + 6;

        y = addToggleRow(y, centerX, "gui.mcpskins.config.toast_enabled", MCPSkinsClientConfig.TOAST_ENABLED, true);
        y = addStepperRow(y, centerX, "gui.mcpskins.config.toast_duration", MCPSkinsClientConfig.TOAST_DURATION_MS, 2200, 200, 500, 10000);
        y = addStepperRow(y, centerX, "gui.mcpskins.config.carousel_height", MCPSkinsClientConfig.CAROUSEL_HEIGHT, 96, 4, 60, 160);
        y = addStepperRow(y, centerX, "gui.mcpskins.config.carousel_slot_size", MCPSkinsClientConfig.CAROUSEL_SLOT_SIZE, 44, 4, 24, 80);
        y = addStepperRow(y, centerX, "gui.mcpskins.config.carousel_slot_spacing", MCPSkinsClientConfig.CAROUSEL_SLOT_SPACING, 60, 4, 30, 120);

        contentHeight = y - CONTENT_TOP;
        viewportTop = CONTENT_TOP;
        viewportBottom = Math.max(viewportTop + ROW_HEIGHT, this.height - RESERVED_BOTTOM);
        maxScroll = Math.max(0, contentHeight - (viewportBottom - viewportTop));

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        b -> this.minecraft.setScreen(parent))
                .bounds(centerX - 100, this.height - 28, 200, 20)
                .build());

        updateRowPositions();
    }

    // -----------------------------------------------------------------------------------
    // Row construction
    // -----------------------------------------------------------------------------------

    private int addToggleRow(int y, int centerX, String labelKey, ModConfigSpec.BooleanValue value, boolean fallback) {
        boolean current = MCPSkinsClientConfig.SPEC.isLoaded() ? value.get() : fallback;
        Button button = Button.builder(toggleLabel(labelKey, current), b -> {
                    boolean next = !(MCPSkinsClientConfig.SPEC.isLoaded() ? value.get() : fallback);
                    MCPSkinsClientConfig.trySet(value, next);
                    b.setMessage(toggleLabel(labelKey, next));
                })
                .bounds(centerX - ROW_WIDTH / 2, y, ROW_WIDTH, 20)
                .build();
        addScrollWidget(button, y);
        return y + ROW_HEIGHT;
    }

    private Component toggleLabel(String labelKey, boolean value) {
        return Component.translatable(labelKey,
                Component.translatable(value ? "gui.mcpskins.config.on" : "gui.mcpskins.config.off"));
    }

    private int addStepperRow(int y, int centerX, String labelKey, ModConfigSpec.IntValue value,
                              int fallback, int step, int min, int max) {
        int rowLeft = centerX - ROW_WIDTH / 2;
        int current = MCPSkinsClientConfig.SPEC.isLoaded() ? value.get() : fallback;

        Button valueButton = Button.builder(Component.translatable(labelKey, current), b -> {})
                .bounds(rowLeft + STEP_BUTTON_WIDTH + 2, y, ROW_WIDTH - STEP_BUTTON_WIDTH * 2 - 4, 20)
                .build();
        valueButton.active = false;

        Button minus = Button.builder(Component.literal("-"), b -> {
                    int now = MCPSkinsClientConfig.SPEC.isLoaded() ? value.get() : fallback;
                    int next = Mth.clamp(now - step, min, max);
                    MCPSkinsClientConfig.trySet(value, next);
                    valueButton.setMessage(Component.translatable(labelKey, next));
                })
                .bounds(rowLeft, y, STEP_BUTTON_WIDTH, 20)
                .build();
        Button plus = Button.builder(Component.literal("+"), b -> {
                    int now = MCPSkinsClientConfig.SPEC.isLoaded() ? value.get() : fallback;
                    int next = Mth.clamp(now + step, min, max);
                    MCPSkinsClientConfig.trySet(value, next);
                    valueButton.setMessage(Component.translatable(labelKey, next));
                })
                .bounds(rowLeft + ROW_WIDTH - STEP_BUTTON_WIDTH, y, STEP_BUTTON_WIDTH, 20)
                .build();

        addScrollWidget(minus, y);
        addScrollWidget(valueButton, y);
        addScrollWidget(plus, y);
        return y + ROW_HEIGHT;
    }

    /**
     * Registers a widget as scrollable content: added to {@code children()} so it still
     * gets input, but not to {@code renderables} - it's rendered manually in {@link #render}
     * inside a scissor box, at a Y that tracks {@link #scrollOffset}.
     */
    private void addScrollWidget(AbstractWidget widget, int baseY) {
        this.addWidget(widget);
        scrollRows.add(new ScrollRow(widget, baseY));
    }

    /** Applies scroll offset to every row and hides/disables rows outside the viewport. */
    private void updateRowPositions() {
        for (ScrollRow row : scrollRows) {
            row.widget.setY(row.baseY - scrollOffset);
            boolean visible = row.widget.getY() >= viewportTop
                    && row.widget.getY() + row.widget.getHeight() <= viewportBottom;
            row.widget.visible = visible;
            row.widget.active = visible && row.enabledByDefault;
        }
    }

    // -----------------------------------------------------------------------------------
    // Scrolling
    // -----------------------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && mouseY >= viewportTop && mouseY <= viewportBottom) {
            scrollOffset = Mth.clamp(scrollOffset - (int) Math.round(scrollY * SCROLL_SPEED), 0, maxScroll);
            updateRowPositions();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // -----------------------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xF0101418);
        guiGraphics.drawCenteredString(this.font, this.getTitle(), this.width / 2, 12, 0xFFFFFF);

        guiGraphics.enableScissor(0, viewportTop, this.width, viewportBottom);
        for (ScrollRow row : scrollRows) {
            if (row.widget.visible) {
                row.widget.render(guiGraphics, mouseX, mouseY, partialTick);
            }
        }
        guiGraphics.disableScissor();

        if (maxScroll > 0) {
            renderScrollbar(guiGraphics);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        Component serverInfo = Component.translatable("gui.mcpskins.config.server_info",
                MCPSkinsServerConfig.fuseCost(),
                MCPSkinsServerConfig.allowLockedSkinPreview()
                        ? Component.translatable("gui.mcpskins.config.on")
                        : Component.translatable("gui.mcpskins.config.off"));
        guiGraphics.drawCenteredString(this.font, serverInfo, this.width / 2, this.height - 44, 0x80FFFFFF);
    }

    private void renderScrollbar(GuiGraphics guiGraphics) {
        int trackHeight = viewportBottom - viewportTop;
        int barX0 = this.width - SCROLLBAR_WIDTH - 2;
        int barX1 = this.width - 2;
        guiGraphics.fill(barX0, viewportTop, barX1, viewportBottom, 0x30FFFFFF);

        int thumbHeight = Math.max(12, trackHeight * trackHeight / contentHeight);
        int thumbTravel = trackHeight - thumbHeight;
        int thumbY = viewportTop + (maxScroll == 0 ? 0 : thumbTravel * scrollOffset / maxScroll);
        guiGraphics.fill(barX0, thumbY, barX1, thumbY + thumbHeight, 0x90FFFFFF);
    }

    /** No-op - avoids the vanilla blurred background under our own opaque fill. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    /** A scrollable row widget plus the Y it sits at when {@code scrollOffset == 0}. */
    private static final class ScrollRow {
        final AbstractWidget widget;
        final int baseY;
        final boolean enabledByDefault;

        ScrollRow(AbstractWidget widget, int baseY) {
            this.widget = widget;
            this.baseY = baseY;
            this.enabledByDefault = widget.active;
        }
    }
}