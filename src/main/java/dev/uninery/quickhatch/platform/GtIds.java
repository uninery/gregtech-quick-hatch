package dev.uninery.quickhatch.platform;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * MachineDefinition ↔ ResourceLocation 映射。
 * GTRegistry 没有直接的 def→id 查询，这里用注册表 entries 惰性建立双向映射。
 */
public final class GtIds {

    private static volatile Map<MachineDefinition, ResourceLocation> defToId;

    private GtIds() {}

    private static void buildIfNeeded() {
        if (defToId == null) {
            synchronized (GtIds.class) {
                if (defToId == null) {
                    Map<MachineDefinition, ResourceLocation> d2i = new HashMap<>();
                    for (Map.Entry<ResourceLocation, MachineDefinition> e : GTRegistries.MACHINES.entries()) {
                        d2i.put(e.getValue(), e.getKey());
                    }
                    defToId = d2i;
                }
            }
        }
    }

    public static ResourceLocation id(MachineDefinition def) {
        buildIfNeeded();
        return defToId.get(def);
    }
}
