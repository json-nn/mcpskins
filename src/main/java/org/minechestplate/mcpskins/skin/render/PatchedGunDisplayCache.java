package org.minechestplate.mcpskins.skin.render;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Держит СТАБИЛЬНУЮ идентичность (object identity) пропатченного {@link GunDisplayInstance}
 * между вызовами {@code TimelessAPI.getGunDisplay(...)}, вместо того, чтобы
 * {@link GunDisplayInstancePatcher#withOverrides} создавал новый объект (через Unsafe) на
 * КАЖДЫЙ вызов, даже когда результат должен быть тем же самым.
 * <p>
 * ИСПРАВЛЕНИЕ (после того, как первая версия этого класса не помогла): ключом кэша раньше
 * служил сам {@code original} {@link GunDisplayInstance} (как {@link java.util.WeakHashMap}
 * ключ) - расчёт был на то, что TACZ хранит ОДИН объект-синглтон на GunId и переиспользует его
 * между вызовами. Мы не смогли проверить это предположение по реальному исходнику форка - а
 * если оно неверно (TACZ создаёт новый объект на каждый вызов, либо какой-то другой мод/микс
 * между вызовами подменяет его), кэш по идентичности оригинала был бесполезен: каждый вызов
 * снова видел "новый" ключ и заново гонял рефлексию.
 * <p>
 * Теперь ключ - это (baseGunId, skinId) как СТРОКА, а не идентичность Java-объекта. Это
 * НЕ зависит от того, как именно TACZ управляет временем жизни своих внутренних объектов:
 * для одной и той же пары (оружие, скин) мы ВСЕГДА возвращаем один и тот же пропатченный
 * объект, пока фактические входные данные (сам {@code original} и результат разрешения
 * текстуры/иконки) не изменятся. Если {@code original} всё-таки поменялся (TACZ пересобрал
 * его) - запись в кэше САМА заметит несовпадение (см. проверку {@code existing.original() == original}
 * ниже) и пересоздаст патч - отдельный явный сброс кэша при пересборке оригинала не требуется,
 * хотя {@link #clear()} всё равно вызывается при клиентской перезагрузке ресурсов.
 */
public final class PatchedGunDisplayCache {

    private record CacheEntry(GunDisplayInstance original, ResourceLocation texture,
                              ResourceLocation icon, GunDisplayInstance patched) {
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    // Считаем, сколько раз реально пересоздавался патч для каждого ключа - в норме это
    // должно происходить один-два раза за сессию (первое применение скина + возможные
    // /reload), а не каждый кадр. Если лог покажет частые пересоздания - значит кэш не
    // держит стабильную идентичность и проблему нужно искать дальше (см. javadoc класса).
    private static final Map<String, Integer> RECREATE_COUNTS = new ConcurrentHashMap<>();

    private PatchedGunDisplayCache() {
    }

    /**
     * @param cacheKey стабильный ключ комбинации (обычно {@code baseGunId + "\u0000" + skinId},
     *                 см. вызывающий код в TimelessAPIMixin) - НЕ зависит от идентичности
     *                 Java-объекта {@code original}, в отличие от предыдущей версии этого класса
     */
    public static GunDisplayInstance getOrCreate(String cacheKey, GunDisplayInstance original,
                                                 ResourceLocation texture, ResourceLocation icon) {
        if (original == null) return null;

        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null
                && existing.original() == original
                && Objects.equals(existing.texture(), texture)
                && Objects.equals(existing.icon(), icon)) {
            return existing.patched();
        }

        GunDisplayInstance patched = GunDisplayInstancePatcher.withOverrides(original, texture, icon);
        if (patched != null) {
            CACHE.put(cacheKey, new CacheEntry(original, texture, icon, patched));
            int count = RECREATE_COUNTS.merge(cacheKey, 1, Integer::sum);
            MCPSkins.LOGGER.info(
                    "[MCPSkins] Пересоздан пропатченный GunDisplayInstance для '{}' (раз за сессию: {}). "
                            + "Если это число быстро растёт (десятки/сотни) - кэш не держит стабильную "
                            + "идентичность, и дело не в ней, см. javadoc PatchedGunDisplayCache.",
                    cacheKey, count);
        } else {
            // original есть, но withOverrides не смог создать копию (см. его javadoc) -
            // не оставляем в кэше протухшую запись под этим ключом.
            CACHE.remove(cacheKey);
        }
        return patched;
    }

    /**
     * Полностью сбрасывает кэш пропатченных инстансов. Вызывается при клиентской перезагрузке
     * ресурсов (см. регистрацию в {@code ClientModEvents}).
     */
    public static void clear() {
        CACHE.clear();
        RECREATE_COUNTS.clear();
    }
}