"""Silences part of a 16-bit WAV in place of a copy: python3 -I silence.py in.wav out.wav from_s to_s
(made for the DTX fixture, so the encoder has a stretch of digital silence to stop sending audio in)."""
import sys, wave
src = wave.open(sys.argv[1], 'rb')
params = src.getparams()
data = bytearray(src.readframes(params.nframes))
src.close()
frame = params.sampwidth * params.nchannels
a, b = int(float(sys.argv[3]) * params.framerate), int(float(sys.argv[4]) * params.framerate)
data[a * frame:b * frame] = bytes((b - a) * frame)
out = wave.open(sys.argv[2], 'wb')
out.setparams(params)
out.writeframes(bytes(data))
out.close()
