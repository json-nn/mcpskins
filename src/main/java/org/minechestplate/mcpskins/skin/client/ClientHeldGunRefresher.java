package org.minechestplate.mcpskins.skin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ИСПРАВЛЕНИЕ (остаточный баг "голые руки", который переживает и чистку кэшей, и
 * нефорсирующий {@code GunDisplayInstancePatcher.getTexture()} - см. лог с F3+T).
 * <p>
 * По логу подтверждено: иногда F3+T на клиенте на ОДИН цикл перезагрузки ресурсов реально
 * убирает серверный ресурспак (тот, где физически лежат PNG скинов) из стека, и тут же сам
 * применяет его заново на следующем цикле (под новым id пака). {@code SkinAssetResolver} и
 * {@code PatchedGunDisplayCache} на это реагируют правильно - оба чистятся на каждой такой
 * перезагрузке (см. {@code ClientModEvents#registerReloadListeners}), так что НАШИ
 * ResourceLocation-ссылки после возврата пака снова корректны.
 * <p>
 * Но ЭТОГО НЕДОСТАТОЧНО: ванильный {@code TextureManager} в момент, когда файла не было,
 * успевает залогировать {@code FileNotFoundException} для уже когда-то забинженной текстуры
 * скина - и то, как рендер TACZ после этого решает (или не решает) перебиндить GPU-текстуру
 * заново, когда файл появляется снова, целиком находится на стороне рендер-кода TACZ, куда
 * рефлексией из {@code GunDisplayInstancePatcher} мы принципиально не лезем (см. его javadoc,
 * п.2 - геометрию/рендер-состояние этот мод не трогает никогда). Единственный НАДЁЖНО
 * подтверждённый на практике способ починить именно это состояние - тот, что описал автор
 * мода: переключить оружие в другой слот и обратно. Технически это заставляет TACZ полностью
 * пересобрать своё рендер-состояние для этого предмета с нуля.
 * <p>
 * Этот класс автоматизирует ровно тот же приём, без участия игрока: спустя
 * {@link #DELAY_TICKS} тиков после ЛЮБОЙ клиентской перезагрузки ресурсов (регистрируется в
 * {@code ClientModEvents#registerReloadListeners}, вызывается уже ПОСЛЕ
 * {@code preparationBarrier.wait()} - то есть когда реально применились ВСЕ листенеры,
 * включая собственные листенеры TACZ, а не только наши) заменяет удерживаемый предмет в
 * обеих руках на {@code stack.copy()} - тот же самый приём (новый Java-объект с тем же
 * содержимым), который уже используется в этом моде для клиентского предпросмотра скина -
 * см. {@code TACZRefitSkinOverlay#previewLockedSkin}. Задержка нужна, чтобы не дёргать
 * пересоздание ДО того, как TACZ сам успеет пересобрать свои внутренние менеджеры ассетов
 * после перезагрузки - иначе можно словить ту же самую гонку заново.
 * <p>
 * Если баг всё равно изредка проявляется - увеличьте {@link #DELAY_TICKS}: значит, на вашей
 * машине/сервере TACZ пересобирает ассеты дольше отведённого запаса.
 */
public final class ClientHeldGunRefresher {

    private static final int DELAY_TICKS = 10;

    /** -1 = ничего не запланировано, иначе - оставшееся число тиков до пересоздания. */
    private static final AtomicInteger pendingTicks = new AtomicInteger(-1);

    private ClientHeldGunRefresher() {
    }

    /**
     * Вызывается из {@code ClientModEvents#registerReloadListeners} ПОСЛЕ
     * {@code preparationBarrier.wait()} - см. javadoc класса про то, почему важен именно
     * этот момент, а не начало перезагрузки.
     */
    public static void scheduleRefresh() {
        pendingTicks.set(DELAY_TICKS);
    }

    @EventBusSubscriber(modid = MCPSkins.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class TickHandler {

        private TickHandler() {
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            int remaining = pendingTicks.get();
            if (remaining < 0) return;
            if (remaining > 0) {
                pendingTicks.set(remaining - 1);
                return;
            }
            pendingTicks.set(-1);
            refreshHeldGuns();
        }
    }

    private static void refreshHeldGuns() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        refreshHand(player, InteractionHand.MAIN_HAND);
        refreshHand(player, InteractionHand.OFF_HAND);
    }

    private static void refreshHand(LocalPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) return;
        // Любое оружие TACZ - не только со скином: сам баг (см. лог) бьёт по рендеру
        // независимо от того, надет ли на пушке скин, поэтому не фильтруем по SKIN_ID.
        if (TACZSkinHelper.getGunId(stack) == null) return;

        // Новый Java-объект с тем же содержимым - принципиально важно именно stack.copy(),
        // а не мутация существующего: смена ИДЕНТИЧНОСТИ, а не данных, и есть то, что чинит
        // рендер (см. javadoc класса).
        player.setItemInHand(hand, stack.copy());
    }
}
