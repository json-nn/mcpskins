package org.minechestplate.mcpskins.skin.render;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * ПРОВЕРЕНО по реальному исходнику {@code com.tacz.guns.client.resource.GunDisplayInstance}
 * (форк MUKSC/TACZ-1.21.1, ветка neoforge/1.21.1). Три вещи в исходной версии этого класса
 * были неверны, по порядку от "просто опечатка" до "в принципе не могло работать":
 *
 * 1) TEXTURE_FIELD_NAME был "texture" - в реальном классе поле называется
 *    {@code modelTexture} (у него, кстати, уже ЕСТЬ публичный геттер
 *    {@code getModelTexture()} - рефлексия для ЧТЕНИЯ вообще не нужна, см. ниже).
 *
 * 2) MODEL_FIELD_NAME "geoModel" - такого поля в GunDisplayInstance нет вообще, и это не
 *    просто опечатка: в реальном классе geo-модель не хранится как ResourceLocation после
 *    загрузки. {@code checkTextureAndModel()} читает {@code display.getModelLocation()} как
 *    ЛОКАЛЬНУЮ переменную, тут же скармливает её {@code ClientAssetsManager} и
 *    {@code GunModelTypeManager}, и результат (уже собранная геометрия/анимации) кладёт в
 *    поле {@code gunModel} типа {@code BedrockGunModel} - это не ResourceLocation, а
 *    полностью распарсенная модель. Переопределить "какой файл геометрии использовать"
 *    подменой одного поля-ссылки НЕЛЬЗЯ - для этого пришлось бы повторить своей рефлексией
 *    добрую половину приватной логики {@code checkTextureAndModel()} (ClientAssetsManager +
 *    GunModelTypeManager + BedrockVersion + сборка нового BedrockGunModel), что весьма
 *    хрупко и ломается при любом рефакторинге рендера TACZ. Поэтому геометрию (полную
 *    замену модели, а не перекраску) этот класс больше не трогает - см. javadoc
 *    {@link #withOverrides}. Если каким-то скинам всё-таки нужна другая геометрия
 *    (не только текстура), используйте для НИХ прежний рабочий механизм мода - подмену
 *    GunId на отдельно зарегистрированную "скин-пушку" (то, что уже делает
 *    {@code TACZSkinHelper.applySkinSafely}) - TACZ тогда сам корректно соберёт и модель,
 *    и текстуру, и анимации по своему штатному пайплайну, без всякой рефлексии.
 *
 * 3) shallowCopy() пытался вызвать Object.clone() через рефлексию, предварительно проверяя
 *    "instance instanceof Cloneable". GunDisplayInstance НЕ реализует Cloneable (см.
 *    объявление класса: {@code public class GunDisplayInstance}, без interface) - то есть
 *    эта проверка проваливалась ВСЕГДА, и withOverrides() гарантированно возвращал null
 *    независимо от того, правильные были имена полей или нет. При этом GunDisplayInstance -
 *    это и не Java record (тоже видно по объявлению класса), так что и альтернативная ветка
 *    из пункта 4 старого javadoc ("собрать через канонический конструктор record'а") тоже не
 *    подходила. Единственный конструктор пакетный ({@code GunDisplayInstance(ResourceLocation,
 *    GunDisplay)}) и делает тяжёлую работу (парсинг display-json, а при выключенном
 *    ENABLE_LAZY_CLIENT_ASSET_LOAD - ещё и загрузку анимаций) - вызывать его повторно ради
 *    копии не вариант. Поэтому копия здесь создаётся через {@code sun.misc.Unsafe#allocateInstance}
 *    (тот же приём, которым Gson/Objenesis/Netty создают экземпляры в обход конструктора),
 *    а все поля переносятся в неё рефлексией по одному - это ручной эквивалент shallow-clone.
 *    {@code sun.misc.Unsafe} доступен без дополнительных --add-opens: пакет sun.misc
 *    экспортируется модулем jdk.unsupported всем немодульным классам "из коробки" -
 *    именно поэтому этим приёмом безопасно пользуются сторонние библиотеки на класспасе.
 *
 * ВАЖНЫЙ НЮАНС про ленивую загрузку (ИСПРАВЛЕНО - причина бага "голые руки после
 * захода/обновления ресурспака"): GunDisplayInstance грузит текстуру/модель ЛЕНИВО (см.
 * {@code ensureModelLoaded()} в оригинале) и помнит это через приватные флаги
 * modelLoaded/modelLoadFailed + volatile CompletableFuture modelWarmUpTask. Если скопировать
 * инстанс ДО того, как оригинал хоть раз запросил текстуру/модель, эти флаги на копии будут
 * "не загружено", и следующий же вызов copy.getModelTexture() заново прогонит
 * checkTextureAndModel(display) уже НА КОПИИ - а это молча перезапишет наше переопределение
 * текстуры обратно на ванильную.
 * <p>
 * Раньше эта проблема "решалась" тем, что {@code withOverrides()} сам форсировал загрузку на
 * ОРИГИНАЛЕ прямым вызовом {@code getModelTexture()} перед копированием. Это оказалось ХУЖЕ
 * исходной проблемы: {@code TimelessAPI.getGunDisplay()} (и, соответственно, наш миксин на
 * него) может дёргаться очень рано и очень часто - в том числе в момент, когда клиент только
 * что применил/перезагрузил ресурспак (например, зашёл на сервер с ресурспаком скинов) и
 * {@code ClientAssetsManager}/{@code GunModelTypeManager} у TACZ ещё сами пересобираются.
 * Форсированный вызов {@code getModelTexture()} именно в этом узком окне мог запустить
 * загрузку по недособранным на тот момент ресурсам, получить обрезанный/пустой результат и
 * выставить {@code modelLoaded=true} с уже испорченными данными - а раз флаг "загружено" один
 * раз стал true, {@code ensureModelLoaded()} больше никогда сам не перезапускал загрузку. Итог
 * на экране - оружие в руке рендерится без модели (голые руки), пока GunId не воссоздастся
 * заново (что происходит при выбросе/подборе предмета - тогда TACZ строит новый
 * GunDisplayInstance с нуля).
 * <p>
 * Починено: {@code withOverrides()} теперь НИЧЕГО не форсирует. Он лишь читает те же самые
 * флаги {@code modelLoaded}/{@code modelLoadFailed} (см. {@link #isBaseReadyToPatch}) и, если
 * TACZ ещё не закончил загрузку сам по своему обычному, безопасному пути - тихо возвращает
 * {@code null} (миксин в этом случае оставляет оригинал как есть и просто попробует снова на
 * следующий вызов, благо вызывается он достаточно часто, задержка перекраски получается на
 * глаз незаметной). Копия при этом создаётся, только когда TACZ уже сам подтвердил, что
 * загрузка завершилась успешно - тогда флаги "загружено"/"ошибка" переносятся на копию уже
 * как надёжно true/false, и её собственный ensureModelLoaded() ничего не делает.
 *
 * <p><b>НОВОЕ - опциональная фича "своя иконка скина" (2D-иконка предмета в инвентаре):</b>
 * в отличие от {@code modelTexture}, имя приватного поля, отвечающего за 2D-иконку слота
 * (то, что в JSON ганпака называется {@code "slot"} и физически лежит по пути вида
 * {@code textures/gun/slot/<gun>.png}), в реальном исходнике конкретно ВАШЕГО форка
 * {@code MUKSC/TACZ-1.21.1} НЕ проверялось (в отличие от {@code modelTexture} - см. пункт 1
 * выше, который проверен). У меня не было доступа для скачивания и декомпиляции jar'а этого
 * форка, поэтому вместо одного жёстко прописанного имени {@link # findIconField} перебирает
 * список правдоподобных имён {@link #ICON_FIELD_CANDIDATES} и берёт первое поле типа
 * {@link ResourceLocation}, которое реально существует в классе. Это НЕ гарантия успеха -
 * если реальное имя поля отличается от всех кандидатов, фича просто тихо не будет работать
 * (со ОДНИМ предупреждением в лог при первой попытке, без спама и без краша) - перекраска
 * 3D-модели (то, что уже было проверено и работает) при этом продолжит работать как обычно.
 * <p>
 * Если предупреждение в логе появилось - откройте {@code GunDisplayInstance.class} из jar'а
 * вашего форка декомпилятором (например, Vineflower/FernFlower через инструмент вроде
 * JD-GUI, MCreator's "Decompile", или просто {@code javap -p -c GunDisplayInstance.class}
 * для списка полей без тел методов), найдите реальное имя приватного поля типа
 * {@code ResourceLocation}, которое хранит путь к {@code textures/gun/slot/*.png}, и
 * допишите его ПЕРВЫМ в {@link #ICON_FIELD_CANDIDATES} - тогда фича заработает без каких-либо
 * других правок кода.
 */
public final class GunDisplayInstancePatcher {

    private static final String TEXTURE_FIELD_NAME = "modelTexture";

    /**
     * Имена приватных флагов ленивой загрузки, упомянутых в javadoc класса выше
     * ("modelLoaded"/"modelLoadFailed") - используются в {@link #isBaseReadyToPatch}, чтобы
     * НЕ форсировать загрузку самим, а лишь спросить, закончил ли её TACZ своим обычным путём.
     */
    private static final String MODEL_LOADED_FIELD_NAME = "modelLoaded";
    private static final String MODEL_LOAD_FAILED_FIELD_NAME = "modelLoadFailed";
    private static volatile boolean loadFlagWarningLogged = false;

    // Кэшированное поле modelTexture для НЕфорсирующего чтения в getTexture() - см. javadoc
    // этого метода про то, почему публичный геттер сюда не годится, вопреки тому, что было
    // написано в п.1 javadoc класса раньше.
    private static volatile boolean textureFieldSearched = false;
    private static volatile Field cachedTextureField;
    private static volatile boolean textureFieldWarningLogged = false;

    /**
     * Правдоподобные имена поля 2D-иконки (см. javadoc класса) - перебираются по порядку,
     * используется первое, которое реально существует и имеет тип {@link ResourceLocation}.
     * Допишите сюда верифицированное имя первым пунктом, как только его узнаете.
     */
    private static final String[] ICON_FIELD_CANDIDATES = {
            "icon", "slotTexture", "iconTexture", "slotIcon", "invTexture",
            "inventoryTexture", "guiTexture", "slot"
    };

    // Поле иконки ищется рефлексией один раз за сессию игры и кэшируется - см. resolveIconField().
    private static volatile boolean iconFieldSearched = false;
    private static volatile Field cachedIconField;
    private static volatile boolean iconFieldWarningLogged = false;

    private GunDisplayInstancePatcher() {
    }

    /**
     * <b>ИСПРАВЛЕНО (это и была причина того, что баг "голые руки" пережил предыдущую правку):</b>
     * раньше здесь стоял {@code instance.getModelTexture()} с комментарием "публичный геттер
     * уже есть, рефлексия для чтения не нужна" - формально верно, но у этого геттера в реальном
     * исходнике TACZ (см. {@code GunDisplayInstance.getModelTexture()}) есть побочный эффект:
     * <pre>{@code
     * public ResourceLocation getModelTexture() {
     *     ensureModelLoaded();  // <- та же самая форсированная ленивая загрузка
     *     return modelTexture != null ? modelTexture : MissingTextureAtlasSprite.getLocation();
     * }
     * }</pre>
     * То есть это ТОЧНО ТА ЖЕ форсирующая загрузка, которую якобы убрали из
     * {@link #withOverrides}. В {@code TimelessAPIMixin} этот метод вызывается ДО проверки
     * {@link #isBaseReadyToPatch} (чтобы получить {@code baseTexture} для сравнения с
     * разрешённой текстурой скина) - то есть флаг {@code modelLoaded} принудительно
     * становился {@code true} ИМЕННО ЗДЕСЬ, на строку раньше, чем до него добиралась защита
     * от гонки. Сама защита при этом технически работала правильно, но видела уже
     * подделанную реальность - "готово", потому что готовым её только что сделал этот же
     * вызов. Отсюда и наблюдение "то чинится, то нет" - гонка с {@code ClientAssetsManager}
     * никуда не делась, просто переехала на одну строчку раньше.
     * <p>
     * Теперь читаем приватное поле {@code modelTexture} НАПРЯМУЮ рефлексией (ровно тот же
     * приём, что уже используется в {@link #isBaseReadyToPatch}/{@link #getIcon} для
     * {@code modelLoaded}/иконки) - ни один вызов в этом методе больше не может САМ
     * инициировать загрузку. Если модель ещё не загружена - поле просто вернёт {@code null}
     * (а не {@code MissingTextureAtlasSprite}), и вызывающий код (см. {@code TimelessAPIMixin})
     * корректно это переживёт: {@code SkinAssetResolver.resolveTexture} ищет файл скина в
     * ресурспаках независимо от состояния загрузки TACZ, а {@link #withOverrides} чуть ниже
     * всё равно откажется патчить неготовый оригинал через {@link #isBaseReadyToPatch} - на
     * этот раз по-настоящему, а не для вида.
     */
    public static ResourceLocation getTexture(GunDisplayInstance instance) {
        if (instance == null) return null;
        Field field = resolveTextureField();
        if (field == null) {
            // Поле переименовали в какой-то версии форка - деградируем на старое (форсирующее)
            // поведение, чтобы фича скинов хотя бы не переставала работать целиком, но
            // предупреждаем один раз, что защита от гонки с ресурспаком сейчас не активна.
            warnMissingTextureFieldOnce();
            return instance.getModelTexture();
        }
        try {
            Object value = field.get(instance);
            return value instanceof ResourceLocation location ? location : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Field resolveTextureField() {
        if (textureFieldSearched) return cachedTextureField;
        synchronized (GunDisplayInstancePatcher.class) {
            if (textureFieldSearched) return cachedTextureField;
            Field found;
            try {
                found = findField(GunDisplayInstance.class, TEXTURE_FIELD_NAME);
                found.setAccessible(true);
            } catch (NoSuchFieldException e) {
                found = null;
            }
            cachedTextureField = found;
            textureFieldSearched = true;
            return found;
        }
    }

    private static void warnMissingTextureFieldOnce() {
        if (textureFieldWarningLogged) return;
        synchronized (GunDisplayInstancePatcher.class) {
            if (textureFieldWarningLogged) return;
            textureFieldWarningLogged = true;
            MCPSkins.LOGGER.warn(
                    "Не нашлось поле '{}' в GunDisplayInstance вашей версии TACZ - чтение "
                            + "базовой текстуры откатилось на getModelTexture(), что означает: "
                            + "защита от гонки с загрузкой ресурспака (см. javadoc "
                            + "GunDisplayInstancePatcher#getTexture) сейчас НЕ активна. "
                            + "Декомпилируйте GunDisplayInstance.class и поправьте TEXTURE_FIELD_NAME, "
                            + "если поле переименовали.",
                    TEXTURE_FIELD_NAME);
        }
    }

    /**
     * Текущее значение 2D-иконки предмета (2D-текстура слота инвентаря), если поле удалось
     * найти рефлексией (см. {@link #ICON_FIELD_CANDIDATES} и javadoc класса), иначе
     * {@code null}. В отличие от {@link #getTexture}, публичного геттера для этого поля в
     * GunDisplayInstance может не быть, поэтому читаем через то же кэшированное {@link Field}.
     */
    public static ResourceLocation getIcon(GunDisplayInstance instance) {
        if (instance == null) return null;
        Field field = resolveIconField();
        if (field == null) return null;
        try {
            Object value = field.get(instance);
            return value instanceof ResourceLocation location ? location : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Готов ли ОРИГИНАЛ к копированию - то есть закончил ли TACZ (сам, своим обычным путём,
     * без нашего вмешательства) ленивую загрузку текстуры/модели для него. См. подробное
     * объяснение проблемы и почему мы больше не форсируем загрузку сами - в javadoc класса
     * выше ("ВАЖНЫЙ НЮАНС... ИСПРАВЛЕНО").
     * <p>
     * Если поле {@code modelLoaded} не нашлось рефлексией (переименовали в какой-то версии
     * форка) - не блокируем скины из-за этого: ведём себя как "готово" (старое поведение),
     * но один раз пишем в лог предупреждение, что защита от гонки с ресурспаком не активна.
     */
    private static boolean isBaseReadyToPatch(GunDisplayInstance instance) {
        Boolean loaded = readBooleanField(instance, MODEL_LOADED_FIELD_NAME);
        if (loaded == null) {
            warnMissingLoadFlagOnce();
            return true;
        }
        if (!loaded) return false;

        Boolean failed = readBooleanField(instance, MODEL_LOAD_FAILED_FIELD_NAME);
        // failed == null (поле не нашлось) не считаем поводом блокировать патч - modelLoaded
        // уже true, этого достаточно в подавляющем большинстве случаев.
        return failed == null || !failed;
    }

    private static Boolean readBooleanField(GunDisplayInstance instance, String fieldName) {
        try {
            Field field = findField(GunDisplayInstance.class, fieldName);
            field.setAccessible(true);
            Object value = field.get(instance);
            return value instanceof Boolean bool ? bool : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void warnMissingLoadFlagOnce() {
        if (loadFlagWarningLogged) return;
        synchronized (GunDisplayInstancePatcher.class) {
            if (loadFlagWarningLogged) return;
            loadFlagWarningLogged = true;
            MCPSkins.LOGGER.warn(
                    "Не нашлось поле '{}' в GunDisplayInstance вашей версии TACZ - защита от гонки "
                            + "с загрузкой ресурспака (см. javadoc GunDisplayInstancePatcher) отключена, "
                            + "патч скина применяется без проверки готовности оригинала, как раньше. "
                            + "Декомпилируйте GunDisplayInstance.class и поправьте MODEL_LOADED_FIELD_NAME/"
                            + "MODEL_LOAD_FAILED_FIELD_NAME, если это поле переименовали.",
                    MODEL_LOADED_FIELD_NAME);
        }
    }

    /**
     * Возвращает копию instance с переопределённой текстурой оружия (обязательный параметр
     * для перекраски скина) и, опционально, переопределённой 2D-иконкой предмета в
     * инвентаре (см. javadoc класса про {@link #ICON_FIELD_CANDIDATES} - фича опциональна и
     * молча пропускается, если поле иконки не удалось найти рефлексией).
     * <p>
     * Модель (геометрию) этот метод сознательно не трогает - см. пункт 2 в javadoc класса.
     * Возвращает {@code null}, если рефлексия не смогла создать/записать копию - тогда
     * вызывающий миксин просто не применяет переопределение (оставляет ванильный вид), без
     * краша. Если {@code iconOverride == null} (нет "_icon.png" у скина ИЛИ поле не найдено) -
     * иконка не трогается вообще, копия при этом всё равно создаётся и текстура всё равно
     * патчится как обычно.
     */
    public static GunDisplayInstance withOverrides(GunDisplayInstance instance, ResourceLocation textureOverride, ResourceLocation iconOverride) {
        if (instance == null) return null;
        if (textureOverride == null && iconOverride == null) return instance;

        // ВАЖНО: раньше здесь стоял форс-вызов instance.getModelTexture(), чтобы "досрочно"
        // прогнать ленивую загрузку перед копированием. Именно ОН и был причиной "заморозки"
        // оружия с голыми руками вместо модели после обновления/захода с ресурспаком скинов -
        // см. подробное объяснение в {@link #isBaseReadyToPatch}. Мы больше НИЧЕГО не форсируем
        // сами - только спрашиваем, готов ли оригинал (по мнению самого TACZ), и если ещё нет -
        // тихо возвращаем null (миксин в этом случае оставит оригинал как есть и попробует
        // снова на следующий вызов getGunDisplay, благо вызывается он часто).
        if (!isBaseReadyToPatch(instance)) {
            return null;
        }

        Field iconField = iconOverride != null ? resolveIconField() : null;

        GunDisplayInstance copy = shallowCopy(instance);
        if (copy == null) return null;
        try {
            if (textureOverride != null) {
                writeField(copy, TEXTURE_FIELD_NAME, textureOverride);
            }
            if (iconField != null) {
                iconField.set(copy, iconOverride);
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            MCPSkins.LOGGER.error("Не удалось применить переопределение текстуры/иконки скина через рефлексию. " +
                    "Проверьте TEXTURE_FIELD_NAME/ICON_FIELD_CANDIDATES в GunDisplayInstancePatcher (см. javadoc " +
                    "класса) - возможно, поля переименовали в новой версии TACZ.", e);
            return null;
        }
    }

    /**
     * Совместимость с прежним API (только перекраска текстуры, без иконки) - используется,
     * например, местами, где override иконки заведомо не нужен.
     */
    public static GunDisplayInstance withTextureOverride(GunDisplayInstance instance, ResourceLocation texture) {
        return withOverrides(instance, texture, null);
    }

    /**
     * Ищет поле 2D-иконки рефлексией среди {@link #ICON_FIELD_CANDIDATES} и кэширует
     * результат на весь сеанс игры (поиск рефлексией недёшев, а набор полей класса за время
     * работы игры не меняется). При неудаче логирует ОДНО предупреждение (не на каждый кадр)
     * с инструкцией, что делать - см. javadoc класса.
     */
    private static Field resolveIconField() {
        if (iconFieldSearched) return cachedIconField;
        synchronized (GunDisplayInstancePatcher.class) {
            if (iconFieldSearched) return cachedIconField;
            Field found = null;
            for (String name : ICON_FIELD_CANDIDATES) {
                try {
                    Field field = findField(GunDisplayInstance.class, name);
                    if (field.getType() == ResourceLocation.class) {
                        field.setAccessible(true);
                        found = field;
                        break;
                    }
                } catch (NoSuchFieldException ignored) {
                    // пробуем следующего кандидата
                }
            }
            cachedIconField = found;
            iconFieldSearched = true;
            if (found == null && !iconFieldWarningLogged) {
                iconFieldWarningLogged = true;
                MCPSkins.LOGGER.warn(
                        "Опциональная фича 'своя иконка скина' отключена: не нашлось поле типа ResourceLocation "
                                + "ни под одним из ожидаемых имён {} в GunDisplayInstance вашей версии TACZ. "
                                + "Перекраска 3D-модели скина при этом продолжает работать как обычно - страдает "
                                + "только 2D-иконка в инвентаре, она остаётся от базового оружия. Чтобы включить "
                                + "фичу, декомпилируйте GunDisplayInstance.class из jar'а TACZ, найдите реальное имя "
                                + "приватного поля, отвечающего за textures/gun/slot/*.png, и допишите его первым "
                                + "в GunDisplayInstancePatcher.ICON_FIELD_CANDIDATES.",
                        Arrays.toString(ICON_FIELD_CANDIDATES));
            }
            return found;
        }
    }

    /**
     * Ручной shallow-copy через Unsafe#allocateInstance (конструктор не вызывается вообще) +
     * копирование всех полей по одному. См. пункт 3 в javadoc класса - GunDisplayInstance не
     * Cloneable и не record, поэтому Object.clone() и канонический конструктор record'а здесь
     * не варианты.
     */
    private static GunDisplayInstance shallowCopy(GunDisplayInstance instance) {
        try {
            Unsafe unsafe = getUnsafe();
            Object rawCopy = unsafe.allocateInstance(GunDisplayInstance.class);
            GunDisplayInstance copy = (GunDisplayInstance) rawCopy;
            for (Field field : GunDisplayInstance.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(copy, field.get(instance));
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            MCPSkins.LOGGER.error("Не удалось создать копию GunDisplayInstance через Unsafe. " +
                    "Возможно, окружение блокирует sun.misc.Unsafe - тогда переопределение " +
                    "скинов работать не будет, но краша не произойдёт.", e);
            return null;
        }
    }

    private static Unsafe getUnsafe() throws ReflectiveOperationException {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static void writeField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}