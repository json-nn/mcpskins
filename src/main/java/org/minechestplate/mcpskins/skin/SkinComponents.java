package org.minechestplate.mcpskins.skin;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * Registers the custom {@code mcpskins:skin_id} data component, which stores the
 * currently applied skin ID on an item stack. It's persistent (saved to NBT) and
 * network-synchronized, so the value always comes from the server and can't be
 * spoofed by the client.
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
