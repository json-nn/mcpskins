package org.minechestplate.mcpskins.skin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Проверяет, существует ли переопределяющий (skin) файл текстуры/модели в активных
 * ресурспаках, и решает, какой ResourceLocation в итоге использовать - переопределение
 * скина, если файл реально есть, или базовый (ванильный для GunId) ресурс как фолбэк.
 *
 * Резолвинг текстуры/иконки, как и раньше, - полностью ванильный код без зависимостей от
 * TACZ. Резолвинг geo-модели ({@link #resolveModel}) ТЕПЕРЬ ТОЖЕ полностью ванильный - он
 * больше не угадывает namespace/папку сам, а принимает готовый {@code baseModelLocation}
 * (его добывает {@link GunModelPatcher#getBaseModelLocation} рефлексией у самого TACZ) - см.
 * подробный javadoc {@link #resolveModel} про то, почему угадывание namespace не сработало.
 *
 * ВАЖНО про кэш: этот резолвер дёргается из миксина потенциально на каждый релевантный
 * вызов {@code TimelessAPI.getGunDisplay(...)}, то есть многократно за секунду, а
 * {@code ResourceManager#getResource} - это не бесплатная операция (поиск по всем
 * активным пакам). Поэтому результат существования файла кэшируется в памяти по ключу
 * ResourceLocation. Кэш переживает игровую сессию; если вы динамически подкладываете
 * новые скины без перезапуска игры (например, через datapack-like систему), вызывайте
 * {@link #clearCache()} после такого добавления - иначе новый файл не будет замечен,
 * пока не случится следующий /reload или перезаход в мир.
 *
 * <p><b>ИСПРАВЛЕНИЕ (краш с чужими ганпаками, например create_armorer):</b> {@code GunId}
 * оружия из СВОЕГО ганпака TACZ обычно приходит "голым" (без namespace, например
 * {@code "m4a1"}), но {@code GunId} оружия из ЧУЖОГО ганпака (любого другого мода/пака)
 * приходит уже как полноценный {@code "namespace:path"}, например
 * {@code "create_armorer:pistol_auto_stress"}. Раньше этот "сырой" GunId (со своим
 * двоеточием) склеивался прямо в PATH другого ResourceLocation
 * ({@code "textures/skins/" + baseGunId + "/" + skinId + ".png"}), а двоеточие - НЕ
 * валидный символ внутри path (валиден только один раз, как разделитель namespace:path
 * самого идентификатора) -&gt; {@code ResourceLocationException} и краш рендер-потока на
 * каждый кадр, пока оружие с чужим ганпаком было в руке или видно в карусели скинов.
 * <p>
 * Точно так же ломался и {@code skinId}, если он сам был "namespace:path" (например
 * {@code "create_armorer_skins:blossom/pistol_auto_stress_blossom"}) - что как раз и есть
 * НУЖНЫЙ формат для скина, который лежит в ЧУЖОМ namespace ресурспака (не в {@code modId}
 * этого мода), то есть именно то, что требуется, чтобы "любой скин из любого установленного
 * серверного ресурспака" можно было применить, независимо от того, из какого он ганпака
 * или мода.
 * <p>
 * Починено в два шага:
 * <ol>
 *     <li>{@code baseGunId} склеивается в path-часть, но ЛЮБОЕ двоеточие в нём сначала
 *     заменяется на "/" - тогда путь остаётся валидным, а чужой ганпак вдобавок получает
 *     свою собственную подпапку (что заодно исключает коллизии имён между двумя разными
 *     ганпаками с одинаковым "коротким" id оружия).</li>
 *     <li>{@code skinId} перед склейкой разбирается на namespace:path САМ. Если в нём есть
 *     ":", то, что до двоеточия, становится namespace итогового ResourceLocation (то есть
 *     PNG будет искаться в РЕСУРСПАКЕ С ЭТИМ NAMESPACE, а не в {@code modId} этого мода) -
 *     это и есть поддержка "скины из любого ресурспака/namespace". Если двоеточия нет -
 *     поведение не изменилось: namespace = {@code modId} (обратная совместимость с уже
 *     существующими простыми id вроде {@code "cobra"}).</li>
 * </ol>
 * Итоговый ResourceLocation после этого всегда собирается через
 * {@link ResourceLocation#tryBuild(String, String)}, а НЕ {@code fromNamespaceAndPath}/
 * {@code parse} - в отличие от них {@code tryBuild} никогда не бросает исключение, а просто
 * возвращает {@code null}, если namespace или path содержат хоть один недопустимый символ.
 * Это страховка "на будущее": даже если где-то заведётся скин/GunId с совсем неожиданными
 * символами (опечатка в JSON, кривой id из стороннего ресурспака и т.п.), результат - тихий
 * фолбэк на ванильную текстуру, а НЕ краш игры, как было раньше.
 */
public final class SkinAssetResolver {
    private static final Map<String, Boolean> EXISTS_CACHE = new ConcurrentHashMap<>();

    // Чтобы не заспамить лог одним и тем же предупреждением каждый кадр, пока на экране
    // висит невалидный skin_id/GunId - предупреждаем один раз на уникальную комбинацию.
    private static final Set<String> WARNED_INVALID = ConcurrentHashMap.newKeySet();

    private SkinAssetResolver() {
    }

    /**
     * @param modId      неймспейс ПО УМОЛЧАНИЮ, в котором лежат файлы скинов (обычно ваш
     *                   MOD_ID) - используется, только если {@code skinId} сам не задаёт
     *                   свой namespace (см. javadoc класса)
     * @param baseGunId  "сырой" GunId оружия (БЕЗ префикса "default:", как возвращает
     *                   {@code TACZSkinHelper.getGunId(stack)}) - может как НЕ содержать
     *                   двоеточие (оружие "родного" ганпака), так и содержать его (оружие
     *                   стороннего ганпака, например {@code "create_armorer:pistol_auto_stress"})
     * @param skinId     значение компонента mcpskins:skin_id, например "cobra", либо
     *                   "namespace:path" (например
     *                   "create_armorer_skins:blossom/pistol_auto_stress_blossom"), если
     *                   скин лежит в стороннем ресурспаке под своим namespace
     * @param fallback   ResourceLocation текстуры базового (неперекрашенного) оружия,
     *                   которую нужно вернуть, если файла скина не существует (или если
     *                   итоговый id оказался невалидным - см. javadoc класса)
     */
    public static ResourceLocation resolveTexture(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s.png", fallback);
    }

    /**
     * <b>ПЕРЕДЕЛАНО ПОЛНОСТЬЮ (после диагностики в реальном логе):</b> ни фиксированный
     * {@code GunMod.MOD_ID} (первая попытка), ни namespace скина - неверные догадки. Реальная
     * диагностика {@link GunModelPatcher#discover} на живом оружии из стороннего ганпака
     * показала: {@code 'create_armorer:gun/cannon_40mm_salamander_geo'} - то есть у оружия из
     * ЧУЖОГО ганпака (create_armorer) настоящая geo-модель лежит под namespace САМОГО ганпака
     * ({@code create_armorer}), а НЕ под {@code tacz}. Более того, путь в этом namespace -
     * {@code "gun/cannon_40mm_salamander_geo"} - вообще БЕЗ префикса папки
     * {@code "geo_models/"} и БЕЗ расширения {@code ".json"}: это "свёрнутая" форма ID,
     * которую использует {@code FileToIdConverter} внутри {@code ClientAssetsManager} (папка
     * сканирования и расширение файла - это ПРЕФИКС/СУФФИКС, которые он сам обрезает при
     * построении ID; см. регистрацию в реальном {@code ClientAssetsManager.java}:
     * {@code new LazyJsonDataManager<>(BedrockModelPOJO.class, GSON, "geo_models", ...)}).
     * <p>
     * Отсюда следует, что у одного и того же файла ФАКТИЧЕСКИ ДВЕ разные формы пути,
     * используемые для двух разных целей:
     * <ul>
     *     <li><b>физический путь</b> ({@code assets/<namespace>/geo_models/<sub>.json}) -
     *     нужен для проверки "существует ли файл" через ванильный
     *     {@code Minecraft.getResourceManager()} (см. {@link #exists}), который ничего не
     *     знает про TACZ и работает с файлами как есть;</li>
     *     <li><b>"свёрнутая" форма</b> ({@code namespace:<sub>}, БЕЗ {@code "geo_models/"} и
     *     БЕЗ {@code ".json"}) - именно её хранит поле модели {@code GunDisplay} (см.
     *     {@code getModelLocation()}) и именно её ожидает
     *     {@code ClientAssetsManager.getBedrockModelPOJO(...)} - это и есть то значение,
     *     которое в итоге должно стать {@code modelOverride} для
     *     {@link GunModelPatcher#getOrCreate}.</li>
     * </ul>
     * Раньше сюда (в {@code ClientAssetsManager}) передавался ПЕРВЫЙ (физический) путь -
     * отсюда и "физически есть, но ClientAssetsManager про него не знает", НЕЗАВИСИМО от того,
     * какой namespace подставляли.
     * <p>
     * <b>Как теперь строится итоговый путь - БЕЗ угадывания namespace/папки:</b> вместо
     * жёсткого формата (константы вроде старого {@code MODEL_PATH_FORMAT}) метод принимает
     * {@code baseModelLocation} - РЕАЛЬНЫЙ ResourceLocation модели ИМЕННО ТОГО оружия, которое
     * сейчас патчим (см. {@link GunModelPatcher#getBaseModelLocation}, читает то же самое
     * поле, которое диагностика печатает в лог). Namespace и папка берутся ИЗ НЕГО (то есть
     * автоматически совпадают с конвенцией конкретного ганпака - будь то {@code tacz},
     * {@code create_armorer} или любой другой, ещё не виденный), а к имени файла просто
     * добавляется уникальный суффикс с ID скина, чтобы не столкнуться с самим оригиналом или
     * с другими скинами этого же оружия.
     * <p>
     * Например, для {@code create_armorer:gun/cannon_40mm_salamander_geo} и скина
     * {@code "cannon_40mm_salamander_galaxy"} итоговая "свёрнутая" форма будет
     * {@code create_armorer:gun/cannon_40mm_salamander_geo__skin_cannon_40mm_salamander_galaxy},
     * а физически в ресурспаке файл должен лежать по пути
     * {@code assets/create_armorer/geo_models/gun/cannon_40mm_salamander_geo__skin_cannon_40mm_salamander_galaxy.json}.
     * <p>
     * Если у скина есть только текстура (такого файла нет ни в одном активном ресурспаке) -
     * метод вернёт {@code null}, то есть модель останется от базового оружия, а перекрасится
     * только текстура. Именно так реализуется требование "скин может менять только текстуру,
     * либо и текстуру, и модель".
     *
     * @param baseModelLocation РЕАЛЬНЫЙ ResourceLocation модели базового (непатченного) оружия -
     *                          см. {@link GunModelPatcher#getBaseModelLocation}. Если
     *                          {@code null} (рефлексия не разведала нужные поля -
     *                          неподдерживаемая версия форка), метод сразу возвращает
     *                          {@code null}.
     * @param skinId            значение компонента mcpskins:skin_id, например
     *                          "cannon_40mm_salamander_galaxy"
     * @return "свёрнутый" ResourceLocation geo-модели скина (готов к передаче в
     *         {@link GunModelPatcher#getOrCreate}), либо {@code null}, если подходящего файла
     *         нет ни в одном активном ресурспаке (скин остаётся чисто текстурным)
     */
    public static ResourceLocation resolveModel(ResourceLocation baseModelLocation, String skinId) {
        if (baseModelLocation == null || skinId == null || skinId.isBlank()) return null;

        String namespace = baseModelLocation.getNamespace();
        String basePath = baseModelLocation.getPath(); // "свёрнутая" форма, напр. "gun/cannon_40mm_salamander_geo"

        int lastSlash = basePath.lastIndexOf('/');
        String dir = lastSlash >= 0 ? basePath.substring(0, lastSlash + 1) : "";
        String baseFileName = lastSlash >= 0 ? basePath.substring(lastSlash + 1) : basePath;

        // skinId сплющиваем в безопасный кусок имени файла - двоеточие/слэши здесь недопустимы
        // (это не namespace:path, а просто уникальный суффикс имени файла в ТОЙ ЖЕ папке, что
        // и оригинал, см. javadoc выше про то, почему namespace/папка больше не берутся из
        // skinId).
        String sanitizedSkinId = skinId.replace(':', '_').replace('/', '_');
        String skinSubPath = dir + baseFileName + "__skin_" + sanitizedSkinId; // "свёрнутая" форма

        // Физический путь - ОТДЕЛЬНО, с префиксом папки "geo_models/" и суффиксом ".json" - см.
        // javadoc метода про то, почему это ДВЕ разные формы одного и того же пути.
        ResourceLocation physical = ResourceLocation.tryBuild(namespace, "geo_models/" + skinSubPath + ".json");
        if (physical == null) {
            String debugId = namespace + ":geo_models/" + skinSubPath + ".json";
            if (WARNED_INVALID.add(debugId)) {
                MCPSkins.LOGGER.warn(
                        "Skin id '{}' для модели '{}' даёт некорректный путь geo-модели после "
                                + "сборки ('{}') - geo-модель игнорируется, оружие остаётся с "
                                + "базовой геометрией вместо краша.",
                        skinId, baseModelLocation, debugId);
            }
            return null;
        }
        if (!exists(physical)) return null;

        // "Свёрнутая" форма (без "geo_models/" и без ".json") - именно её ожидает
        // GunDisplay.model и ClientAssetsManager.getBedrockModelPOJO(...), см. javadoc метода.
        return ResourceLocation.tryBuild(namespace, skinSubPath);
    }

    /**
     * НОВОЕ (опциональная фича "своя иконка скина"): то же самое, но для ПЛОСКОЙ 2D-иконки
     * предмета в инвентаре/слотах.
     * <p>
     * У TACZ иконка в инвентаре - это ОТДЕЛЬНОЕ изображение (в терминологии ганпаков TACZ -
     * поле {@code "slot"} в {@code guns/display/<gun>_display.json}, физически лежащее в
     * гансборке по пути вида {@code textures/gun/slot/<gun>.png}), совершенно независимое от
     * {@code "texture"} (UV-развёртки 3D-модели, которую перекрашивает {@link #resolveTexture}).
     * Перекраска одной только UV-развёртки НИКАК не меняет иконку в инвентаре - оружие в руке
     * будет выглядеть перекрашенным, а в инвентаре останется исходная иконка ганпака.
     * <p>
     * Если у скина рядом с текстурой (см. {@link #resolveTexture}) лежит файл
     * {@code <skinId>_icon.png} - используем его как иконку скина. Если файла нет - вернётся
     * fallback, то есть иконка в инвентаре останется от базового оружия, а перекрасится
     * только 3D-модель, как и раньше. Именно так реализуется требование "фича опциональна":
     * ничего не ломается и не требует правок в датапаке для скинов, у которых нет своей
     * иконки, - достаточно ПРОСТО НЕ КЛАСТЬ файл {@code _icon.png} в ресурспак.
     * <p>
     * Применение переопределения на стороне рендера - см.
     * {@code GunDisplayInstancePatcher#withOverrides} и javadoc про поиск поля рефлексией
     * (в отличие от {@code modelTexture}, точное имя приватного поля 2D-иконки в
     * {@code GunDisplayInstance} для вашего конкретного форка TACZ НЕ было верифицировано по
     * исходнику - см. подробности там же).
     */
    public static ResourceLocation resolveIcon(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_icon.png", fallback);
    }

    private static ResourceLocation resolve(String defaultModId, String baseGunId, String skinId, String pathFormat, ResourceLocation fallback) {
        if (defaultModId == null || baseGunId == null || skinId == null || skinId.isBlank()) return fallback;

        // skinId может сам задавать namespace ("<namespace>:<path>") - тогда ищем именно в
        // этом namespace (чужой ресурспак/мод), а не в своём modId. Без двоеточия - как и
        // раньше, namespace = modId этого мода.
        String skinNamespace = defaultModId;
        String skinPath = skinId;
        int colon = skinId.indexOf(':');
        if (colon >= 0) {
            skinNamespace = skinId.substring(0, colon);
            skinPath = skinId.substring(colon + 1);
        }

        // baseGunId сам может быть "namespace:path" (оружие чужого ганпака) - двоеточие
        // недопустимо внутри path, поэтому превращаем его в "/" (заодно даёт чужому
        // ганпаку отдельную подпапку и убирает риск коллизии имён).
        String sanitizedGunId = baseGunId.replace(':', '/');

        String path = String.format(pathFormat, sanitizedGunId, skinPath);

        // tryBuild (в отличие от fromNamespaceAndPath/parse) не кидает исключение на
        // невалидные символы, а возвращает null - именно это предотвращает краш рендера,
        // если после всех преобразований выше в namespace или path всё равно останется
        // что-то недопустимое (опечатка в id, лишнее двоеточие и т.п.). Передаём namespace
        // и path РАЗДЕЛЬНО (а не склеенной строкой + tryParse), чтобы двоеточие, случайно
        // оставшееся внутри path, однозначно считалось невалидным символом path, а не могло
        // быть по ошибке переинтерпретировано как ещё один разделитель namespace:path.
        ResourceLocation candidate = ResourceLocation.tryBuild(skinNamespace, path);
        if (candidate == null) {
            String debugId = skinNamespace + ":" + path;
            if (WARNED_INVALID.add(debugId)) {
                MCPSkins.LOGGER.warn(
                        "Skin id '{}' для оружия '{}' даёт некорректный ResourceLocation ('{}') - "
                                + "скин игнорируется, используется базовая текстура оружия вместо краша.",
                        skinId, baseGunId, debugId);
            }
            return fallback;
        }

        return exists(candidate) ? candidate : fallback;
    }

    private static boolean exists(ResourceLocation location) {
        String key = location.toString();
        Boolean cached = EXISTS_CACHE.get(key);
        if (cached != null) return cached;
        boolean found = Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
        EXISTS_CACHE.put(key, found);
        return found;
    }

    /** Сбросить кэш проверок существования файлов (см. javadoc класса). */
    public static void clearCache() {
        EXISTS_CACHE.clear();
        WARNED_INVALID.clear();
    }
}