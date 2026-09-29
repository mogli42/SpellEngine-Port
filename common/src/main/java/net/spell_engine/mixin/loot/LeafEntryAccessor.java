package net.spell_engine.mixin.loot;

import net.minecraft.world.level.storage.loot.entries.UniformContainerBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/// 26.3: weighted leaf entries (items, tags, table references) share `UniformContainerBase`
/// (formerly `LootPoolSingletonContainer`); their functions moved up to `LootPoolEntryContainer#modifier`.
@Mixin(UniformContainerBase.class)
public interface LeafEntryAccessor {
    @Accessor("weight")
    int spellEngine_getWeight();
}
