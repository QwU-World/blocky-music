package ru.gora.musicbattle.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/** The same chained speaker turned on its side, with a matching full-model collision shape. */
public final class HorizontalSpeakerBlock extends SpeakerBlock {
    public static final MapCodec<HorizontalSpeakerBlock> CODEC = simpleCodec(HorizontalSpeakerBlock::new);

    public HorizontalSpeakerBlock(Properties properties) {
        super(properties, true);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}
