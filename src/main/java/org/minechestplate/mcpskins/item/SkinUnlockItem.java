package org.minechestplate.mcpskins.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;
import org.minechestplate.mcpskins.skin.network.SyncUnlocksPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Расходуемый предмет-"ключ", который разблокирует один конкретный скин игроку
 * при использовании (ПКМ). Какой именно скин он разблокирует, хранится не в самом
 * Item (он один на все скины), а в NBT конкретного стака - в custom_data-компоненте,
 * под ключом {@code SkinToUnlock} (см. {@link #use}). Собирать такой стак вручную
 * НЕ рекомендуется - используйте команду
 * {@code /mcpskins give item <player> <skinId>} (см. SkinCommand), она сама
 * проверяет, что skinId существует в реестре, и предлагает автоподстановку.
 */
public class SkinUnlockItem extends Item {

    public SkinUnlockItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);

        if (!data.contains("SkinToUnlock")) {
            tooltipComponents.add(Component.translatable("tooltip.mcpskins.empty_unlock_item").withStyle(ChatFormatting.DARK_GRAY));
            super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
            return;
        }

        String skinId = data.copyTag().getString("SkinToUnlock");
        SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);

        if (lookup == null) {
            // Скина с таким id нет в реестре - либо опечатка в NBT предмета, либо
            // датапак со скинами ещё не подгрузился на момент создания стака.
            tooltipComponents.add(Component.translatable("tooltip.mcpskins.unknown_skin", skinId).withStyle(ChatFormatting.RED));
            super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
            return;
        }

        // Название скина и название пушки - динамический контент из датапака (а не
        // статичный текст интерфейса), поэтому они остаются Component.literal и
        // передаются как аргументы в translatable-шаблон, сохраняя свой стиль/цвет.
        Component skinName = Component.literal(lookup.skin().name())
                .withStyle(style -> style.withColor(lookup.skin().labelColor()));

        // Превью-стак пушки строим через TACZSkinHelper.createGunStack(gunId, skinId),
        // а НЕ вручную (как было раньше, с хардкодом "tacz:modern_kinetic_gun" и голым
        // GunId без компонента скина) - это важно, потому что createGunStack ещё и
        // проставляет SkinComponents.SKIN_ID, из-за чего миксин TimelessAPIMixin
        // подставляет правильную (перекрашенную) текстуру. Без этого тултип показывал
        // бы иконку ОБЫЧНОЙ, не перекрашенной пушки - что и вводило в заблуждение.
        ItemStack previewGun = TACZSkinHelper.createGunStack(lookup.weapon().baseGun(), lookup.skin().id());
        MutableComponent line;
        if (!previewGun.isEmpty()) {
            Component gunName = previewGun.getHoverName().copy().withStyle(style -> style.withColor(ChatFormatting.YELLOW));
            line = Component.translatable("tooltip.mcpskins.unlocks_for", skinName, gunName).withStyle(ChatFormatting.GRAY);
        } else {
            line = Component.translatable("tooltip.mcpskins.unlocks", skinName).withStyle(ChatFormatting.GRAY);
        }
        // В тултипе не добавляем HoverEvent на превью-стак - Minecraft не поддерживает
        // тултип внутри тултипа, вложенный SHOW_ITEM здесь просто не отрисуется.

        tooltipComponents.add(line);
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);

        if (!data.contains("SkinToUnlock")) {
            // "Пустой" предмет без NBT - ничего не делаем, ведём себя как обычный предмет без use-логики.
            return InteractionResultHolder.pass(stack);
        }

        String skinId = data.copyTag().getString("SkinToUnlock");

        if (SkinAttachment.hasSkin(player, skinId)) {
            if (!level.isClientSide()) {
                player.sendSystemMessage(Component.translatable("message.mcpskins.already_have_skin").withStyle(ChatFormatting.RED));
            }
            return InteractionResultHolder.fail(stack);
        }

        if (!level.isClientSide()) {
            SkinAttachment.unlockSkin(player, skinId);
            PacketDistributor.sendToPlayer((ServerPlayer) player, new SyncUnlocksPayload(new ArrayList<>(player.getData(SkinAttachment.UNLOCKED_SKINS))));

            player.sendSystemMessage(buildUnlockChatMessage(skinId));
        } else {
            // Звук проигрываем именно на клиенте (а не рассылаем с сервера) - так он
            // слышен мгновенно, без задержки на round-trip до сервера и обратно.
            level.playSound(player, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.5f);
        }

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * Собирает красивое интерактивное сообщение в чат об успешной разблокировке:
     * "» Скин &lt;название&gt; для &lt;пушка&gt; успешно разблокирован!", где название
     * скина покрашено в его label_color, а название пушки кликабельно-наводимо
     * (hover показывает превью самой пушки С НАДЕТЫМ СКИНОМ, см. createGunStack).
     */
    private static Component buildUnlockChatMessage(String skinId) {
        SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);

        if (lookup == null) {
            // Фолбэк на случай "битого" skinId (разблокировка в SkinAttachment уже
            // произошла выше, независимо от того, нашли мы описание скина или нет).
            return Component.translatable("message.mcpskins.skin_unlocked_fallback", skinId).withStyle(ChatFormatting.GREEN);
        }

        ItemStack previewGun = TACZSkinHelper.createGunStack(lookup.weapon().baseGun(), lookup.skin().id());
        final int labelColor = lookup.skin().labelColor();

        // Название скина - динамический контент из датапака, оформляется своим стилем
        // и передаётся как аргумент в translatable-шаблон (порядок слов/склонения
        // разные языки при этом расставляют сами, в своём lang-файле).
        Component skinName = Component.literal(lookup.skin().name())
                .withStyle(style -> style.withColor(labelColor).withBold(true));

        if (!previewGun.isEmpty()) {
            Component gunName = previewGun.getHoverName().copy().withStyle(style -> style
                    .withColor(ChatFormatting.YELLOW)
                    .withUnderlined(true)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(previewGun))));
            return Component.translatable("message.mcpskins.unlock_success_for", skinName, gunName).withStyle(ChatFormatting.GREEN);
        }

        return Component.translatable("message.mcpskins.unlock_success", skinName).withStyle(ChatFormatting.GREEN);
    }
}