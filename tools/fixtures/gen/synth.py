"""Writes a synthetic, music-like test signal as 16-bit WAV: chords with harmonics, a bass line, noise-burst drums,
clicks, a sweep, silence and a stereo pan, so every Opus mode and transient path gets exercised. No recorded audio."""
import math, random, struct, sys, wave

def main(path, seconds, rate, channels, seed):
    rng = random.Random(seed)
    n = int(seconds * rate)
    out = bytearray()
    notes = [261.63, 329.63, 392.00, 523.25, 293.66, 349.23, 440.00, 587.33]
    phase = [0.0] * 8
    for i in range(n):
        t = i / rate
        beat = t * 2.0  # 120 bpm
        bar = int(beat) % 8
        chord = [notes[bar], notes[(bar + 2) % 8], notes[(bar + 4) % 8]]
        s = 0.0
        for k, f in enumerate(chord):
            s += 0.12 * math.sin(2 * math.pi * f * t) + 0.04 * math.sin(2 * math.pi * 2 * f * t + k)
        s += 0.18 * math.sin(2 * math.pi * (notes[bar] / 4) * t)
        frac = beat - int(beat)
        if frac < 0.08:
            s += (rng.random() * 2 - 1) * 0.5 * (1 - frac / 0.08)
        if int(t * 7) % 13 == 0 and (i % 4410) < 3:
            s += 0.8
        if 6.0 < t % 12 < 7.0:
            f0 = 100 * (200 ** ((t % 12) - 6.0))
            s = 0.4 * math.sin(2 * math.pi * f0 * t)
        if 9.0 < t % 12 < 9.25:
            s = 0.0
        s = max(-1.0, min(1.0, s))
        if channels == 1:
            out += struct.pack('<h', int(s * 30000))
        else:
            pan = 0.5 + 0.4 * math.sin(2 * math.pi * 0.1 * t)
            for c in range(channels):
                if c == 0:
                    v = s * (1 - pan) * 1.6
                elif c == 1:
                    v = s * pan * 1.6
                else:
                    v = 0.5 * math.sin(2 * math.pi * (110 * (c + 1)) * t) * (0.5 + 0.5 * math.sin(t * c))
                v = max(-1.0, min(1.0, v))
                out += struct.pack('<h', int(v * 30000))
    w = wave.open(path, 'wb')
    w.setnchannels(channels)
    w.setsampwidth(2)
    w.setframerate(rate)
    w.writeframes(bytes(out))
    w.close()

if __name__ == '__main__':
    main(sys.argv[1], float(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]))
