package org.minechestplate.mcpskins.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.SkinTranslations;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

/** Chat feedback for unlocking and fusing, built in the recipient's own language. */
final class UnlockMessages {

    private UnlockMessages() {
    }

    /** The player's own language, so server-sent text matches what their Armory shows. */
    static String localeOf(Player player) {
        return player instanceof ServerPlayer server
                ? server.clientInformation().language()
                : SkinTranslations.DEFAULT_LOCALE;
    }

    static Component unlocked(String locale, String skinId) {
        SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);
        if (lookup == null) {
            // The unlock itself already happened, so report it rather than staying silent.
            return green(Component.translatable("message.mcpskins.skin_unlocked_fallback", skinId));
        }
        Component skin = skinName(locale, lookup.skin());
        Component gun = gunName(lookup);
        return green(gun == null
                ? Component.translatable("message.mcpskins.unlock_success", skin)
                : Component.translatable("message.mcpskins.unlock_success_for", skin, gun));
    }

    static Component fused(String locale, SkinDataModels.Rarity fromRarity, SkinDataModels.SkinLookupResult rolled) {
        Component from = fromRarity.label(locale);
        Component skin = skinName(locale, rolled.skin());
        Component gun = gunName(rolled);
        return green(gun == null
                ? Component.translatable("message.mcpskins.fuse_success", from, skin)
                : Component.translatable("message.mcpskins.fuse_success_for", from, skin, gun));
    }

    /** Hoverable preview of the weapon wearing this skin, or null when TACZ has no such gun. */
    private static Component gunName(SkinDataModels.SkinLookupResult lookup) {
        ItemStack previewGun = TACZSkinHelper.createGunStack(lookup.weapon().baseGun(), lookup.skin().id());
        if (previewGun.isEmpty()) return null;
        return TACZSkinHelper.gunDisplayName(lookup.weapon().baseGun()).copy().withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withUnderlined(true)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(previewGun))));
    }

    private static Component green(MutableComponent message) {
        return message.withStyle(ChatFormatting.GREEN);
    }

    /** Rarity-coloured and clickable, so the player can jump straight to it in the Armory. */
    static Component skinName(String locale, SkinDataModels.SkinEntry skin) {
        return Component.literal(SkinTranslations.text(locale, SkinTranslations.skinKey(skin.id(), "name"), skin.name()))
                .withStyle(style -> style
                        .withColor(skin.labelColor())
                        .withBold(true)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mcpskins armory " + skin.id()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("tooltip.mcpskins.open_in_armory"))));
    }
}
