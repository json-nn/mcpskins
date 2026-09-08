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
import org.minechestplate.mcpskins.client.gui.ArmoryTheme;
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
 * Embeds a skin carousel into TACZ's weapon refit screen. The displayed skin is purely a
 * function of {@link SkinComponents#SKIN_ID}; the GunId is never touched, and previewing a
 * locked skin is a local component edit with no packet and no effect on ownership.
 * <p>
 * Drawn rather than added as widgets: switching attachment tabs rebuilds
 * {@code GunRefitScreen}'s widget list without a full {@code init()}, so widgets would vanish.
 * Uses {@link ArmoryTheme}'s sprites, so it reskins with the Armory.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public class TACZRefitSkinOverlay {

    private static final String GUN_REFIT_SCREEN_CLASS = "com.tacz.guns.client.gui.GunRefitScreen";

    private static final int PANEL_BOTTOM_MARGIN = 14;
    /** Extra grab room above the tray, so a click just over it still counts. */
    private static final int TRAY_CLICK_MARGIN = 24;
    private static final int TRAY_MARGIN = 8;
    private static final int TRAY_PAD = 12;
    private static final float SLOT_MAX_SCALE = 1.35f;

    /** Shorter than the cull in {@link #computeSlots}: slot alpha hits zero at 3 anyway. */
    private static final float SLOT_TRAY_REACH = 2.5f;

    private static final int TRAY_SHADOW = 3;

    /** Amber for a previewed but unowned skin, the one colour outside the theme. */
    private static final int PREVIEW_ACCENT = 0xFFB347;

    // Lines the tooltip up with TACZ's own attachment-name label.
    private static final int LABEL_Y_NUDGE = -2;

    private static final long TOAST_FADE_MS = 350L;
    private static Component toastText = null;
    private static long toastStartTime = 0L;

    private static boolean skinModeActive = false;
    private static int focusedSkinIndex = 0;
    private static float animatedSkinIndex = 0f;
    // Tells "the weapon/skin changed" apart from "the player is scrolling the carousel".
    private static String lastSeenSkinId = null;

    private static boolean previewActive = false;
    // The real (server-authoritative) bare skin id before we overwrote SKIN_ID for preview
    private static String previewOriginalSkinId = null;
    private static String previewedSkinId = null;
    private static InteractionHand previewHand = null;

    private TACZRefitSkinOverlay() {
    }


    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;

        // Centred on the equipped skin at (re)open, but left alone during a preview.
        syncFocusedSkinToEquipped();
    }

    /** Tied to closing, not init: init fires on every attachment tab switch. */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!isGunRefitScreen(event.getScreen())) return;
        restorePreviewIfActive();
        // Or the toggle button's "lit" look survives into the next session.
        skinModeActive = false;
    }


    // LOWEST so we draw after TACZ's own Render.Post work, which would otherwise cover us.
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

    /** @return {x0, y0, size}, shared by rendering and hit-testing so the two can't diverge */
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

    /** Read off the live widget bounds, so it survives TACZ moving its own buttons. */
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

        // Depth off and pushed forward, so TACZ's leftover 3D depth values cannot bury this.
        RenderSystem.disableDepthTest();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 900.0F);

        ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.INSET, x0, y0, boxW, boxH, bgAlpha | 0xFFFFFF);
        guiGraphics.fill(x0 + 2, y0, x0 + boxW - 2, y0 + 1, borderAlpha | (ArmoryTheme.ACCENT & 0xFFFFFF));
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

        animatedSkinIndex += (focusedSkinIndex - animatedSkinIndex) * 0.35f;
        if (Math.abs(focusedSkinIndex - animatedSkinIndex) < 0.01f) animatedSkinIndex = focusedSkinIndex;

        List<CarouselSlot> slots = computeSlots(weapon, centerX, centerY);
        Minecraft mc = Minecraft.getInstance();

        // Width comes from the skin count rather than the live slot positions, so the tray
        // stays put while the carousel scrolls under it.
        int slotSize = MCPSkinsClientConfig.carouselSlotSize();
        int centreSlotHalf = Math.round(slotSize * SLOT_MAX_SCALE) / 2;
        int halfSpan = Math.round(Math.min(SLOT_TRAY_REACH, weapon.skins().size() - 1)
                * MCPSkinsClientConfig.carouselSlotSpacing()) + centreSlotHalf;
        int trayHeight = Math.max(carouselHeight, centreSlotHalf * 2 + 24);
        int trayTop = centerY - trayHeight / 2;
        int trayX0 = Math.max(TRAY_MARGIN, centerX - halfSpan - TRAY_PAD);
        int trayX1 = Math.min(width - TRAY_MARGIN, centerX + halfSpan + TRAY_PAD);

        // Drawn past the content bounds: the sprite carries its own shadow in its border. No
        // tint or fill behind it, so a resource pack replacing tray.png gets what it authored.
        ArmoryTheme.sprite(guiGraphics, ArmoryTheme.TRAY, trayX0 - TRAY_SHADOW, trayTop - TRAY_SHADOW,
                (trayX1 - trayX0) + TRAY_SHADOW * 2, trayHeight + TRAY_SHADOW * 2);

        float pulse = 0.5f + 0.5f * Mth.sin((System.currentTimeMillis() % 1200L) / 1200f * ((float) Math.PI * 2f));

        guiGraphics.enableScissor(trayX0 + 2, trayTop + 1, trayX1 - 2, trayTop + trayHeight - 1);
        try {
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

                int rarity = ArmoryTheme.readable(entry.labelColor());
                int ringRgb = isCurrentlyEquipped ? ArmoryTheme.ACCENT
                        : isPreviewed ? PREVIEW_ACCENT
                        : hovered && !isCenter ? ArmoryTheme.TEXT_50
                        : rarity;

                ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.TILE, x0, y0, slot.size(), slot.size(),
                        alphaByte | 0xFFFFFF);
                ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.TILE_RING, x0, y0, slot.size(), slot.size(),
                        (Math.round(slot.alpha() * (isCenter ? 255 : 115)) << 24) | (ringRgb & 0xFFFFFF));
                if (isCenter && (isCurrentlyEquipped || isPreviewed)) {
                    int glowRgb = isCurrentlyEquipped ? ArmoryTheme.ACCENT : PREVIEW_ACCENT;
                    int glowAlpha = Math.round(slot.alpha() * (0x40 + Math.round(pulse * 0x60))) << 24;
                    ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.TILE_RING, x0 - 2, y0 - 2,
                            slot.size() + 4, slot.size() + 4, glowAlpha | (glowRgb & 0xFFFFFF));
                }

                // Same path as the held weapon, so "<skinId>_icon.png" is picked up.
                ItemStack thumb = TACZSkinHelper.createGunStack(weapon.baseGun(), entry.id());
                int iconOffset = (slot.size() - 16) / 2;
                guiGraphics.renderItem(thumb, x0 + iconOffset, y0 + iconOffset);

                if (!unlocked && !isPreviewed) {
                    ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ROW, x0 + 1, y0 + 1,
                            slot.size() - 2, slot.size() - 2,
                            (Math.round(slot.alpha() * 0x8C) << 24) | (ArmoryTheme.LOCKED_DIM & 0xFFFFFF));
                    ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ICON_LOCK,
                            x0 + slot.size() - 11, y0 + slot.size() - 11, 8, 8,
                            (Math.round(slot.alpha() * 255) << 24) | (ArmoryTheme.TEXT_50 & 0xFFFFFF));
                } else if (!unlocked) {
                    guiGraphics.fill(x0, y0, x0 + slot.size(), y0 + slot.size(), (Math.round(slot.alpha() * 0x30) << 24) | 0xFFB347);
                }

                if (isCenter) {
                    String name = ArmoryTheme.truncate(mc.font, SkinTranslations.name(entry), trayX1 - trayX0 - 60, 1f);
                    guiGraphics.drawCenteredString(mc.font, name, centerX, trayTop + 5, rarity);

                    Component status;
                    int statusColor;
                    if (isCurrentlyEquipped) {
                        status = Component.translatable("gui.mcpskins.status_equipped");
                        statusColor = ArmoryTheme.ACCENT;
                    } else if (isPreviewed) {
                        status = Component.translatable("gui.mcpskins.status_preview_locked");
                        statusColor = PREVIEW_ACCENT;
                    } else if (unlocked) {
                        status = Component.translatable("gui.mcpskins.status_click_to_equip");
                        statusColor = ArmoryTheme.TEXT_50;
                    } else {
                        status = Component.translatable("gui.mcpskins.status_click_to_preview");
                        statusColor = 0xFF8080;
                    }
                    guiGraphics.drawCenteredString(mc.font, status, centerX, trayTop + trayHeight - 13, statusColor);

                    String counter = (slot.skinIndex() + 1) + " / " + weapon.skins().size();
                    guiGraphics.drawString(mc.font, counter, trayX1 - 6 - mc.font.width(counter),
                            trayTop + 5, ArmoryTheme.TEXT_35, false);
                }
            }
        } finally {
            guiGraphics.disableScissor();
        }

        if (focusedSkinIndex > 0) {
            guiGraphics.drawCenteredString(mc.font, Component.literal("‹"), trayX0 + 7,
                    centerY - 4, ArmoryTheme.TEXT_50);
        }
        if (focusedSkinIndex < weapon.skins().size() - 1) {
            guiGraphics.drawCenteredString(mc.font, Component.literal("›"), trayX1 - 7,
                    centerY - 4, ArmoryTheme.TEXT_50);
        }
    }


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
        if (mouseY < panelTop - TRAY_CLICK_MARGIN) return;

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
                    // Optimistic, or the preview lingers until the server's sync lands.
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
                    toastText = Component.translatable("gui.mcpskins.toast_skin_applied", SkinTranslations.name(entry));
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

        // Anywhere left or right of the cluster switches skins, not just the arrow glyph.
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

        // Swallowed, or the click falls through to TACZ's attachment slots underneath.
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


    /**
     * Client-side only, down the same path as a real application. Ownership doesn't change, and
     * the real value comes back in {@link #restorePreviewIfActive()}.
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

    private static void restorePreviewIfActive() {
        if (!previewActive) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && previewHand != null) {
            ItemStack heldGun = mc.player.getItemInHand(previewHand);
            if (!heldGun.isEmpty()) {
                // A null original means "had no skin"; applySkin clears the component for it.
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
     * {@link #restorePreviewIfActive()} runs from {@code ScreenEvent.Closing}, which never fires
     * on a kick or a timeout, leaving a stale hand and skin id set for the next world.
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

    /** Unaffected by an active preview. Null means no skin. */
    private static String getRealSkinId() {
        if (previewActive) return previewOriginalSkinId;
        return TACZSkinHelper.getSkinId(getViewedGunStack());
    }

    /** "No skin" compares equal to the default entry's bare id, which is the baseGun. */
    private static String normalizeEquipped(String skinIdOrNull, String baseGun) {
        return skinIdOrNull == null ? baseGun : skinIdOrNull;
    }


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

    // Carousel geometry (same "coverflow" approach as SkinHubScreen)

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
            float alpha = Mth.clamp(1.2f - dist * 0.4f, 0f, 1f);
            // Cull on alpha, not distance: renderItem ignores it, so a fully faded slot would
            // still draw its icon with no tile behind it.
            if (alpha <= 0.02f) continue;

            float scale = Mth.clamp(SLOT_MAX_SCALE - dist * 0.3f, 0.4f, SLOT_MAX_SCALE);
            int size = Math.round(slotBase * scale);
            int cx = centerX + Math.round(offset * spacing);

            slots.add(new CarouselSlot(i, cx, centerY, size, alpha, dist));
        }
        slots.sort(Comparator.comparingDouble((CarouselSlot s) -> -s.distance()));
        return slots;
    }
}