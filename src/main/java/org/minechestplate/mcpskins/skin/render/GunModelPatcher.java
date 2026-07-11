package org.minechestplate.mcpskins.skin.render;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Полная замена geo-модели скина (не только перекраска текстуры, см. {@link GunDisplayInstancePatcher}) -
 * путь до geo.json скина строит {@link SkinAssetResolver#resolveModel} на основе РЕАЛЬНОГО
 * пути модели конкретного оружия (см. {@link #getBaseModelLocation}, добавлено после того, как
 * выяснилось, что namespace/папка у разных ганпаков разные и их нельзя угадать заранее) - и
 * если файл с таким именем найден в активных ресурспаках, эта модель подменяет геометрию оружия.
 *
 * <p><b>ПОЧЕМУ ЭТО НЕЛЬЗЯ СДЕЛАТЬ ТАК ЖЕ ПРОСТО, КАК ТЕКСТУРУ:</b> см. подробный javadoc
 * {@link GunDisplayInstancePatcher} (пункт 2, "MODEL_FIELD_NAME") - {@code modelTexture} это
 * просто {@code ResourceLocation}, который можно перезаписать одним {@code Field#set}. Геометрия
 * же после загрузки хранится НЕ как {@code ResourceLocation}, а как уже полностью разобранный
 * {@code BedrockGunModel} (результат работы приватных {@code ClientAssetsManager} и
 * {@code GunModelTypeManager}, которые парсят geo.json, собирают кости/меши/анимации). Просто
 * записать в это поле "путь до другого файла" нельзя - там уже не путь, а готовый объект.
 * Реализовать разбор geo.json заново, вручную повторяя приватный пайплайн TACZ (тот путь, о
 * котором предупреждает javadoc {@link GunDisplayInstancePatcher}) - тот самый способ получить
 * "плачевный опыт TAC": хрупко, версионно-зависимо, и один неверный байт в ручном парсинге
 * молча портит модель без единой ошибки в логе.
 *
 * <p><b>КАК СДЕЛАНО ЗДЕСЬ ВМЕСТО ЭТОГО:</b> вместо того, чтобы самим разбирать geo.json, мы
 * заставляем ЭТО СДЕЛАТЬ САМУ TACZ - её настоящим кодом, без единой ручной попытки повторить
 * парсинг. У {@code GunDisplayInstance} есть ровно один конструктор (пакетный, НЕ приватный) с
 * сигнатурой {@code (ResourceLocation, GunDisplay)} - это уже было проверено раньше по
 * декомпилированному jar'у форка (см. javadoc {@link GunDisplayInstancePatcher}, "Единственный
 * конструктор пакетный"). {@code GunDisplay} - это простой POJO-конфиг (то, во что уже
 * десериализован display.json), а НЕ сама лениво загружаемая модель - подменить в НЁМ путь до
 * geo.json совершенно безопасно, это обычное поле обычного объекта данных. Дальше просто вызываем
 * этот же конструктор с копией конфига оригинального оружия, в которой подменено только поле
 * модели - и TACZ САМА, своим же кодом, при первом обращении (лениво, как обычно) распарсит НАШ
 * geo.json через тот же самый {@code ClientAssetsManager}/{@code GunModelTypeManager}, которым бы
 * пользовалась для любого настоящего оружия. Мы ни строчки этого пайплайна не повторяем -
 * буквально просим TACZ переиспользовать его для другого файла.
 *
 * <p><b>КАК НАЙДЕНЫ НУЖНЫЕ ПОЛЯ/МЕТОДЫ (важно, если это когда-то перестанет работать):</b>
 * ничего не захардкожено по имени, кроме того, что уже проверено ({@code getModelLocation()} -
 * имя метода тоже уже подтверждено раньше, см. javadoc {@link GunDisplayInstancePatcher},
 * "checkTextureAndModel() читает display.getModelLocation()"). Всё остальное ищется САМО:
 * <ul>
 *     <li>Класс {@code GunDisplay} берём прямо из сигнатуры конструктора (второй параметр) -
 *     нам не нужно знать его полное имя/пакет заранее.</li>
 *     <li>Поле на {@code GunDisplayInstance}, которое хранит ссылку на этот конфиг, ищем ПО
 *     ТИПУ поля (единственное поле такого типа) - не зависит от имени поля.</li>
 *     <li>Поле на {@code GunDisplay}, отвечающее за модель, ищем ПО ЗНАЧЕНИЮ: вызываем
 *     {@code getModelLocation()} у оригинального (заведомо рабочего) конфига и ищем среди полей
 *     типа {@code ResourceLocation} то, чьё значение равно возвращённому - опять же не зависит от
 *     имени поля, только от того, что геттер действительно его возвращает.</li>
 * </ul>
 * Если хоть один из этих шагов не удался (другая версия форка, поле переименовали в другой ТИП
 * и т.п.) - {@link #getOrCreate} на этот и все последующие вызовы просто возвращает {@code null}
 * (без повторных попыток и без спама в лог, см. {@link #warnUnsupportedOnce}), а миксин
 * ({@code TimelessAPIMixin}) в этом случае оставляет геометрию оружия базовой - перекраска
 * текстуры/иконки скина при этом продолжает работать как обычно, эта фича от неё независима.
 *
 * <p><b>ПОЧЕМУ ЭТО НЕ ЛОМАЕТ АНИМАЦИИ/СОВМЕСТИМОСТЬ:</b> копия конфига берётся у ОРИГИНАЛЬНОГО
 * (заведомо рабочего) оружия и меняется РОВНО в одном поле - модель. Всё остальное (набор
 * анимаций, звуки, иконка и т.д.) остаётся от базового оружия без изменений. Единственное жёсткое
 * требование к geo-модели скина - её скелет (имена костей) должен соответствовать тому, что
 * ожидают анимации базового оружия, иначе конструктор {@code GunDisplayInstance} сам обнаружит
 * несовпадение и бросит исключение при валидации (см. {@code checkAnimation()} - на это ЕСТЬ
 * подтверждённый в issue-трекере TACZ пример) - весь вызов обёрнут в try/catch, так что это
 * приведёт не к краху игры, а просто к тихому отказу от подмены модели именно для этого скина
 * (см. {@link #createInstance}).
 */
public final class GunModelPatcher {

    private record CacheEntry(GunDisplayInstance base, ResourceLocation modelOverride, GunDisplayInstance result) {
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    // -1 = ещё не проверяли, 0 = разведка не удалась (фича недоступна на этой версии форка),
    // 1 = разведка прошла успешно. Проверяем и кэшируем результат один раз за сессию игры -
    // структура классов TACZ не меняется, пока не перезапустят игру с другой версией мода.
    private static volatile int supportState = -1;
    private static volatile boolean unsupportedWarningLogged = false;

    private static volatile Constructor<?> displayInstanceConstructor;
    private static volatile Class<?> gunDisplayClass;
    private static volatile Field configField;        // GunDisplayInstance -> GunDisplay
    private static volatile Field modelLocationField; // GunDisplay -> ResourceLocation (модель)

    /**
     * <b>ДИАГНОСТИКА/ПРЕДПРОВЕРКА (добавлено после краша "there is no corresponding model
     * file" / IllegalArgumentException в checkTextureAndModel()):</b> прямой (через рефлексию,
     * но БЕЗ Unsafe - это самый обычный вызов метода) доступ к тому же самому объекту и методу,
     * которые падают внутри {@code GunDisplayInstance.checkTextureAndModel()} -
     * {@code com.tacz.guns.client.resource.ClientAssetsManager.INSTANCE.getBedrockModelPOJO
     * (ResourceLocation)}. Имя класса/метода взято НЕ гаданием, а буквально из предоставленного
     * декомпилированного исходника {@code GunDisplayInstance.java} (строки вокруг
     * checkTextureAndModel(), см. трассировку краша) - {@code ClientAssetsManager} лежит в ТОМ
     * ЖЕ пакете {@code com.tacz.guns.client.resource}, что и {@code GunDisplayInstance} (отсюда
     * там нет отдельного import'а на него).
     * <p>
     * Смысл: ПЕРЕД тем как вообще пытаться собрать geo-инстанс через Unsafe/конструктор (дорогая
     * и необратимая операция, ошибка в которой раньше обнаруживалась только на следующем кадре,
     * глубоко внутри TACZ, через перехваченное исключение), мы напрямую спрашиваем у TACZ тем же
     * методом, которым он сам пользуется: "знаешь ли ты вообще про файл с таким
     * ResourceLocation?". Если нет - причина ПОЧТИ ГАРАНТИРОВАННО в том, что
     * {@code ClientAssetsManager} не является универсальным "загрузчиком по любому пути" (в
     * отличие от {@code Minecraft.getInstance().getResourceManager()}, которым пользуется
     * {@link SkinAssetResolver} для текстур) - это приватный, заранее построенный при
     * перезагрузке ресурсов реестр, который знает только о моделях, попавших в него по
     * СОБСТВЕННОЙ логике сканирования TACZ (конкретная папка/конвенция именования зависит от
     * версии форка). Физическое наличие файла в ресурспаке (что можно проверить через
     * {@code Minecraft.getResourceManager().getResource(...)}, как и делает
     * {@link SkinAssetResolver# exists}) в этом случае НИЧЕГО не говорит о том, найдёт ли его
     * именно {@code ClientAssetsManager} - это два независимых механизма.
     * <p>
     * Если реестр НЕ разрешил рефлексией (другая версия форка переименовала класс/метод) -
     * ведём себя как раньше (не блокируем попытку, {@link #isModelRecognized} возвращает
     * {@code true}) - в конце концов TACZ и без этой предпроверки сам корректно обработает
     * ошибку через свой try/catch в {@code ensureModelLoaded()}, просто без нашего
     * заблаговременного и куда более информативного лога.
     */
    private static volatile Object clientAssetsManagerInstance;
    private static volatile Method getBedrockModelPOJOMethod;
    private static volatile boolean assetsManagerProbeDone = false;
    private static final Set<String> WARNED_UNRECOGNIZED = ConcurrentHashMap.newKeySet();

    private GunModelPatcher() {
    }

    /**
     * @param cacheKey     стабильный ключ (обычно {@code baseGunId + "\u0000" + skinId}, как и в
     *                     {@link PatchedGunDisplayCache}) - НЕ зависит от идентичности Java-объекта
     * @param base         "чистый" (без скина) {@link GunDisplayInstance} базового оружия -
     *                     источник шаблона конфига, который мы минимально модифицируем
     * @param modelOverride "свёрнутый" ResourceLocation geo.json скина - см.
     *                     {@link SkinAssetResolver#resolveModel}, который теперь строит его на
     *                     основе {@link #getBaseModelLocation} (namespace/папка берутся у
     *                     настоящей модели ИМЕННО этого {@code base}, а не угадываются)
     * @return новый, полноценно собранный самой TACZ {@link GunDisplayInstance} с геометрией
     *         скина, либо {@code null}, если фича недоступна на этой версии форка или сборка
     *         конкретно для этой пары (оружие, скин) не удалась (несовместимый скелет и т.п.) -
     *         в обоих случаях вызывающий код (миксин) должен просто оставить базовую геометрию
     */
    public static GunDisplayInstance getOrCreate(String cacheKey, GunDisplayInstance base, ResourceLocation modelOverride) {
        if (base == null || modelOverride == null) return null;
        if (!ensureSupported(base)) return null;

        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null && existing.base() == base && modelOverride.equals(existing.modelOverride())) {
            return existing.result();
        }

        // ПРЕДПРОВЕРКА (см. javadoc про clientAssetsManagerInstance/getBedrockModelPOJOMethod):
        // спрашиваем у ClientAssetsManager напрямую, ДО дорогой Unsafe-сборки, знает ли он
        // вообще про этот ResourceLocation. Раньше несовпадение обнаруживалось только через
        // кадр, глубоко внутри TACZ (перехваченное IllegalArgumentException в
        // ensureModelLoaded() -> "Failed to load gun model ..."), без какой-либо подсказки о
        // причине. Физическое существование файла (SkinAssetResolver.resolveModel уже это
        // проверило) НЕ гарантирует, что ClientAssetsManager его нашёл - это два разных
        // механизма, см. javadoc поля.
        if (!isModelRecognized(modelOverride)) {
            if (WARNED_UNRECOGNIZED.add(cacheKey)) {
                MCPSkins.LOGGER.warn(
                        "[MCPSkins] Файл geo-модели скина '{}' физически есть в ресурспаке, но "
                                + "ClientAssetsManager (внутренний реестр моделей TACZ) про него НЕ "
                                + "знает - скорее всего, не совпадает ожидаемая папка/конвенция "
                                + "именования. Смотрите строку '[MCPSkins][diag]' в логе (печатается "
                                + "при первой попытке применить geo-скин) - там реальный путь модели "
                                + "у уже работающего оружия для сравнения. Скин останется с базовой "
                                + "геометрией, краша не будет.",
                        modelOverride);
            }
            CACHE.put(cacheKey, new CacheEntry(base, modelOverride, null));
            return null;
        }

        GunDisplayInstance created = createInstance(base, modelOverride);
        CACHE.put(cacheKey, new CacheEntry(base, modelOverride, created));
        if (created != null) {
            MCPSkins.LOGGER.info("[MCPSkins] Собрана geo-модель скина для '{}' (модель: {}).", cacheKey, modelOverride);
        }
        return created;
    }

    /** Сбросить кэш собранных geo-инстансов. Вызывается при клиентской перезагрузке ресурсов. */
    public static void clear() {
        CACHE.clear();
        WARNED_UNRECOGNIZED.clear();
    }

    /**
     * <b>ДОБАВЛЕНО:</b> отдаёт РЕАЛЬНЫЙ {@code ResourceLocation} модели, который сейчас лежит
     * в конфиге {@code base} - буквально то же самое значение, которое печатает диагностика в
     * {@link #discover} (поле {@code modelLocationField}, уже разведанное рефлексией к этому
     * моменту), и то же самое значение, которое {@code checkTextureAndModel()} внутри TACZ
     * передаёт в {@code ClientAssetsManager.getBedrockModelPOJO(...)}.
     * <p>
     * <b>Зачем это нужно:</b> раньше {@code SkinAssetResolver.resolveModel} САМ придумывал
     * namespace/папку для geo-модели скина (сначала - namespace скина, потом - жёстко
     * {@code GunMod.MOD_ID}) - обе попытки оказались неверны на практике: у оружия из чужого
     * ганпака (например, {@code create_armorer:cannon_40mm_salamander}) реальная модель
     * лежит под namespace САМОГО ганпака ({@code create_armorer:gun/cannon_40mm_salamander_geo}),
     * а не под {@code tacz} и не под namespace скина. Гадать больше не нужно - у КОНКРЕТНОГО
     * оружия, которое сейчас патчим, УЖЕ есть его собственный настоящий {@code base}, и у него
     * уже есть настоящий, рабочий {@code modelLocationField} - его и нужно читать напрямую,
     * а не угадывать по шаблону. {@link SkinAssetResolver#resolveModel} теперь берёт
     * namespace и папку ИЗ ЭТОГО значения и только подставляет своё имя файла - тем самым
     * автоматически совпадает с конвенцией любого ганпака, включая ещё не виденные.
     * <p>
     * Возвращает {@code null}, если рефлексия не разведала нужные поля (неподдерживаемая
     * версия форка - см. {@link #ensureSupported}) или у {@code base} по какой-то причине нет
     * конфига/модели.
     */
    public static ResourceLocation getBaseModelLocation(GunDisplayInstance base) {
        if (base == null) return null;
        if (!ensureSupported(base)) return null;
        try {
            Object config = configField.get(base);
            if (config == null) return null;
            Object location = modelLocationField.get(config);
            return location instanceof ResourceLocation ? (ResourceLocation) location : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Проверяет через {@code ClientAssetsManager.INSTANCE.getBedrockModelPOJO(location)}
     * (см. javadoc полей выше), знает ли TACZ вообще про этот ResourceLocation. Если пробник
     * не смог инициализироваться рефлексией (другая версия форка) - возвращает {@code true}
     * (не блокируем попытку, деградируем на старое поведение: TACZ сам сообщит об ошибке
     * позже, просто без нашей заблаговременной диагностики).
     */
    private static boolean isModelRecognized(ResourceLocation location) {
        probeAssetsManagerOnce();
        if (getBedrockModelPOJOMethod == null || clientAssetsManagerInstance == null) return true;
        try {
            Object result = getBedrockModelPOJOMethod.invoke(clientAssetsManagerInstance, location);
            return result != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Не можем спросить - не блокируем попытку по этой же причине, что и выше.
            return true;
        }
    }

    private static void probeAssetsManagerOnce() {
        if (assetsManagerProbeDone) return;
        synchronized (GunModelPatcher.class) {
            if (assetsManagerProbeDone) return;
            assetsManagerProbeDone = true;
            try {
                // Класс/метод/имя поля-синглтона - буквально из предоставленного
                // декомпилированного исходника GunDisplayInstance.checkTextureAndModel():
                // "ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelLocation)".
                Class<?> assetsManagerClass = Class.forName("com.tacz.guns.client.resource.ClientAssetsManager");
                Object instance = null;
                for (Field field : assetsManagerClass.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && field.getType() == assetsManagerClass) {
                        field.setAccessible(true);
                        instance = field.get(null);
                        break;
                    }
                }
                if (instance == null) {
                    MCPSkins.LOGGER.warn("[MCPSkins] Не нашлось поле-синглтон ClientAssetsManager.INSTANCE - "
                            + "предпроверка распознавания geo-моделей отключена (не блокирует фичу, только диагностику).");
                    return;
                }
                Method method = findMethod(assetsManagerClass, "getBedrockModelPOJO", ResourceLocation.class);
                if (method == null) {
                    MCPSkins.LOGGER.warn("[MCPSkins] Не нашлось ClientAssetsManager.getBedrockModelPOJO(ResourceLocation) - "
                            + "предпроверка распознавания geo-моделей отключена (не блокирует фичу, только диагностику).");
                    return;
                }
                method.setAccessible(true);
                clientAssetsManagerInstance = instance;
                getBedrockModelPOJOMethod = method;
            } catch (ReflectiveOperationException | RuntimeException e) {
                MCPSkins.LOGGER.warn("[MCPSkins] Не удалось получить прямой доступ к ClientAssetsManager для "
                        + "предпроверки geo-моделей (не блокирует фичу, только диагностику).", e);
            }
        }
    }

    private static boolean ensureSupported(GunDisplayInstance sample) {
        if (supportState != -1) return supportState == 1;
        synchronized (GunModelPatcher.class) {
            if (supportState != -1) return supportState == 1;
            boolean ok;
            try {
                ok = discover(sample);
            } catch (ReflectiveOperationException | RuntimeException e) {
                MCPSkins.LOGGER.error("[MCPSkins] Ошибка при разведке внутренностей GunDisplayInstance/GunDisplay для замены geo-модели.", e);
                ok = false;
            }
            supportState = ok ? 1 : 0;
            if (!ok) warnUnsupportedOnce();
            return ok;
        }
    }

    private static boolean discover(GunDisplayInstance sample) throws ReflectiveOperationException {
        // 1) Единственный конструктор (ResourceLocation, GunDisplay) - сигнатура уже проверена
        //    ранее по декомпилированному jar'у (см. javadoc класса).
        Constructor<?> found = null;
        for (Constructor<?> ctor : GunDisplayInstance.class.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2 && params[0] == ResourceLocation.class) {
                found = ctor;
                break;
            }
        }
        if (found == null) return false;
        found.setAccessible(true);
        Class<?> displayClass = found.getParameterTypes()[1];

        // 2) Поле на GunDisplayInstance, хранящее ссылку на этот же конфиг - ищем ПО ТИПУ поля.
        Field foundConfigField = null;
        for (Field field : GunDisplayInstance.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() == displayClass) {
                field.setAccessible(true);
                foundConfigField = field;
                break;
            }
        }
        if (foundConfigField == null) return false;

        Object sampleConfig = foundConfigField.get(sample);
        if (sampleConfig == null) return false;

        // 3) getModelLocation() - имя метода уже проверено ранее (см. javadoc класса). Ищем как
        //    объявленный метод (а не только через getMethod()), чтобы не зависеть от того,
        //    публичный он или пакетный/protected в конкретной версии форка.
        Method getModelLocation = findMethod(displayClass, "getModelLocation");
        if (getModelLocation == null) return false;
        getModelLocation.setAccessible(true);
        Object currentModelLocation = getModelLocation.invoke(sampleConfig);
        if (!(currentModelLocation instanceof ResourceLocation)) return false;

        // ДИАГНОСТИКА (см. javadoc про clientAssetsManagerInstance/getBedrockModelPOJOMethod
        // выше): печатаем РЕАЛЬНЫЙ ResourceLocation модели у уже загруженного, заведомо
        // рабочего оружия - это единственный по-настоящему надёжный источник правды о том,
        // какой формат пути (папка/суффикс/наличие расширения) ClientAssetsManager вашей
        // версии форка ожидает. Печатается ОДИН раз за сессию (внутри discover(), который сам
        // выполняется не более одного раза благодаря supportState). Сравните с путём, который
        // строит SkinAssetResolver.resolveModel (MODEL_PATH_FORMAT) - если конвенции не
        // совпадают, поправьте MODEL_PATH_FORMAT под то, что видите здесь в логе.
        ResourceLocation realModelLocation = (ResourceLocation) currentModelLocation;
        MCPSkins.LOGGER.info(
                "[MCPSkins][diag] Реальный ResourceLocation модели у базового оружия: '{}' "
                        + "(namespace='{}', path='{}'). Файл скина ДОЛЖЕН лежать так, чтобы "
                        + "ClientAssetsManager распознавал его в ТОЙ ЖЕ конвенции (см. javadoc "
                        + "GunModelPatcher про getBedrockModelPOJOMethod и SkinAssetResolver."
                        + "MODEL_PATH_FORMAT).",
                realModelLocation, realModelLocation.getNamespace(), realModelLocation.getPath());

        // 4) Поле модели на GunDisplay - ищем ПО ЗНАЧЕНИЮ (через equals, а не через ==, на
        //    случай если геттер возвращает не сам field, а построенную по нему копию).
        Field foundModelField = null;
        for (Field field : displayClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != ResourceLocation.class) continue;
            field.setAccessible(true);
            Object value = field.get(sampleConfig);
            if (Objects.equals(value, currentModelLocation)) {
                foundModelField = field;
                break;
            }
        }
        if (foundModelField == null) return false;

        displayInstanceConstructor = found;
        gunDisplayClass = displayClass;
        configField = foundConfigField;
        modelLocationField = foundModelField;
        return true;
    }

    private static GunDisplayInstance createInstance(GunDisplayInstance base, ResourceLocation modelOverride) {
        try {
            Object baseConfig = configField.get(base);
            Object configCopy = shallowCopy(baseConfig, gunDisplayClass);
            if (configCopy == null) return null;
            modelLocationField.set(configCopy, modelOverride);

            ResourceLocation identity = syntheticIdentity(modelOverride);
            Object instance = displayInstanceConstructor.newInstance(identity, configCopy);
            GunDisplayInstance created = (GunDisplayInstance) instance;

            // ИСПРАВЛЕНИЕ БАГА "скин с geo-моделью первый раз показывается маленьким/смещённым,
            // пока не переключишь слот или не подберёшь предмет заново":
            //
            // Настоящий конструктор GunDisplayInstance(ResourceLocation, GunDisplay), который мы
            // только что вызвали выше, грузит геометрию/анимацию ЛЕНИВО (см. подробности в
            // оригинальном GunDisplayInstance - modelLoaded/animationLoaded остаются false сразу
            // после конструктора, если ResourceConfig.ENABLE_LAZY_CLIENT_ASSET_LOAD включён, что
            // по умолчанию так и есть). Из-за этого созданный здесь `created` в момент возврата
            // из этого метода - НЕ готов: modelLoaded=false.
            //
            // Дальше в TimelessAPIMixin именно этот факт (см. GunDisplayInstancePatcher#
            // isBaseReadyToPatch, читающий modelLoaded рефлексией) заставляет миксин на ПЕРВОМ
            // вызове TimelessAPI.getGunDisplay(stack) после смены скина вернуть рендеру TACZ
            // "созданный, но ещё не загруженный" `created` НАПРЯМУЮ (без текстуры скина - см.
            // ветку "else if (patchBase != base)" в миксине), и только на СЛЕДУЮЩЕМ вызове -
            // когда modelLoaded уже станет true (потому что рендер TACZ сам успел дёрнуть
            // getGunModel()/getAnimationStateMachine() на предыдущем кадре) - PatchedGunDisplayCache
            // наконец создаёт ЕЩЁ ОДНУ, третью по счёту, копию (`patched`, уже с текстурой).
            //
            // Итог: в первые кадры после применения geo-скина TimelessAPI.getGunDisplay(stack)
            // возвращает TRI РАЗНЫХ Java-объекта подряд (base -> created(без текстуры) ->
            // patched), хотя по документированному в PatchedGunDisplayCache предположению TACZ
            // ожидает СТАБИЛЬНУЮ идентичность GunDisplayInstance на весь срок, пока оружие
            // экипировано (собственно ради этого предположения и написан весь механизм
            // PatchedGunDisplayCache). Каждая такая незапланированная смена идентичности - это,
            // с точки зрения рендер/анимационного кода TACZ, неотличимо от "оружие сменили" - а
            // такая смена у TACZ штатно запускает переигровку позы доставания/опускания оружия
            // (маленькое, смещённое вниз-под интерфейс - ровно то, что видно на скриншоте бага).
            // Пока это состояние успевает переиграться самим TACZ (обычно пара тиков в обычном
            // геймплее), всё незаметно - но в открытом экране рефита анимация оружия у TACZ,
            // по всем признакам, не тикает (или тикает не так, как в обычной игре), так что поза
            // застревает и не доигрывается сама, пока игрок не сделает что-то, что заставляет
            // TACZ пересобрать состояние рендера с нуля (переключить слот, выбросить/подобрать -
            // тот же самый по духу приём, что уже используется в ClientHeldGunRefresher для
            // другого похожего по механике бага).
            //
            // Починка: форсируем ту же самую ленивую загрузку ЗДЕСЬ, синхронно, СРАЗУ после
            // конструктора - ДО того, как этот инстанс вообще увидит миксин/рендер TACZ. Это
            // безопасно (в отличие от прежней, уже отменённой ранее попытки форсировать загрузку
            // ОРИГИНАЛА `base` в withOverrides() - см. подробный javadoc GunDisplayInstancePatcher
            // "ВАЖНЫЙ НЮАНС..."): та попытка ловила гонку с перезагрузкой ресурспака, потому что
            // форсировала загрузку ОБЩЕГО, session-wide singleton-инстанса `base` в ЛЮБОЙ момент,
            // в том числе в узком окне, пока ClientAssetsManager сам ещё пересобирается. Здесь же
            // мы форсируем загрузку СВОЕГО СОБСТВЕННОГО, только что созданного одноразового
            // `created`, и ТОЛЬКО ПОСЛЕ того, как isModelRecognized(modelOverride) в getOrCreate()
            // уже подтвердил, что ClientAssetsManager прямо сейчас знает про этот geo.json - то
            // есть ресурсы для конкретно этой модели гарантированно готовы к моменту вызова.
            // getAnimationStateMachine() тянет за собой и ensureModelLoaded() (см. её собственную
            // реализацию - ensureAnimationLoaded() сама сначала грузит модель), так что одного
            // этого вызова достаточно, чтобы modelLoaded/animationLoaded стали true ДО того, как
            // isBaseReadyToPatch впервые их проверит. Любая ошибка здесь (несовместимый скелет и
            // т.п.) уже штатно ловится общим catch ниже - отдельный try тут не нужен.
            created.getAnimationStateMachine();

            return created;
        } catch (Throwable t) {
            // ЛЮБАЯ ошибка здесь (включая IllegalArgumentException из checkAnimation(), если
            // скелет geo-модели скина не совпадает с тем, что ожидают анимации базового оружия -
            // см. подтверждённый в issue-трекере TACZ пример именно такого краша, а теперь ещё и
            // возможные ошибки из форсированной загрузки чуть выше) НЕ должна ронять рендер
            // целиком - просто отказываемся от замены модели для этого скина, оружие останется
            // с базовой геометрией (перекраска текстуры при этом не страдает).
            MCPSkins.LOGGER.warn(
                    "[MCPSkins] Не удалось собрать geo-модель '{}' - вероятно, скелет geo-модели "
                            + "скина не совпадает с анимациями базового оружия (нужны те же имена "
                            + "костей). Скин останется с базовой геометрией оружия.",
                    modelOverride, t);
            return null;
        }
    }

    private static Object shallowCopy(Object instance, Class<?> type) {
        try {
            Unsafe unsafe = getUnsafe();
            Object copy = unsafe.allocateInstance(type);
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(copy, field.get(instance));
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Unsafe getUnsafe() throws ReflectiveOperationException {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    /**
     * Синтетический "адрес" для конструктора {@code GunDisplayInstance} - НЕ соответствует
     * никакому настоящему GunId TACZ (специально, чтобы не столкнуться и не спутаться с реальным
     * зарегистрированным оружием). По уже проверенному ранее исходнику ({@code
     * checkTextureAndModel()} читает пути ассетов из конфига, а не из этого параметра) это
     * значение используется только для внутренней идентификации/логов самого инстанса.
     */
    private static ResourceLocation syntheticIdentity(ResourceLocation modelOverride) {
        String path = "geo_skin/" + modelOverride.getNamespace() + "/" + modelOverride.getPath();
        ResourceLocation built = ResourceLocation.tryBuild(MCPSkins.MOD_ID, path);
        return built != null ? built : modelOverride;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        return findMethod(clazz, name, new Class<?>[0]);
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void warnUnsupportedOnce() {
        if (unsupportedWarningLogged) return;
        unsupportedWarningLogged = true;
        MCPSkins.LOGGER.warn(
                "[MCPSkins] Полная замена geo-модели скинов отключена: не удалось рефлексией "
                        + "найти нужные внутренности GunDisplayInstance/GunDisplay в вашей версии "
                        + "TACZ (см. javadoc GunModelPatcher). Перекраска текстуры и иконки скина "
                        + "при этом продолжает работать как обычно - страдает только полная "
                        + "замена геометрии для скинов, у которых есть '_geo.json'.");
    }
}