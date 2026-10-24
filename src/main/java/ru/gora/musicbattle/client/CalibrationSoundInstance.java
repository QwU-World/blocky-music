package ru.gora.musicbattle.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.SampledFloat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.CalibrationPulse;

@EventBusSubscriber(modid = MusicBattleMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class CalibrationSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private static final SampledFloat ONE = random -> 1.0F;
    private final int audioOffsetMs;
    private volatile boolean stopped;
    private volatile long startedNanos;

    public CalibrationSoundInstance(int audioOffsetMs) {
        super(ResourceLocation.fromNamespaceAndPath(MusicBattleMod.MOD_ID, "calibration_click"),
                SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.audioOffsetMs = audioOffsetMs;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = ClientBattleSettings.musicVolume();
        this.pitch = 1.0F;
        this.sound = new Sound(this.location, ONE, ONE, 1, Sound.Type.FILE, true, false, 16);
    }

    @SubscribeEvent
    public static void onStreamingStarted(PlayStreamingSourceEvent event) {
        if (event.getSound() instanceof CalibrationSoundInstance calibration) {
            if (calibration.stopped) event.getChannel().stop();
            else calibration.startedNanos = System.nanoTime();
        }
    }

    public double elapsedMs() {
        long started = startedNanos;
        return stopped || started == 0 ? -1.0 : (System.nanoTime() - started) / 1_000_000.0;
    }

    public void requestStop() { stopped = true; }
    @Override public boolean isStopped() { return stopped; }
    @Override public void tick() { }
    @Override public boolean canStartSilent() { return true; }
    @Override public WeighedSoundEvents resolve(SoundManager manager) { return new WeighedSoundEvents(location, null); }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new LowLatencyAudioStream(new AudioStream() {
            private long frame;
            @Override public AudioFormat getFormat() {
                return new AudioFormat(CalibrationPulse.SAMPLE_RATE, 16, 1, true, false);
            }
            @Override public ByteBuffer read(int size) {
                if (stopped) return ByteBuffer.allocateDirect(0);
                ByteBuffer pcm = ByteBuffer.allocateDirect(Math.max(2, size & ~1)).order(ByteOrder.LITTLE_ENDIAN);
                while (pcm.remaining() >= 2) pcm.putShort(CalibrationPulse.sample(frame++, audioOffsetMs));
                return pcm.flip();
            }
            @Override public void close() { stopped = true; }
        }));
    }
}
