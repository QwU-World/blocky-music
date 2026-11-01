/* Encodes a 48 kHz 16-bit WAV with the libopus encoder while forcing its mode, bandwidth, frame size and channel
   count to change every few frames, so the packets carry every transition the decoder handles: SILK to CELT and CELT
   to SILK with redundant CELT frames, hybrid to SILK with the CELT fade-out, mono packets in a stereo stream, and
   bandwidth changes. Writes the OPKT packet format of fuzzdec.c.

   modeswitch in.wav out.pkts bitrate */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <opus.h>
#include "opus_private.h"

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: modeswitch in.wav out.pkts bitrate\n"); return 2; }
    FILE *in = fopen(argv[1], "rb"), *out = fopen(argv[2], "wb");
    unsigned char hdr[44];
    if (!in || !out || fread(hdr, 1, 44, in) != 44) { fprintf(stderr, "bad input\n"); return 1; }
    int channels = hdr[22];
    int err;
    OpusEncoder *enc = opus_encoder_create(48000, channels, OPUS_APPLICATION_AUDIO, &err);
    if (!enc) { fprintf(stderr, "encoder: %d\n", err); return 1; }
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(atoi(argv[3])));
    /* OPKT header: channels, one stream (coupled when stereo), the identity mapping and no gain. */
    unsigned char ohdr[11] = {'O', 'P', 'K', 'T', (unsigned char)channels, 1, (unsigned char)(channels - 1), 0, 1, 0, 0};
    if (channels == 1) ohdr[8] = 0;
    fwrite(ohdr, 1, 7 + channels + 2, out);
    /* Mode, bandwidth, frame size in samples, forced channels; cycled every 4 frames. */
    static const int plan[][4] = {
        {MODE_SILK_ONLY, OPUS_BANDWIDTH_NARROWBAND, 960, 0}, {MODE_CELT_ONLY, OPUS_BANDWIDTH_FULLBAND, 960, 0},
        {MODE_SILK_ONLY, OPUS_BANDWIDTH_WIDEBAND, 480, 0}, {MODE_HYBRID, OPUS_BANDWIDTH_SUPERWIDEBAND, 960, 0},
        {MODE_SILK_ONLY, OPUS_BANDWIDTH_MEDIUMBAND, 960, 1}, {MODE_CELT_ONLY, OPUS_BANDWIDTH_WIDEBAND, 480, 0},
        {MODE_HYBRID, OPUS_BANDWIDTH_FULLBAND, 480, 0}, {MODE_CELT_ONLY, OPUS_BANDWIDTH_FULLBAND, 240, 0},
        {MODE_SILK_ONLY, OPUS_BANDWIDTH_WIDEBAND, 1920, 0}, {MODE_CELT_ONLY, OPUS_BANDWIDTH_NARROWBAND, 120, 1},
        {MODE_HYBRID, OPUS_BANDWIDTH_SUPERWIDEBAND, 960, 1}, {MODE_SILK_ONLY, OPUS_BANDWIDTH_NARROWBAND, 2880, 0},
    };
    int nplan = sizeof plan / sizeof plan[0];
    short pcm[2880 * 2];
    unsigned char packet[1500];
    long frames = 0, packets = 0;
    for (int step = 0; ; step++) {
        const int *p = plan[(step / 4) % nplan];
        opus_encoder_ctl(enc, OPUS_SET_FORCE_MODE(p[0]));
        opus_encoder_ctl(enc, OPUS_SET_BANDWIDTH(p[1]));
        opus_encoder_ctl(enc, OPUS_SET_FORCE_CHANNELS(p[3] ? 1 : OPUS_AUTO));
        int n = (int)fread(pcm, 2 * channels, p[2], in);
        if (n < p[2]) break;
        int len = opus_encode(enc, pcm, p[2], packet, sizeof packet);
        if (len < 0) { fprintf(stderr, "encode: %d\n", len); return 1; }
        unsigned char lb[4] = {(unsigned char)len, (unsigned char)(len >> 8), 0, 0};
        fwrite(lb, 1, 4, out);
        fwrite(packet, 1, len, out);
        frames += n;
        packets++;
    }
    int lookahead = 0;
    opus_encoder_ctl(enc, OPUS_GET_LOOKAHEAD(&lookahead));
    printf("packets=%ld samples=%ld lookahead=%d\n", packets, frames, lookahead);
    fclose(out);
    return 0;
}
