package org.minechestplate.mcpskins;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.client.ClientHeldGunRefresher;
import org.minechestplate.mcpskins.skin.render.GunModelPatcher;
import org.minechestplate.mcpskins.skin.render.PatchedGunDisplayCache;
import org.minechestplate.mcpskins.skin.render.SkinAssetResolver;

import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = MCPSkins.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    /**
     * ИСПРАВЛЕНИЕ (баг "голые руки/сломанные анимации после захода с ресурспаком скинов"):
     * раньше в моде НЕ БЫЛО НИ ОДНОГО клиентского reload-листенера - {@code SkinAssetResolver}
     * кэширует "существует ли файл скина в активных ресурспаках" (см. его javadoc про
     * {@code EXISTS_CACHE}) НАВСЕГДА, пока кто-то явно не позовёт {@code clearCache()}, а
     * {@code PatchedGunDisplayCache} держит уже готовые пропатченные {@code GunDisplayInstance}
     * тоже без какой-либо связи с реальной перезагрузкой ресурсов клиента.
     * <p>
     * Оба кэша чисто клиентские (не зависят от датапак-реестра скинов, который синхронизирует
     * {@code SkinManager} через {@code AddReloadListenerEvent} - это ДРУГОЙ, серверный/датапак
     * reload, не про текстуры) - им нужен именно {@link RegisterClientReloadListenersEvent},
     * который срабатывает на КАЖДУЮ перезагрузку РЕСУРСОВ клиента (в том числе на применение
     * ресурспака, присланного сервером при подключении, и на команду /reload). Без этого сброса
     * устаревшие "файл не найден"/"файл найден" решения и старые пропатченные копии могли бы
     * пережить смену ресурспака и указывать на уже не соответствующие действительности данные.
     * <p>
     * <b>ДОБАВЛЕНО - {@link ClientHeldGunRefresher#scheduleRefresh()}:</b> по логу подтверждено,
     * что при F3+T клиент иногда на один цикл перезагрузки реально теряет из стека серверный
     * ресурспак (видно по отсутствию записи {@code server/<id>/...} в логе
     * {@code ReloadableResourceManager}), из-за чего ванильный {@code TextureManager} ловит
     * {@code FileNotFoundException} на уже когда-то забинженной текстуре скина. Наши кэши это
     * переживают правильно (сбрасываются на каждый такой цикл), но то, как рендер TACZ решает
     * (не) перебиндить саму GPU-текстуру после возврата файла - вне зоны действия
     * {@code GunDisplayInstancePatcher} (см. его javadoc, п.2 - в рендер-состояние мы
     * принципиально не лезем). Единственный подтверждённый на практике фикс - пересоздать
     * удерживаемый предмет (то же самое, что "переключить оружие в другой слот и обратно") -
     * см. подробности в javadoc {@link ClientHeldGunRefresher}. Планируем это ПОСЛЕ
     * {@code preparationBarrier.wait()} (внутри {@code thenRunAsync} ниже), то есть когда
     * перезагрузка уже полностью применилась ВСЕМИ листенерами (включая TACZ), а не в момент
     * начала подготовки - иначе рискуем словить ту же гонку с недособранными ассетами заново.
     */
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((PreparableReloadListener) (preparationBarrier, resourceManager,
                                                                 preparationsProfiler, reloadProfiler,
                                                                 backgroundExecutor, gameExecutor) ->
                CompletableFuture.runAsync(() -> {
                            SkinAssetResolver.clearCache();
                            PatchedGunDisplayCache.clear();
                            GunModelPatcher.clear();
                            MCPSkins.LOGGER.info("[MCPSkins] Клиентские ресурсы перезагружены - кэши скинов (существование текстур/моделей, пропатченные и geo-модельные GunDisplayInstance) сброшены.");
                        }, backgroundExecutor)
                        .thenCompose(preparationBarrier::wait)
                        .thenRunAsync(ClientHeldGunRefresher::scheduleRefresh, gameExecutor));
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            // tintIndex 0 соответствует "layer0" в нашем JSON (наш фон)
            if (tintIndex == 0) {
                CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);

                if (data.contains("SkinToUnlock")) {
                    String skinId = data.copyTag().getString("SkinToUnlock");

                    // Ищем цвет скина в реестре
                    for (SkinDataModels.WeaponSkins weapon : SkinManager.INSTANCE.getRegistry().values()) {
                        for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                            if (skin.id().equals(skinId)) {
                                // Маска | 0xFF000000 гарантирует, что альфа-канал будет равен FF (непрозрачный)
                                return skin.labelColor() | 0xFF000000;
                            }
                        }
                    }
                }
            }
            // Если скин не найден или это tintIndex 1 (белые линии), возвращаем непрозрачный белый
            return 0xFFFFFFFF; // Использовать 0xFFFFFFFF вместо 0xFFFFFF

        }, ModItems.SKIN_UNLOCK_ITEM.get());
    }
}