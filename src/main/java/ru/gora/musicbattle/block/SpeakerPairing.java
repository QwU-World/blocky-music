package ru.gora.musicbattle.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Coordinate rules shared by placement, interaction routing and legacy repair. */
public final class SpeakerPairing {
    private SpeakerPairing() {
    }

    public static Direction extensionDirection(boolean horizontal, Direction facing) {
        return horizontal ? facing.getClockWise() : Direction.UP;
    }

    public static BlockPos extensionPos(boolean horizontal, Direction facing, BlockPos primaryPos) {
        return primaryPos.relative(extensionDirection(horizontal, facing));
    }

    public static BlockPos primaryPos(boolean horizontal, boolean extension, Direction facing, BlockPos pos) {
        return extension ? pos.relative(extensionDirection(horizontal, facing).getOpposite()) : pos;
    }
}
