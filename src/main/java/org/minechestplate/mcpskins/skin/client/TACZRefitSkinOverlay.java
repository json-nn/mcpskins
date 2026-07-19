package org.minechestplate.mcpskins.skin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * Embeds skin browsing/selection directly into TACZ's weapon refit screen
 * ({@code com.tacz.guns.client.gui.GunRefitScreen}).
 * <p>
 * Which skin is shown is controlled entirely by the {@link SkinComponents#SKIN_ID}
 * component (read via {@link TACZSkinHelper#getSkinId(ItemStack)}); the weapon's GunId
 * itself is never changed, for either a real skin application or the client-side preview
 * below. Previewing a locked skin is therefore just a temporary local edit of that same
 * component on the held item - no packet sent to the server, and no effect on ownership.
 * <p>
 * <b>Why the toggle button is hand-drawn instead of a {@code Button} widget:</b> switching
 * attachment tabs (GRIP/SCOPE/MUZZLE/...) on {@code GunRefitScreen} rebuilds its own widget
 * list without going through a full {@code Screen.init()}, so a widget added via
 * {@code ScreenEvent.Init.Post} would silently disappear on the next tab switch. The
 * carousel and toast never had this problem because they're drawn manually in
 * {@link #onScreenRenderPost} and hit-tested manually in {@link #onMouseClicked}; the
 * toggle button now works the same way for the same reason.
 * <p>
 * Assumes the refit screen always operates on the weapon currently in the player's hand
 * (main or offhand) - see {@link #getViewedGunStack()}. If your fork opens refit some
 * other way, that method needs adjusting. The toggle button's position
 * ({@link #TOGGLE_MARGIN_TOP}/{@link #TOGGLE_MARGIN_RIGHT}) is tuned by eye against a
 * screenshot; adjust if it overlaps native icons on your resource pack.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public class TACZRefitSkinOverlay {

    // Fully qualified name of the native TACZ screen this class hooks into
    private static final String GUN_REFIT_SCREEN_CLASS = "com.tacz.guns.client.gui.GunRefitScreen";

    // ---- Layout tuning (adjust for your TACZ resource pack/resolution) ----------------
    private static final int TOGGLE_SIZE = 20;
    private static final int TOGGLE_MARGIN_RIGHT = 8;
    private static final int TOGGLE_MARGIN_TOP = 108;

    // assets/mcpskins/textures/gui/skin_switch_icon.png - replaces the old "SK"/"✕" text
    // glyphs on the toggle button (see renderToggleButton)
    private static final ResourceLocation TOGGLE_ICON =
            ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "textures/gui/skin_switch_icon.png");
    private static final int TOGGLE_ICON_TEX_SIZE = 64; // native resolution of skin_switch_icon.png
    private static final int TOGGLE_ICON_DRAW_SIZE = 16; // rendered size inside the 20x20 button

    private static final int CAROUSEL_HEIGHT = 96;
    private static final int CAROUSEL_SLOT_BASE = 44;
    private static final int CAROUSEL_SPACING = 60;
    private static final int PANEL_BOTTOM_MARGIN = 14;
    private static final int PANEL_FADE_HEIGHT = 24;

    // ---- Toast for a REAL skin application (not a preview) -----------------------------
    private static final long TOAST_DURATION_MS = 2200L;
    private static final long TOAST_FADE_MS = 350L;
    // Component rather than String, so the toast text stays translatable
    private static Component toastText = null;
    private static long toastStartTime = 0L;

    // ---- Carousel state (one active refit screen at a time, so static is fine) --------
    private static boolean skinModeActive = false;
    private static int focusedSkinIndex = 0;
    private static float animatedSkinIndex = 0f;
    // Last seen equipped bare skin id (or baseGun if no skin) - distinguishes "the
    // weapon/skin actually changed" from "the player is just scrolling the carousel"
    private static String lastSeenSkinId = null;

    // ---- Client-side preview state (no unlock/grant involved) --------------------------
    // previewOriginalSkinId is the real (server-authoritative) bare skin id the weapon had
    // before we temporarily overwrote the SKIN_ID component for preview purposes.
    // previewActive tracks whether a preview is in progress, kept separate from a null
    // check since "no skin" and "no preview" are both legitimately null.
    private static boolean previewActive = false;
    private static String previewOriginalSkinId = null;
    private static String previewedSkinId = null;
    private static InteractionHand previewHand = null;

    private TACZRefitSkinOverlay() {
    }

    // -----------------------------------------------------------------------------------
    // Screen init - only syncs carousel focus. The toggle button is no longer registered
    // as a widget here - see the class javadoc and renderToggleButton()/onMouseClicked().
    // -----------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;

        // Center the carousel on the currently equipped skin on (re)open. Left alone
        // during an active preview, so it doesn't reset on every attachment tab switch.
        syncFocusedSkinToEquipped();
    }

    /**
     * {@code GunRefitScreen.init()} fires repeatedly per session (on every attachment tab
     * switch), so restoring the real skin after a preview is tied to the screen closing
     * entirely, not to (re)initialization.
     */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!isGunRefitScreen(event.getScreen())) return;
        restorePreviewIfActive();
    }

    // -----------------------------------------------------------------------------------
    // Rendering the carousel, toast, and toggle button over the native screen
    // -----------------------------------------------------------------------------------

    // priority = LOWEST: multiple mods can subscribe to Render.Post on this screen (TACZ
    // itself draws attachment icons, tab highlights, etc. there too), and with the default
    // priority TACZ could render after us and cover the toast/button/carousel. LOWEST
    // guarantees we draw last among all Render.Post subscribers on this screen.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!isGunRefitScreen(screen)) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();

        // Toast for a real skin application draws regardless of whether the carousel is
        // open, so the player sees confirmation even after closing the panel immediately
        renderToast(guiGraphics, screen);

        // Toggle button always renders while the refit screen is open, not just while
        // skinModeActive - otherwise there'd be no way to turn skin mode on
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
            // Weapon changed (or a skin was really applied) - re-center the carousel.
            // Left alone during an active preview.
            centerOnSkin(weapon, equippedSkinId);
        }

        renderPanel(guiGraphics, screen, weapon, equippedSkinId, mouseX, mouseY);
    }

    /**
     * Toggle button bounds in screen coordinates, kept in one place so rendering
     * ({@link #renderToggleButton}) and hit-testing ({@link #onMouseClicked}) can't diverge.
     *
     * @return {x0, y0, size}
     */
    private static int[] toggleButtonBounds(Screen screen) {
        int x = screen.width - TOGGLE_MARGIN_RIGHT - TOGGLE_SIZE;
        int y = TOGGLE_MARGIN_TOP;
        return new int[]{x, y, TOGGLE_SIZE};
    }

    /**
     * Manually draws the toggle button - see the class javadoc on why it isn't a widget.
     * Styled to sit quietly among TACZ's own attachment icons instead of standing out:
     * flat fill, no border at rest, and a white outline only on hover - the same language
     * TACZ uses for its own icon row. Active state (skin mode on) gets a lighter fill
     * rather than a border, matching how TACZ marks a selected tab.
     */
    private static void renderToggleButton(GuiGraphics guiGraphics, Screen screen, int mouseX, int mouseY) {
        int[] bounds = toggleButtonBounds(screen);
        int x0 = bounds[0], y0 = bounds[1], size = bounds[2];
        boolean hovered = mouseX >= x0 && mouseX <= x0 + size && mouseY >= y0 && mouseY <= y0 + size;

        // Light gray, translucent - matches TACZ's own attachment icons instead of the
        // dark near-black box we used before
        int bg = skinModeActive ? 0xB0D6D6D6 : (hovered ? 0xA0C6C6C6 : 0x90B0B0B0);
        guiGraphics.fill(x0, y0, x0 + size, y0 + size, bg);
        if (hovered) {
            guiGraphics.renderOutline(x0, y0, size, size, 0xFFFFFFFF);
        }

        float iconAlpha = (hovered || skinModeActive) ? 1f : 0.8f;
        int iconDraw = TOGGLE_ICON_DRAW_SIZE;
        int iconX = x0 + (size - iconDraw) / 2;
        int iconY = y0 + (size - iconDraw) / 2;

        RenderSystem.setShaderColor(1f, 1f, 1f, iconAlpha);
        guiGraphics.blit(TOGGLE_ICON, iconX, iconY, iconDraw, iconDraw,
                0f, 0f, TOGGLE_ICON_TEX_SIZE, TOGGLE_ICON_TEX_SIZE,
                TOGGLE_ICON_TEX_SIZE, TOGGLE_ICON_TEX_SIZE);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        if (hovered) {
            // Right-aligned to the button's own right edge rather than centered - the
            // button sits close to the screen edge, so a centered label would run off-screen
            Minecraft mc = Minecraft.getInstance();
            Component label = Component.translatable("gui.mcpskins.weapon_skins_tooltip");
            int labelRight = x0 + size;
            guiGraphics.drawString(mc.font, label, labelRight - mc.font.width(label), y0 + size + 4, 0xFFFFFFFF);
        }
    }

    /**
     * Picks a toast top position that doesn't overlap any visible widget of
     * {@code GunRefitScreen}, by reading each widget's actual bounds rather than
     * guessing a pixel offset - so it keeps working even if TACZ moves its own
     * buttons in a future update. Starts at {@code y0 = 8} and, iteratively, pushes
     * the toast below any widget it overlaps.
     */
    private static int computeToastTop(Screen screen, int toastX0, int boxWidth, int boxHeight) {
        int candidateY = 8;
        int toastX1 = toastX0 + boxWidth;

        // Iteration cap is purely defensive - fall back to a reasonable value rather
        // than loop indefinitely on an unexpected widget layout
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
        // Reads real widget bounds via computeToastTop rather than a hardcoded offset, so
        // the toast never overlaps a native TACZ widget on other tabs/resolutions/packs
        int y0 = computeToastTop(screen, x0, boxW, boxH);

        int bgAlpha = Math.round(alpha * 0xD0) << 24;
        int borderAlpha = Math.round(alpha * 255) << 24;
        int textAlpha = Math.round(alpha * 255) << 24;

        // Disables the depth test and pushes far forward on Z while drawing: if TACZ's own
        // render() draws through the 3D pipeline with depth testing on, leftover depth
        // buffer values could make our 2D quads fail the depth check and render underneath
        // TACZ's pixels despite correct call order. Disabling it draws over the color
        // buffer unconditionally.
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

        // Semi-transparent backdrop so the carousel reads clearly over the 3D weapon/world
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

            // Pulsing outer ring on the equipped/previewed skin in the center slot
            if (isCenter && (isCurrentlyEquipped || isPreviewed)) {
                int glowRgb = isCurrentlyEquipped ? 0x5FD3FF : 0xFFB347;
                int glowAlpha = Math.round(slot.alpha() * (0x40 + Math.round(pulse * 0x60))) << 24;
                guiGraphics.renderOutline(x0 - 2, y0 - 2, slot.size() + 4, slot.size() + 4, (glowRgb & 0xFFFFFF) | glowAlpha);
            }

            // Thumbnail goes through the same createGunStack -> TimelessAPI -> mixin path
            // as the held weapon, so an optional "<skinId>_icon.png" is picked up automatically
            ItemStack thumb = TACZSkinHelper.createGunStack(weapon.baseGun(), entry.id());
            int iconOffset = (slot.size() - 16) / 2;
            guiGraphics.renderItem(thumb, x0 + iconOffset, y0 + iconOffset);

            if (!unlocked && !isPreviewed) {
                guiGraphics.fill(x0, y0, x0 + slot.size(), y0 + slot.size(), 0x80000000);
                // Small lock icon so "locked" reads at a glance, not just from the dimming
                int lockW = 8, lockH = 8;
                int lx = x0 + slot.size() - lockW - 2;
                int ly = y0 + slot.size() - lockH - 2;
                int lockColor = (Math.round(slot.alpha() * 255) << 24) | 0xE8E8E8;
                guiGraphics.renderOutline(lx + 1, ly, lockW - 2, 4, lockColor);
                guiGraphics.fill(lx, ly + 3, lx + lockW, ly + lockH, lockColor);
            } else if (!unlocked) {
                // Currently previewed but not owned - amber tint instead of a dark
                // overlay, so it doesn't read as "unavailable"
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

                // "N / total" counter in the panel's top-right corner
                String counter = (slot.skinIndex() + 1) + " / " + weapon.skins().size();
                guiGraphics.drawString(mc.font, counter, width - mc.font.width(counter) - 8, panelTop + 4, 0x80FFFFFF, false);
            }
        }

        // Edge arrows hinting the skin list continues off-screen
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

        // Toggle button click is checked first and always, regardless of skinModeActive -
        // otherwise there'd be no way to turn skin mode back on (it's hand-drawn, not a
        // widget, so Screen.mouseClicked() doesn't handle it on its own)
        int[] bounds = toggleButtonBounds(screen);
        if (mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2]
                && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[2]) {
            skinModeActive = !skinModeActive;
            if (!skinModeActive) {
                // Restore the weapon's real appearance if a preview was in progress
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
        if (mouseY < panelTop - PANEL_FADE_HEIGHT) return; // click outside our panel entirely

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
                    // The real skin application goes through the server, but the SKIN_ID
                    // component on the held item is also set optimistically here, before
                    // the packet is sent - via the same code path as previewLockedSkin().
                    //
                    // This matters because if a locked skin was being previewed right
                    // before this click, the held item's component already reflects that
                    // preview locally. clearPreviewState() below only resets the preview
                    // bookkeeping flags, not the item stack itself - so without this,
                    // the client would keep showing the previewed skin until the
                    // server's inventory sync packet arrives, which isn't instant.
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
                    // Skin not owned - client-side preview only, no server round-trip
                    previewLockedSkin(bareId(entry.id()));
                    player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.0f);
                }
                event.setCanceled(true);
                return;
            }
        }

        // The whole dark panel left/right of the slot cluster switches skins now, not just
        // the arrow glyph itself - anywhere past centerX works, mirroring the scroll wheel
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

        // Any other click within the carousel strip shouldn't fall through to TACZ's
        // attachment slots, which may physically sit beneath our panel
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
    // Client-side skin preview (no unlock/grant involved)
    // -----------------------------------------------------------------------------------

    /**
     * Temporarily writes or clears the {@link SkinComponents#SKIN_ID} component on the
     * player's actual held item, client-side only - the same code path as a real skin
     * application, so TACZ renders the preview exactly like an equipped skin. Ownership
     * doesn't change; the real component value is restored once the preview ends (see
     * {@link #restorePreviewIfActive()}).
     *
     * @param skinIdBare bare skin id to preview, or a value equal to the weapon's baseGun
     *                   to preview "no skin"
     */
    private static void previewLockedSkin(String skinIdBare) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        InteractionHand hand = resolveGunHand(mc.player);
        if (hand == null) return;

        ItemStack heldGun = mc.player.getItemInHand(hand);
        if (heldGun.isEmpty()) return;

        if (!previewActive) {
            // Remember the real component value only before the first override in this
            // preview session, so there's something to restore to
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
     * Restores the weapon's real skin component if a client-side preview is active.
     * Called when the refit screen closes and when skin mode is toggled off.
     */
    private static void restorePreviewIfActive() {
        if (!previewActive) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && previewHand != null) {
            ItemStack heldGun = mc.player.getItemInHand(previewHand);
            if (!heldGun.isEmpty()) {
                // previewOriginalSkinId == null means "had no skin" - applySkin(..., null)
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
     * The weapon's real (server-authoritative) bare skin id, or {@code null} for no skin.
     * Unlike reading {@link #getViewedGunStack()} directly, this returns the pre-preview
     * value while a preview is active.
     */
    private static String getRealSkinId() {
        if (previewActive) return previewOriginalSkinId;
        return TACZSkinHelper.getSkinId(getViewedGunStack());
    }

    /**
     * "No skin" (skinId == null) is equivalent, for carousel comparison purposes, to the
     * default skin entry's bare id, which {@code SkinManager} always sets equal to the
     * weapon's baseGun.
     */
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

    /**
     * The refit screen always operates on the weapon in the player's hand; checks the
     * main hand first, then the offhand.
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
    // Carousel geometry (same "coverflow" approach as SkinHubScreen)
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