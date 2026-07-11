package org.minechestplate.mcpskins.skin.network;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.ArrayList;
import java.util.List;

public record ApplySkinPayload(String skinId) implements CustomPacketPayload {
    public static final Type<ApplySkinPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "apply_skin"));

    // Translation key строки лора "владелец скина". Раньше старая строка лора
    // находилась поиском по префиксу РЕНДЕРЕННОГО текста ("▪ Владелец скина: ") -
    // с переводом это ломается (после локализации строка может рендериться на любом
    // языке). Вместо этого сравниваем translation key самого компонента - это
    // однозначно и не зависит от языка.
    private static final String OWNER_LORE_KEY = "tooltip.mcpskins.skin_owner";

    public static final StreamCodec<FriendlyByteBuf, ApplySkinPayload> CODEC = CustomPacketPayload.codec(
            ApplySkinPayload::write, ApplySkinPayload::new
    );

    public ApplySkinPayload(FriendlyByteBuf buffer) {
        this(buffer.readUtf());
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(skinId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            if (!SkinAttachment.hasSkin(player, skinId) && !player.hasPermissions(2)) {
                return;
            }

            ItemStack mainHand = player.getMainHandItem();

            // ГЛАВНОЕ ОТЛИЧИЕ ОТ СТАРОЙ ВЕРСИИ: GunId оружия в руке БОЛЬШЕ НЕ МЕНЯЕТСЯ.
            // Он и есть настоящий базовый gunId - используем его напрямую, без
            // SkinManager.getBaseGun() (та индирекция была нужна только чтобы "распутать"
            // случаи, когда GunId уже был подменён на скин-пушку - таких случаев больше
            // не бывает в принципе).
            String heldBaseGun = TACZSkinHelper.getGunId(mainHand);
            if (heldBaseGun == null) {
                return; // В руке не оружие TACZ (или пустая рука)
            }

            String requestedBaseGun = SkinManager.INSTANCE.getBaseGun(skinId);
            if (!heldBaseGun.equals(requestedBaseGun)) {
                // Запрещаем натягивать скин от другой пушки
                return;
            }

            ItemStack newWeapon = TACZSkinHelper.applySkin(mainHand, skinId);
            if (newWeapon == mainHand || newWeapon.isEmpty()) {
                return;
            }

            // Лор "владелец скина" имеет смысл только когда реально что-то надето, а не
            // когда игрок вернул оружие в заводской вид - иначе на голой пушке остаётся
            // странная строка "Владелец скина: ...".
            boolean isStock = TACZSkinHelper.bareSkinId(skinId).equals(heldBaseGun);

            ItemLore currentLore = newWeapon.get(DataComponents.LORE);
            List<Component> newLines = new ArrayList<>();
            if (currentLore != null) {
                for (Component line : currentLore.lines()) {
                    if (!isSkinOwnerLoreLine(line)) {
                        newLines.add(line);
                    }
                }
            }

            if (!isStock) {
                String playerName = player.getName().getString();
                Component ownerLore = Component.translatable(OWNER_LORE_KEY,
                                Component.literal(playerName).withStyle(net.minecraft.ChatFormatting.GOLD))
                        .withStyle(net.minecraft.ChatFormatting.GRAY);
                newLines.add(ownerLore);
            }

            newWeapon.set(DataComponents.LORE, new ItemLore(newLines));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, newWeapon);
        });
    }

    private static boolean isSkinOwnerLoreLine(Component component) {
        return component.getContents() instanceof TranslatableContents contents
                && OWNER_LORE_KEY.equals(contents.getKey());
    }
}