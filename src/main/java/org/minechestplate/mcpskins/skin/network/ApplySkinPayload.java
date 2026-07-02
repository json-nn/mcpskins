package org.minechestplate.mcpskins.skin.network;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
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
            if (context.player() instanceof ServerPlayer player) {
                if (!SkinAttachment.hasSkin(player, skinId) && !player.hasPermissions(2)) {
                    return;
                }
                ItemStack mainHand = player.getMainHandItem();

                // ФИКС КРИТИЧЕСКОЙ ОШИБКИ: Строгая проверка базового оружия
                CustomData data = mainHand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
                if (!data.contains("GunId")) {
                    return; // В руках нет оружия
                }

                String currentGunId = data.copyTag().getString("GunId");
                String currentBaseGun = SkinManager.INSTANCE.getBaseGun(currentGunId);
                String requestedBaseGun = SkinManager.INSTANCE.getBaseGun(skinId);

                // Запрещаем натягивать скин от другой пушки
                if (!currentBaseGun.equals(requestedBaseGun)) {
                    return;
                }

                ItemStack newWeapon = TACZSkinHelper.applySkinSafely(mainHand, skinId);

                if (!newWeapon.isEmpty()) {
                    String playerName = player.getName().getString();
                    ItemLore currentLore = newWeapon.get(DataComponents.LORE);
                    List<Component> newLines = new ArrayList<>();

                    if (currentLore != null) {
                        for (Component line : currentLore.lines()) {
                            if (!line.getString().startsWith("▪ Владелец скина: ")) {
                                newLines.add(line);
                            }
                        }
                    }

                    Component ownerLore = Component.literal("▪ ").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
                            .append(Component.literal("Владелец скина: ").withStyle(net.minecraft.ChatFormatting.GRAY))
                            .append(Component.literal(playerName).withStyle(net.minecraft.ChatFormatting.GOLD));

                    newLines.add(ownerLore);
                    newWeapon.set(DataComponents.LORE, new ItemLore(newLines));
                }

                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, newWeapon);
            }
        });
    }
}