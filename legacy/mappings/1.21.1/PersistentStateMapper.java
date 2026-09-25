package mtr.mappings;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.function.Supplier;

public abstract class PersistentStateMapper extends SavedData {

	public PersistentStateMapper(String name) {
		super();
	}

	public abstract void load(CompoundTag compoundTag);

	@Override
	public abstract CompoundTag save(CompoundTag compoundTag, HolderLookup.Provider provider);

	protected static <T extends PersistentStateMapper> T getInstance(Level world, Supplier<T> supplier, String name) {
		if (world instanceof ServerLevel) {
			return ((ServerLevel) world).getDataStorage().computeIfAbsent(new SavedData.Factory<>(supplier, nbtCompound -> {
				final T railwayData = supplier.get();
				railwayData.load(nbtCompound);
				return railwayData;
			}, DataFixTypes.SAVED_DATA_COMMAND_STORAGE), name);
		} else {
			return null;
		}
	}
}
