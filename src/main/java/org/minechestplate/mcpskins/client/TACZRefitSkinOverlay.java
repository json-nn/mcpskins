package org.minechestplate.mcpskins.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
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
import org.minechestplate.mcpskins.client.gui.TooltipPlacement;
import org.minechestplate.mcpskins.config.MCPSkinsClientConfig;
import org.minechestplate.mcpskins.config.MCPSkinsServerConfig;
import org.minechestplate.mcpskins.config.ScreenAnchor;
import org.minechestplate.mcpskins.network.ApplySkinPayload;
import org.minechestplate.mcpskins.skin.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Embeds skin browsing/selection directly into TACZ's weapon refit screen
 * ({@code com.tacz.guns.client.gui.GunRefitScreen}).
 * <p>
 * Which skin is shown is controlled entirely by the {@link SkinComponents#SKIN_ID}
 * component (read via {@link TACZSkinHelper#getSkinId(ItemStack)}) - the weapon's GunId
 * itself is never touched, so previewing a locked skin is just a temporary local edit of
 * that component, with no packet sent and no effect on ownership.
 * <p>
 * The toggle button, carousel and toast are all hand-drawn instead of widgets: switching
 * attachment tabs on {@code GunRefitScreen} rebuilds its widget list without a full
 * {@code Screen.init()}, so anything added as a widget would disappear on the next tab
 * switch. They're drawn in {@link #onScreenRenderPost} and hit-tested in
 * {@link #onMouseClicked}.
 * <p>
 * Assumes the refit screen always operates on the weapon in the player's hand (main or
 * offhand) - see {@link #getViewedGunStack()}. The toggle button's position comes from
 * {@link MCPSkinsClientConfig} (see {@code RefitButtonPositionScreen} for the in-game
 * picker, or {@link #toggleButtonBounds} for the default).
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public class TACZRefitSkinOverlay {

    // Fully qualified name of the native TACZ screen this class hooks into
    private static final String GUN_REFIT_SCREEN_CLASS = "com.tacz.guns.client.gui.GunRefitScreen";

    private static final int PANEL_BOTTOM_MARGIN = 14;
    private static final int PANEL_FADE_HEIGHT = 24;

    // Shifts the tooltip up so it lines up with TACZ's own attachment-name label
    private static final int LABEL_Y_NUDGE = -2;

    // ---- Toast for a REAL skin application (not a preview) -----------------------------
    private static final long TOAST_FADE_MS = 350L;
    private static Component toastText = null;
    private static long toastStartTime = 0L;

    // ---- Carousel state (one active refit screen at a time, so static is fine) --------
    private static boolean skinModeActive = false;
    private static int focusedSkinIndex = 0;
    private static float animatedSkinIndex = 0f;
    // Last seen equipped bare skin id (or baseGun if no skin) - lets us tell "the weapon/skin
    // actually changed" apart from "the player is just scrolling the carousel"
    private static String lastSeenSkinId = null;

    // ---- Client-side preview state (no unlock/grant involved) --------------------------
    private static boolean previewActive = false;
    // The real (server-authoritative) bare skin id before we overwrote SKIN_ID for preview
    private static String previewOriginalSkinId = null;
    private static String previewedSkinId = null;
    private static InteractionHand previewHand = null;

    private TACZRefitSkinOverlay() {
    }

    // -----------------------------------------------------------------------------------
    // Screen init
    // -----------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;

        // Center the carousel on the equipped skin on (re)open; left alone during a preview
        syncFocusedSkinToEquipped();
    }

    /**
     * {@code GunRefitScreen.init()} fires on every attachment tab switch, so restoring the
     * real skin after a preview is tied to the screen closing, not to reinit.
     */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!isGunRefitScreen(event.getScreen())) return;
        restorePreviewIfActive();
        // Otherwise the toggle button's "lit" look would survive into the next session
        skinModeActive = false;
    }

    // -----------------------------------------------------------------------------------
    // Rendering the carousel, toast, and toggle button over the native screen
    // -----------------------------------------------------------------------------------

    // LOWEST priority so we draw last among all Render.Post subscribers on this screen -
    // TACZ itself draws attachment icons/tab highlights on Render.Post too and could
    // otherwise cover our overlay
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();

        renderToast(guiGraphics, screen);
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
            centerOnSkin(weapon, equippedSkinId);
        }

        renderPanel(guiGraphics, screen, weapon, equippedSkinId, mouseX, mouseY);
    }

    /**
     * Toggle button bounds in screen coordinates, shared by rendering and hit-testing so
     * they can't diverge.
     *
     * @return {x0, y0, size}
     */
    private static int[] toggleButtonBounds(Screen screen) {
        int size = MCPSkinsClientConfig.refitButtonSize();
        ScreenAnchor anchor = MCPSkinsClientConfig.refitButtonAnchor();
        int x = anchor.resolveX(screen.width, size, MCPSkinsClientConfig.refitButtonOffsetX());
        int y = anchor.resolveY(screen.height, size, MCPSkinsClientConfig.refitButtonOffsetY());
        return new int[]{x, y, size};
    }

    private static void renderToggleButton(GuiGraphics guiGraphics, Screen screen, int mouseX, int mouseY) {
        if (!MCPSkinsClientConfig.refitButtonEnabled()) return;

        int[] bounds = toggleButtonBounds(screen);
        int x0 = bounds[0], y0 = bounds[1], size = bounds[2];
        boolean hovered = mouseX >= x0 && mouseX <= x0 + size && mouseY >= y0 && mouseY <= y0 + size;

        RefitToggleButtonRenderer.render(guiGraphics, x0, y0, size, hovered, skinModeActive);

        if (hovered && MCPSkinsClientConfig.refitButtonTooltip()) {
            Minecraft mc = Minecraft.getInstance();
            Component label = Component.translatable("gui.mcpskins.weapon_skins_tooltip");
            int labelWidth = mc.font.width(label);
            TooltipPlacement.Result pos = TooltipPlacement.compute(x0, x0 + size, y0, y0 + size,
                    labelWidth, mc.font.lineHeight, screen.width, screen.height, 4);
            guiGraphics.drawString(mc.font, label, pos.x(), pos.y() + LABEL_Y_NUDGE, 0xFFFFFFFF);
        }
    }

    /**
     * Picks a toast top position that avoids overlapping any visible widget of
     * {@code GunRefitScreen}, by reading real widget bounds instead of a hardcoded offset -
     * keeps working even if TACZ moves its own buttons in a future update.
     */
    private static int computeToastTop(Screen screen, int toastX0, int boxWidth, int boxHeight) {
        int candidateY = 8;
        int toastX1 = toastX0 + boxWidth;

        for (int attempt = 0; attempt < 8; attempt++) {
            AbstractWidget overlapping = findOverlappingWidget(screen, toastX0, toastX1, candidateY, candidateY + boxHeight);
            if (overlapping == null) {
                break;
            }
            candidateY = overlapping.getY() + overlapping.getHeight() + 4;
        }
        return candidateY;
    }

    private static AbstractWidget findOverlappingWidget(Screen screen, int x0, int x1, int y0, int y1) {
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractWidget widget) || !widget.visible) continue;
            int wx0 = widget.getX(), wy0 = widget.getY();
            int wx1 = wx0 + widget.getWidth(), wy1 = wy0 + widget.getHeight();
            if (x0 < wx1 && x1 > wx0 && y0 < wy1 && y1 > wy0) {
                return widget;
            }
        }
        return null;
    }

    private static void renderToast(GuiGraphics guiGraphics, Screen screen) {
        if (toastText == null) return;
        if (!MCPSkinsClientConfig.toastEnabled()) {
            toastText = null;
            return;
        }

        long duration = MCPSkinsClientConfig.toastDurationMs();
        long elapsed = System.currentTimeMillis() - toastStartTime;
        if (elapsed > duration) {
            toastText = null;
            return;
        }

        float alpha;
        if (elapsed < TOAST_FADE_MS) {
            alpha = elapsed / (float) TOAST_FADE_MS;
        } else if (elapsed > duration - TOAST_FADE_MS) {
            alpha = (duration - elapsed) / (float) TOAST_FADE_MS;
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
        int y0 = computeToastTop(screen, x0, boxW, boxH);

        int bgAlpha = Math.round(alpha * 0xD0) << 24;
        int borderAlpha = Math.round(alpha * 255) << 24;
        int textAlpha = Math.round(alpha * 255) << 24;

        // Depth test off + pushed far forward on Z, so leftover depth values from TACZ's
        // own 3D render pass can't make our 2D quads render underneath it
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
        int carouselHeight = MCPSkinsClientConfig.carouselHeight();
        int panelTop = height - carouselHeight - PANEL_BOTTOM_MARGIN;
        int centerY = panelTop + carouselHeight / 2;
        int centerX = width / 2;

        guiGraphics.fillGradient(0, panelTop - PANEL_FADE_HEIGHT, width, panelTop, 0x00000000, 0x9A000000);
        guiGraphics.fill(0, panelTop, width, height, 0xB4000000);
        guiGraphics.fill(0, panelTop, width, panelTop + 1, 0x405FD3FF);

        animatedSkinIndex += (focusedSkinIndex - animatedSkinIndex) * 0.35f;
        if (Math.abs(focusedSkinIndex - animatedSkinIndex) < 0.01f) animatedSkinIndex = focusedSkinIndex;

        List<CarouselSlot> slots = computeSlots(weapon, centerX, centerY);
        Minecraft mc = Minecraft.getInstance();

        // Gentle pulse for the equipped/previewed skin's border in the center slot
        float pulse = 0.5f + 0.5f * Mth.sin((System.currentTimeMillis() % 1200L) / 1200f * ((float) Math.PI * 2f));

        for (CarouselSlot slot : slots) {
            SkinDataModels.SkinEntry entry = weapon.skins().get(slot.skinIndex());
            boolean isCurrentlyEquipped = bareId(entry.id()).equals(equippedSkinId);
            boolean unlocked = mc.player != null && SkinAttachment.isOwnedOrDefault(mc.player, entry.id());
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

            if (isCenter && (isCurrentlyEquipped || isPreviewed)) {
                int glowRgb = isCurrentlyEquipped ? 0x5FD3FF : 0xFFB347;
                int glowAlpha = Math.round(slot.alpha() * (0x40 + Math.round(pulse * 0x60))) << 24;
                guiGraphics.renderOutline(x0 - 2, y0 - 2, slot.size() + 4, slot.size() + 4, (glowRgb & 0xFFFFFF) | glowAlpha);
            }

            // Same createGunStack -> TimelessAPI -> mixin path as the held weapon, so an
            // optional "<skinId>_icon.png" is picked up automatically
            ItemStack thumb = TACZSkinHelper.createGunStack(weapon.baseGun(), entry.id());
            int iconOffset = (slot.size() - 16) / 2;
            guiGraphics.renderItem(thumb, x0 + iconOffset, y0 + iconOffset);

            if (!unlocked && !isPreviewed) {
                guiGraphics.fill(x0, y0, x0 + slot.size(), y0 + slot.size(), 0x80000000);
                int lockW = 8, lockH = 8;
                int lx = x0 + slot.size() - lockW - 2;
                int ly = y0 + slot.size() - lockH - 2;
                int lockColor = (Math.round(slot.alpha() * 255) << 24) | 0xE8E8E8;
                guiGraphics.renderOutline(lx + 1, ly, lockW - 2, 4, lockColor);
                guiGraphics.fill(lx, ly + 3, lx + lockW, ly + lockH, lockColor);
            } else if (!unlocked) {
                // Previewed but not owned - amber tint instead of a dark overlay
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
                guiGraphics.drawCenteredString(mc.font, status, centerX, panelTop + carouselHeight - 12, statusColor);

                String counter = (slot.skinIndex() + 1) + " / " + weapon.skins().size();
                guiGraphics.drawString(mc.font, counter, width - mc.font.width(counter) - 8, panelTop + 4, 0x80FFFFFF, false);
            }
        }

        if (focusedSkinIndex > 0) {
            guiGraphics.drawCenteredString(mc.font, Component.literal("‹"), 14, centerY - 4, 0x80FFFFFF);
        }
        if (focusedSkinIndex < weapon.skins().size() - 1) {
            guiGraphics.drawCenteredString(mc.font, Component.literal("›"), width - 14, centerY - 4, 0x80FFFFFF);
        }
    }

    // -----------------------------------------------------------------------------------
    // Input handling: clicks on the toggle button and carousel slots
    // -----------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;
        if (event.getButton() != 0) return;

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();

        int[] bounds = toggleButtonBounds(screen);
        if (MCPSkinsClientConfig.refitButtonEnabled()
                && mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2]
                && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[2]) {
            skinModeActive = !skinModeActive;
            if (!skinModeActive) {
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

        int carouselHeight = MCPSkinsClientConfig.carouselHeight();
        int panelTop = screen.height - carouselHeight - PANEL_BOTTOM_MARGIN;
        if (mouseY < panelTop - PANEL_FADE_HEIGHT) return;

        int centerX = screen.width / 2;
        int centerY = panelTop + carouselHeight / 2;

        for (CarouselSlot slot : computeSlots(weapon, centerX, centerY)) {
            int half = slot.size() / 2;
            if (mouseX >= slot.centerX() - half && mouseX <= slot.centerX() + half
                    && mouseY >= slot.centerY() - half && mouseY <= slot.centerY() + half) {
                focusedSkinIndex = slot.skinIndex();
                SkinDataModels.SkinEntry entry = weapon.skins().get(slot.skinIndex());

                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null && SkinAttachment.isOwnedOrDefault(player, entry.id())) {
                    // Sets SKIN_ID on the held item optimistically, before the packet is
                    // sent - otherwise, if a preview was active, the item would keep showing
                    // the previewed skin until the server's sync packet arrives
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

                    PacketDistributor.sendToServer(SkinAttachment.isDefaultEntry(entry.id())
                            ? ApplySkinPayload.removeSkin()
                            : ApplySkinPayload.equip(entry.id()));
                    clearPreviewState();
                    toastText = Component.translatable("gui.mcpskins.toast_skin_applied", entry.name());
                    toastStartTime = System.currentTimeMillis();
                    player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.4f);
                } else if (player != null && MCPSkinsServerConfig.allowLockedSkinPreview()) {
                    previewLockedSkin(bareId(entry.id()));
                    player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.0f);
                }
                event.setCanceled(true);
                return;
            }
        }

        // Clicking anywhere left/right of the slot cluster switches skins, mirroring the
        // scroll wheel - not just the arrow glyph itself
        if (mouseX < centerX) {
            if (focusedSkinIndex > 0) {
                focusedSkinIndex--;
                event.setCanceled(true);
                return;
            }
        } else if (focusedSkinIndex < weapon.skins().size() - 1) {
            focusedSkinIndex++;
            event.setCanceled(true);
            return;
        }

        // Swallow other clicks in the carousel strip so they don't fall through to TACZ's
        // attachment slots underneath our panel
        if (mouseY >= panelTop) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!skinModeActive) return;
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;

        int panelTop = screen.height - MCPSkinsClientConfig.carouselHeight() - PANEL_BOTTOM_MARGIN;
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
    // Client-side skin preview (no unlock/grant involved)
    // -----------------------------------------------------------------------------------

    /**
     * Temporarily writes or clears {@link SkinComponents#SKIN_ID} on the held item,
     * client-side only, the same code path as a real skin application. Ownership doesn't
     * change; the real value is restored in {@link #restorePreviewIfActive()}.
     *
     * @param skinIdBare bare skin id to preview, or the weapon's baseGun to preview "no skin"
     */
    private static void previewLockedSkin(String skinIdBare) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        InteractionHand hand = resolveGunHand(mc.player);
        if (hand == null) return;

        ItemStack heldGun = mc.player.getItemInHand(hand);
        if (heldGun.isEmpty()) return;

        if (!previewActive) {
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

    /** Restores the weapon's real skin component if a preview is active. */
    private static void restorePreviewIfActive() {
        if (!previewActive) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && previewHand != null) {
            ItemStack heldGun = mc.player.getItemInHand(previewHand);
            if (!heldGun.isEmpty()) {
                // null previewOriginalSkinId means "had no skin" - applySkin(..., null)
                // correctly clears the component
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
     * Wipes all per-session UI state. Called on disconnect.
     * <p>
     * Every field here is static, which is fine while a session is running - there's only
     * ever one refit screen. Across sessions it isn't: {@link #restorePreviewIfActive()} is
     * driven by {@code ScreenEvent.Closing}, so being disconnected mid-preview (kick, timeout,
     * server restart) never fires it and leaves {@code previewActive} set with a stale hand
     * and skin id. The next world would then restore a previous server's skin onto an
     * unrelated weapon. Preview edits are client-local, so there is nothing to write back
     * here - just drop the state.
     */
    public static void resetSessionState() {
        clearPreviewState();
        skinModeActive = false;
        focusedSkinIndex = 0;
        animatedSkinIndex = 0f;
        lastSeenSkinId = null;
        toastText = null;
        toastStartTime = 0L;
    }

    /** The weapon's real bare skin id, or {@code null} for no skin - unaffected by an active preview. */
    private static String getRealSkinId() {
        if (previewActive) return previewOriginalSkinId;
        return TACZSkinHelper.getSkinId(getViewedGunStack());
    }

    /** "No skin" is equivalent, for comparison, to the default skin entry's bare id (== baseGun). */
    private static String normalizeEquipped(String skinIdOrNull, String baseGun) {
        return skinIdOrNull == null ? baseGun : skinIdOrNull;
    }

    // -----------------------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------------------

    private static boolean isGunRefitScreen(Screen screen) {
        return screen != null && GUN_REFIT_SCREEN_CLASS.equals(screen.getClass().getName());
    }

    private static InteractionHand resolveGunHand(LocalPlayer player) {
        if (TACZSkinHelper.getGunId(player.getMainHandItem()) != null) return InteractionHand.MAIN_HAND;
        if (TACZSkinHelper.getGunId(player.getOffhandItem()) != null) return InteractionHand.OFF_HAND;
        return null;
    }

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
    // Carousel geometry (same "coverflow" approach as SkinHubScreen)
    // -----------------------------------------------------------------------------------

    private record CarouselSlot(int skinIndex, int centerX, int centerY, int size, float alpha, float distance) {
    }

    private static List<CarouselSlot> computeSlots(SkinDataModels.WeaponSkins weapon, int centerX, int centerY) {
        List<CarouselSlot> slots = new ArrayList<>();
        List<SkinDataModels.SkinEntry> skins = weapon.skins();
        int slotBase = MCPSkinsClientConfig.carouselSlotSize();
        int spacing = MCPSkinsClientConfig.carouselSlotSpacing();

        for (int i = 0; i < skins.size(); i++) {
            float offset = i - animatedSkinIndex;
            float dist = Math.abs(offset);
            if (dist > 3.2f) continue;

            float scale = Mth.clamp(1.35f - dist * 0.3f, 0.4f, 1.35f);
            float alpha = Mth.clamp(1.2f - dist * 0.4f, 0f, 1f);
            int size = Math.round(slotBase * scale);
            int cx = centerX + Math.round(offset * spacing);

            slots.add(new CarouselSlot(i, cx, centerY, size, alpha, dist));
        }
        slots.sort(Comparator.comparingDouble((CarouselSlot s) -> -s.distance()));
        return slots;
    }
}