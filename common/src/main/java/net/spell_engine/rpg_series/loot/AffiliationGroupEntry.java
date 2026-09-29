package net.spell_engine.rpg_series.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.ValidationContext;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntries;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntry;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.spell_engine.SpellEngineMod;
import net.spell_engine.mixin.loot.ItemEntryAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/// Offers all of its children (like vanilla `group`), but shifts weight towards the item entries
/// affiliated with the class of the looting player, see {@link ClassAffiliation}.
///
/// The total weight of the group is preserved, so its share within the pool stays the same.
/// When affiliated, weights are multiplied by {@link #WEIGHT_SCALE} for precision. The affiliation
/// depends on the loot context only, so every group of a pool scales (or not) together -
/// a pool using this entry type should consist of this entry type only.
public class AffiliationGroupEntry extends LootPoolEntryContainer {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(SpellEngineMod.ID, "affiliation_group");
    public static final int WEIGHT_SCALE = 100;

    private static final Codec<LootConfig.Behavior.WeightOperation> OPERATION_CODEC = Codec.STRING.xmap(
            name -> LootConfig.Behavior.WeightOperation.valueOf(name.toUpperCase(Locale.ROOT)),
            operation -> operation.name().toLowerCase(Locale.ROOT));

    /// Registered into `BuiltInRegistries.LOOT_POOL_ENTRY_TYPE` (entry types are their codecs since 1.21.2)
    public static final MapCodec<AffiliationGroupEntry> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                            LootPoolEntries.CODEC.listOf().optionalFieldOf("children", List.of()).forGetter(entry -> entry.children),
                            Codec.FLOAT.optionalFieldOf("extra_weight", 1F).forGetter(entry -> entry.extraWeight),
                            OPERATION_CODEC.optionalFieldOf("operation", LootConfig.Behavior.WeightOperation.MULTIPLY).forGetter(entry -> entry.operation),
                            Codec.BOOL.optionalFieldOf("include_team", true).forGetter(entry -> entry.includeTeam)
                    )
                    .and(commonFields(instance))
                    .apply(instance, AffiliationGroupEntry::new)
    );

    private final List<LootPoolEntryContainer> children;
    private final float extraWeight;
    private final LootConfig.Behavior.WeightOperation operation;
    private final boolean includeTeam;

    private AffiliationGroupEntry(List<LootPoolEntryContainer> children, float extraWeight, LootConfig.Behavior.WeightOperation operation,
                                  boolean includeTeam, Optional<Holder<LootItemCondition>> condition,
                                  Optional<Holder<LootItemFunction>> modifier) {
        super(condition, modifier);
        this.children = children;
        this.extraWeight = Math.max(extraWeight, 0);
        this.operation = operation;
        this.includeTeam = includeTeam;
    }

    public List<LootPoolEntryContainer> children() {
        return children;
    }

    @Override
    public MapCodec<AffiliationGroupEntry> codec() {
        return CODEC;
    }

    @Override
    public void validate(ValidationContext context) {
        super.validate(context);
        if (this.children.isEmpty()) {
            context.reportProblem(CompositeEntryBase.NO_CHILDREN_PROBLEM);
        }
        for (int i = 0; i < this.children.size(); i++) {
            this.children.get(i).validate(context.forIndexedField("children", i));
        }
    }

    /// Conditions and the modifier function are applied by `LootPoolEntryContainer#expand`.
    @Override
    protected boolean expandRaw(LootContext context, Consumer<LootPoolEntry> output) {
        var affiliation = ClassAffiliation.resolve(context, includeTeam);
        if (affiliation.isEmpty()) {
            // Affiliation cannot be determined, configured weights as is
            for (var child: children) {
                child.expand(context, output);
            }
            return true;
        }

        var luck = context.getLuck();
        var choices = new ArrayList<WeightedChoice>();
        float plainTotal = 0;
        float shiftedTotal = 0;
        for (var child: children) {
            var affiliated = isAffiliated(child, affiliation);
            var start = choices.size();
            child.expand(context, choice -> {
                float weight = choice.getWeight(luck);
                var shifted = (affiliated && weight > 0) ? operation.apply(weight, extraWeight) : weight;
                choices.add(new WeightedChoice(choice, weight, shifted));
            });
            for (int i = start; i < choices.size(); i++) {
                var choice = choices.get(i);
                plainTotal += choice.plainWeight;
                shiftedTotal += choice.weight;
            }
        }
        if (shiftedTotal <= 0) {
            return true;
        }
        var normalize = (plainTotal / shiftedTotal) * WEIGHT_SCALE;
        for (var choice: choices) {
            if (choice.weight <= 0) { continue; }
            var weight = Math.max(1, Math.round(choice.weight * normalize));
            output.accept(new LootPoolEntry() {
                @Override
                public int getWeight(float luck) {
                    return weight;
                }
                @Override
                public void createItemStack(Consumer<ItemStack> lootConsumer, LootContext context) {
                    choice.choice.createItemStack(lootConsumer, context);
                }
            });
        }
        return true;
    }

    private record WeightedChoice(LootPoolEntry choice, float plainWeight, float weight) { }

    private static boolean isAffiliated(LootPoolEntryContainer entry, Set<TagKey<Item>> affiliation) {
        if (entry instanceof LootItem) {
            var item = ((ItemEntryAccessor) entry).spellEngine_getItem();
            for (var tag: affiliation) {
                if (item.is(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Builder builder(float extraWeight, LootConfig.Behavior.WeightOperation operation, boolean includeTeam) {
        return new Builder(extraWeight, operation, includeTeam);
    }

    public static class Builder extends LootPoolEntryContainer.Builder<Builder> {
        private final List<LootPoolEntryContainer> children = new ArrayList<>();
        private final float extraWeight;
        private final LootConfig.Behavior.WeightOperation operation;
        private final boolean includeTeam;

        private Builder(float extraWeight, LootConfig.Behavior.WeightOperation operation, boolean includeTeam) {
            this.extraWeight = extraWeight;
            this.operation = operation;
            this.includeTeam = includeTeam;
        }

        public Builder with(LootPoolEntryContainer.Builder<?> child) {
            this.children.add(child.build());
            return this;
        }

        public boolean isEmpty() {
            return children.isEmpty();
        }

        @Override
        protected Builder getThis() {
            return this;
        }

        @Override
        public LootPoolEntryContainer build() {
            return new AffiliationGroupEntry(List.copyOf(children), extraWeight, operation, includeTeam,
                    this.getCondition(), this.getModifier());
        }
    }
}
