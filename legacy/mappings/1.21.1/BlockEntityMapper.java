package mtr.mappings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public abstract class BlockEntityMapper extends BlockEntity {

	public BlockEntityMapper(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	protected final void loadAdditional(CompoundTag compoundTag, HolderLookup.Provider provider) {
		super.loadAdditional(compoundTag, provider);
		readCompoundTag(compoundTag);
	}

	@Override
	protected final void saveAdditional(CompoundTag compoundTag, HolderLookup.Provider provider) {
		super.saveAdditional(compoundTag, provider);
		writeCompoundTag(compoundTag);
	}

	public void readCompoundTag(CompoundTag compoundTag) {
	}

	public void writeCompoundTag(CompoundTag compoundTag) {
	}
}
