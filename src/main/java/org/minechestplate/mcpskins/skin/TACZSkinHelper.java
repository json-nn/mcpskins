package org.minechestplate.mcpskins.skin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * ПЕРЕХОД НА ТЕКСТУРНЫЙ ОВЕРЛЕЙ (ресурспак), см. {@link org.minechestplate.mcpskins.mixin.TimelessAPIMixin}.
 * <p>
 * {@code GunId} у предмета TACZ теперь НИКОГДА не подменяется - он всегда остаётся тем
 * оружием, которое реально есть в руке (m4a1 так и остаётся m4a1). Какой скин на нём
 * надет, полностью определяется отдельным компонентом {@link SkinComponents#SKIN_ID}:
 * миксин на {@code TimelessAPI.getGunDisplay} читает его и просто подменяет текстуру
 * (см. {@link org.minechestplate.mcpskins.skin.render.SkinAssetResolver}), если в
 * ресурспаке есть соответствующий PNG.
 * <p>
 * Старая схема ("скин - это отдельная зарегистрированная в TACZ пушка, применяем скин -
 * значит подменяем GunId на неё") полностью убрана из этого класса. Если вам всё же
 * потребуется скин с ДРУГОЙ геометрией (не только перекраска), эта система для него не
 * подходит - см. ограничение в javadoc {@code GunDisplayInstancePatcher} (там же описано,
 * почему подмена модели через рефлексию сознательно не реализована).
 */
public class TACZSkinHelper {
    // Базовый предмет для ВСЕХ пушек в TACZ
    public static final ResourceLocation TACZ_GUN_ITEM = ResourceLocation.parse("tacz:modern_kinetic_gun");

    /**
     * Создаёт предмет для отображения в GUI на основе GunId, БЕЗ скина (ровно как раньше -
     * сигнатура и поведение не менялись, чтобы не сломать существующие вызовы из
     * SkinBrowserScreen/SkinViewerScreen и т.п.).
     */
    public static ItemStack createGunStack(String gunId) {
        return createGunStack(gunId, null);
    }

    /**
     * То же самое, но с наложенным скином - для превью/миниатюр в UI (карусель рефита,
     * браузер скинов и т.д.), чтобы иконка сразу показывала перекрашенную текстуру, а не
     * "голое" оружие.
     *
     * @param gunId  сырой (без "default:") GunId оружия
     * @param skinId id скина ИЗ РЕЕСТРА (как в JSON/SkinDataModels.SkinEntry.id()) - может
     *               быть с префиксом "default:" (тогда трактуется как "без скина") или без
     *               него. Может быть {@code null} - тогда тоже без скина.
     */
    public static ItemStack createGunStack(String gunId, String skinId) {
        Item item = BuiltInRegistries.ITEM.get(TACZ_GUN_ITEM);
        if (item == null) return ItemStack.EMPTY;

        ItemStack stack = new ItemStack(item);

        CompoundTag tag = new CompoundTag();
        // Отрезаем префикс, если он есть, чтобы 3D рендер TACZ нашел модель
        String actualGunId = bareSkinId(gunId);

        tag.putString("GunId", actualGunId);
        tag.putByte("HasBulletInBarrel", (byte) 1);

        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        applySkinComponent(stack, actualGunId, skinId);
        return stack;
    }

    /**
     * Применяет скин к оружию, СОХРАНЯЯ все обвесы, патроны и прочие компоненты.
     * <p>
     * В отличие от старого {@code applySkinSafely}, GunId предмета здесь НЕ ТРОГАЕТСЯ
     * вообще - оружие остаётся тем же самым GunId'ом, каким было. Меняется только
     * компонент {@link SkinComponents#SKIN_ID}: устанавливается для реального скина, либо
     * полностью убирается ({@code stack.remove(...)}), если запрошен "дефолтный" вид -
     * чтобы миксин увидел {@code null} и вообще не полез искать файл текстуры.
     *
     * @param newSkinId id скина из реестра (может быть "default:&lt;gunId&gt;" - тогда
     *                  скин снимается и возвращается родная текстура оружия)
     */
    public static ItemStack applySkin(ItemStack originalWeapon, String newSkinId) {
        if (originalWeapon.isEmpty() || !originalWeapon.is(BuiltInRegistries.ITEM.get(TACZ_GUN_ITEM))) {
            return originalWeapon;
        }

        ItemStack skinnedWeapon = originalWeapon.copy();

        String baseGunId = getGunId(skinnedWeapon);
        if (baseGunId == null) return originalWeapon;

        applySkinComponent(skinnedWeapon, baseGunId, newSkinId);
        return skinnedWeapon;
    }

    /**
     * Общая точка записи компонента скина, используется и в {@link #createGunStack},
     * и в {@link #applySkin}, чтобы правило "default: или совпадение с базовым GunId
     * значит без скина" не расходилось между ними.
     */
    private static void applySkinComponent(ItemStack stack, String baseGunId, String skinId) {
        String bare = skinId == null ? null : bareSkinId(skinId);
        if (bare == null || bare.isBlank() || bare.equals(baseGunId)) {
            stack.remove(SkinComponents.SKIN_ID.get());
        } else {
            stack.set(SkinComponents.SKIN_ID.get(), bare);
        }
    }

    /**
     * Сырой (без префикса "default:") GunId предмета, если это оружие TACZ с
     * соответствующим тегом, иначе null. Не изменился - GunId по-прежнему живёт в
     * CustomData, а не в data-компоненте, это формат самого TACZ.
     */
    public static String getGunId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (!data.contains("GunId")) return null;
        return data.copyTag().getString("GunId");
    }

    /**
     * НОВОЕ: сырой id надетого скина ({@link SkinComponents#SKIN_ID}), либо {@code null},
     * если скина нет (оружие в "заводском" виде). В отличие от {@link #getGunId}, это
     * значение теперь единственный источник правды о том, какой скин показывается -
     * GunId для этого больше не используется.
     */
    public static String getSkinId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String value = stack.get(SkinComponents.SKIN_ID.get());
        return (value == null || value.isBlank()) ? null : value;
    }

    /** Убирает служебный префикс "default:" у id (скина или пушки). */
    public static String bareSkinId(String id) {
        if (id == null) return null;
        return id.startsWith("default:") ? id.substring(8) : id;
    }
}