package org.minechestplate.mcpskins.mixin;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinComponents;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;
import org.minechestplate.mcpskins.skin.render.GunDisplayInstancePatcher;
import org.minechestplate.mcpskins.skin.render.GunModelPatcher;
import org.minechestplate.mcpskins.skin.render.PatchedGunDisplayCache;
import org.minechestplate.mcpskins.skin.render.SkinAssetResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Читает {@code stack.get(SkinComponents.SKIN_ID.get())} и, если для (baseGunId, skinId)
 * в активных ресурспаках существует {@code textures/skins/<baseGunId>/<skinId>.png},
 * подменяет текстуру возвращаемого {@link GunDisplayInstance} - см.
 * {@link SkinAssetResolver#resolveTexture} за формирование пути и
 * {@link GunDisplayInstancePatcher#withOverrides} за сам патч.
 * <p>
 * Компонент {@code mcpskins:skin_id} теперь ДЕЙСТВИТЕЛЬНО записывается на ItemStack -
 * см. {@link TACZSkinHelper#applySkin} (настоящее применение скина, сервер) и
 * {@code TACZRefitSkinOverlay#previewLockedSkin} (клиентский предпросмотр непроверенных
 * скинов). GunId предмета при этом никогда не подменяется - оружие всегда остаётся тем
 * же самым GunId'ом, меняется только то, какую текстуру (и, опционально, иконку) миксин
 * ему "подрисовывает".
 * <p>
 * <b>НОВОЕ - опциональная фича "своя иконка скина":</b> если у скина, помимо обязательной
 * UV-текстуры {@code textures/skins/<baseGunId>/<skinId>.png}, в ресурспаке есть ЕЩЁ файл
 * {@code textures/skins/<baseGunId>/<skinId>_icon.png} (см. {@link SkinAssetResolver#resolveIcon}),
 * миксин заодно подменяет и 2D-иконку предмета в инвентаре/слотах (по умолчанию у TACZ она
 * не зависит от UV-текстуры 3D-модели и без этой фичи так и осталась бы иконкой базового
 * оружия). Фича полностью опциональна: если файла {@code _icon.png} нет - иконка не
 * трогается вообще, и всё работает ровно как раньше (перекрашивается только 3D-модель).
 * Никаких изменений в датапаке (JSON конфигов скинов) для этого не требуется.
 * <p>
 * <b>НОВОЕ - полная замена geo-модели:</b> если для скина в АКТИВНОМ ресурспаке есть файл
 * geo-модели, лежащий РЯДОМ с настоящей моделью того же оружия (namespace и папка берутся
 * автоматически из настоящей модели ЭТОГО оружия, а не угадываются заранее - см. подробный
 * javadoc {@link SkinAssetResolver#resolveModel} и {@link GunModelPatcher#getBaseModelLocation}
 * про то, почему), миксин пытается собрать для оружия ПОЛНОСТЬЮ новую геометрию через
 * {@link GunModelPatcher} - см. его подробный javadoc про то, почему это не такое же простое
 * переопределение поля, как текстура, и как это сделано, чтобы не повторять "плачевный опыт
 * TAC" с ручным парсингом приватных форматов. Как и иконка, эта фича полностью опциональна и
 * молча деградирует до чисто текстурного скина, если подходящего файла geo.json нет, скелет
 * модели несовместим с анимациями оружия, или версия форка не поддерживается.
 */
@Mixin(TimelessAPI.class)
public class TimelessAPIMixin {

    @Inject(
            method = "getGunDisplay(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void mcpskins$applySkinOverride(ItemStack stack, CallbackInfoReturnable<Optional<GunDisplayInstance>> cir) {
        Optional<GunDisplayInstance> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) return;

        String skinId = stack.get(SkinComponents.SKIN_ID.get());
        if (skinId == null || skinId.isBlank()) return;

        String baseGunId = TACZSkinHelper.getGunId(stack);
        if (baseGunId == null) return;

        GunDisplayInstance base = original.get();
        ResourceLocation baseTexture = GunDisplayInstancePatcher.getTexture(base);

        ResourceLocation texture = SkinAssetResolver.resolveTexture(MCPSkins.MOD_ID, baseGunId, skinId, baseTexture);
        boolean hasTexture = texture != null && !texture.equals(baseTexture);

        // НОВОЕ: полная замена geo-модели - см. подробный javadoc SkinAssetResolver#resolveModel
        // про то, почему namespace/папка geo-файла БОЛЬШЕ НЕ угадываются заранее (ни как
        // namespace скина, ни жёстко как tacz - обе догадки оказались неверны на практике для
        // оружия из чужих ганпаков), а берутся ИЗ РЕАЛЬНОЙ модели конкретно этого оружия через
        // GunModelPatcher#getBaseModelLocation. Если рефлексия не разведала нужные поля
        // (неподдерживаемая версия форка) - baseModelLocation будет null, и модель просто не
        // трогается (скин остаётся чисто текстурным, как раньше).
        ResourceLocation baseModelLocation = GunModelPatcher.getBaseModelLocation(base);
        ResourceLocation model = baseModelLocation != null ? SkinAssetResolver.resolveModel(baseModelLocation, skinId) : null;
        boolean hasModel = model != null;

        if (!hasTexture && !hasModel) {
            // Ни текстуры, ни модели для этого скина нет в активных ресурспаках - скина
            // фактически нет, оставляем оригинал целиком.
            return;
        }

        String cacheKey = baseGunId + '\u0000' + skinId;

        // Геометрия собирается ПЕРВОЙ - если получилось, дальше текстуру/иконку накладываем уже
        // поверх новой (geo-скиновой) GunDisplayInstance, а не поверх базовой. Если сборка не
        // удалась (GunModelPatcher вернул null - неподдерживаемая версия форка или
        // несовместимый скелет, см. его javadoc) - тихо остаёмся на базовой геометрии, эта
        // деградация никак не влияет на перекраску текстуры/иконки ниже.
        GunDisplayInstance patchBase = base;
        if (hasModel) {
            GunDisplayInstance geoInstance = GunModelPatcher.getOrCreate(cacheKey, base, model);
            if (geoInstance != null) {
                patchBase = geoInstance;
            }
        }

        // НОВОЕ (опционально): если рядом с текстурой скина лежит "<skinId>_icon.png",
        // заодно подменяем и 2D-иконку предмета в инвентаре - см. javadoc класса и
        // SkinAssetResolver.resolveIcon. Если файла нет ИЛИ поле иконки не удалось найти
        // рефлексией (см. GunDisplayInstancePatcher) - iconOverride останется null, и
        // withOverrides() просто не тронет иконку, ровно как было раньше.
        ResourceLocation baseIcon = GunDisplayInstancePatcher.getIcon(base);
        ResourceLocation icon = SkinAssetResolver.resolveIcon(MCPSkins.MOD_ID, baseGunId, skinId, baseIcon);
        ResourceLocation iconOverride = (icon != null && !icon.equals(baseIcon)) ? icon : null;
        ResourceLocation textureOverride = hasTexture ? texture : null;

        if (textureOverride == null && iconOverride == null) {
            // Только геометрия, без перекраски текстуры/иконки - patchBase уже финальный
            // результат (либо собранный geo-инстанс, либо, если сборка не удалась, оригинал -
            // тогда возвращать вообще нечего, оставляем ванильный вид).
            if (patchBase != base) {
                cir.setReturnValue(Optional.of(patchBase));
            }
            return;
        }

        // Через кэш, а не напрямую через GunDisplayInstancePatcher.withOverrides - этот метод
        // вызывается очень часто (потенциально каждый рендер-кадр), и без кэша каждый вызов
        // создавал бы физически новый объект-копию даже для той же самой комбинации
        // (оружие, скин). Ключ - строка (baseGunId, skinId), а не идентичность объекта
        // "patchBase" - см. подробное объяснение в javadoc {@link PatchedGunDisplayCache}.
        GunDisplayInstance patched = PatchedGunDisplayCache.getOrCreate(cacheKey, patchBase, textureOverride, iconOverride);
        if (patched != null) {
            cir.setReturnValue(Optional.of(patched));
        } else if (patchBase != base) {
            // Текстурный/иконочный патч пока не готов (модель ещё лениво грузится - см.
            // GunDisplayInstancePatcher#isBaseReadyToPatch) - показываем хотя бы верную
            // геометрию с базовой текстурой прямо сейчас, а не оригинальную геометрию скина.
            // Текстура/иконка сами подтянутся на одном из следующих вызовов этого же метода -
            // он вызывается достаточно часто, задержка на глаз незаметна.
            cir.setReturnValue(Optional.of(patchBase));
        }
        // И patched, и запасной patchBase могут быть null/base одновременно - тогда оригинал
        // (без скина вообще) остаётся как есть, без единого лишнего аллокейшна.
    }
}