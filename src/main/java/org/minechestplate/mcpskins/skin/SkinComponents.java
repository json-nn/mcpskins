package org.minechestplate.mcpskins.skin;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * Регистрация кастомного Data Component'а {@code mcpskins:skin_id}.
 *
 * Это подтверждённо-корректный, стандартный для NeoForge 1.21.1 паттерн регистрации
 * (аналогичный тому, как ванилла регистрирует {@code DataComponents.CUSTOM_DATA}) - здесь
 * ничего специфичного для TACZ нет, риска несовпадения версии нет вообще.
 *
 * <ul>
 *     <li>{@code persistent(Codec.STRING)} - значение сохраняется в NBT предмета между
 *     сессиями (так же, как обычный тег в CustomData, но типобезопасно и без ручного
 *     копания в CompoundTag).</li>
 *     <li>{@code networkSynchronized(ByteBufCodecs.STRING_UTF8)} - НЕОБХОДИМОЕ условие из
 *     требований: значение автоматически едет с сервера на клиент при любой синхронизации
 *     стака (взятие в руки, обновление инвентаря и т.д.) штатным ванильным механизмом.
 *     Клиент никогда не может "подделать" значение локально - он получает ровно то, что
 *     записал сервер; write-путь есть только на сервере (там, где вы реально применяете
 *     скин, по аналогии с вашим текущим ApplySkinPayload/TACZSkinHelper.applySkinSafely).</li>
 * </ul>
 */
public class SkinComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MCPSkins.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> SKIN_ID =
            DATA_COMPONENTS.register("skin_id", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    private SkinComponents() {
    }
}
