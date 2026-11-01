package ru.gora.musicbattle.audio.codec;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Development tool: builds Ogg Opus and WebM files from raw Opus packets (the OPKT dumps) with chosen timing
 * metadata and damage, to see what ffmpeg and the Java decoder make of each. Not part of the mod.
 *
 * ContainerLab ogg in.pkts out.opus [key=value...]
 *   preskip=N gain=Q8 perpage=N cropped=N (adds N to every granule) eosgranule=N (overrides the last granule)
 *   noeos emptyeos zeroat=I (packet I becomes 0 bytes) chain=other.pkts muxfirst (a foreign stream's BOS first)
 *   tocat=I (packet I becomes only its TOC byte)
 * ContainerLab webm in.pkts out.webm [key=value...]
 *   preskip=N codecdelay=NS|none discard=NS (on the last block) negdiscard=NS (on the first block)
 *   lacing=none|xiph|ebml|fixed perblock=N unknownsize clusterms=N videofirst dropblock=I blockgroups
 */
public final class ContainerLab {
    static final class Packets {
        int channels;
        int streams;
        int coupled;
        int[] mapping;
        int gain;
        List<byte[]> list = new ArrayList<>();
    }

    public static void main(String[] args) throws IOException {
        Packets p = load(args[1]);
        Map<String, String> o = new HashMap<>();
        for (int i = 3; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            o.put(eq < 0 ? args[i] : args[i].substring(0, eq), eq < 0 ? "" : args[i].substring(eq + 1));
        }
        byte[] out = switch (args[0]) {
            case "ogg" -> ogg(p, o);
            case "webm" -> webm(p, o);
            default -> throw new IllegalArgumentException(args[0]);
        };
        Files.write(Path.of(args[2]), out);
        System.out.println("wrote " + args[2] + " " + out.length + " bytes, " + p.list.size() + " packets");
    }

    static Packets load(String path) throws IOException {
        Packets p = new Packets();
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(path)))) {
            byte[] h = new byte[7];
            in.readFully(h);
            p.channels = h[4] & 0xFF;
            p.streams = h[5] & 0xFF;
            p.coupled = h[6] & 0xFF;
            p.mapping = new int[p.channels];
            for (int c = 0; c < p.channels; c++) p.mapping[c] = in.readUnsignedByte();
            p.gain = (short) (in.readUnsignedByte() | in.readUnsignedByte() << 8);
            while (true) {
                int b0 = in.read();
                if (b0 < 0) break;
                int len = b0 | in.readUnsignedByte() << 8 | in.readUnsignedByte() << 16 | in.readUnsignedByte() << 24;
                byte[] d = new byte[len];
                in.readFully(d);
                p.list.add(d);
            }
        }
        return p;
    }

    static byte[] opusHead(Packets p, int preskip, int gain) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes("OpusHead".getBytes(StandardCharsets.US_ASCII));
        b.write(1);
        b.write(p.channels);
        b.write(preskip & 0xFF);
        b.write(preskip >> 8);
        b.writeBytes(new byte[]{(byte) 0x80, (byte) 0xBB, 0, 0});
        b.write(gain & 0xFF);
        b.write((gain >> 8) & 0xFF);
        boolean family1 = p.channels > 2 || p.streams > 1;
        b.write(family1 ? 1 : 0);
        if (family1) {
            b.write(p.streams);
            b.write(p.coupled);
            for (int m : p.mapping) b.write(m);
        }
        return b.toByteArray();
    }

    static byte[] opusTags() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes("OpusTags".getBytes(StandardCharsets.US_ASCII));
        byte[] vendor = "ContainerLab".getBytes(StandardCharsets.US_ASCII);
        b.writeBytes(new byte[]{(byte) vendor.length, 0, 0, 0});
        b.writeBytes(vendor);
        b.writeBytes(new byte[]{0, 0, 0, 0});
        return b.toByteArray();
    }

    // ---- Ogg

    static final class OggWriter {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int serial;
        int sequence;

        OggWriter(int serial) {
            this.serial = serial;
        }

        /** Writes one page holding whole packets (each must fit), with the given flags and granule. */
        void page(List<byte[]> packets, int flags, long granule, boolean lastContinues) {
            ByteArrayOutputStream lacing = new ByteArrayOutputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (int k = 0; k < packets.size(); k++) {
                byte[] pk = packets.get(k);
                int len = pk.length;
                while (len >= 255) {
                    lacing.write(255);
                    len -= 255;
                }
                if (!(lastContinues && k == packets.size() - 1)) lacing.write(len);
                body.writeBytes(pk);
            }
            byte[] lac = lacing.toByteArray();
            if (lac.length > 255) throw new IllegalStateException("page too big");
            byte[] page = new byte[27 + lac.length + body.size()];
            System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII), 0, page, 0, 4);
            page[5] = (byte) flags;
            for (int i = 0; i < 8; i++) page[6 + i] = (byte) (granule >> (8 * i));
            for (int i = 0; i < 4; i++) page[14 + i] = (byte) (serial >> (8 * i));
            for (int i = 0; i < 4; i++) page[18 + i] = (byte) (sequence >> (8 * i));
            page[26] = (byte) lac.length;
            System.arraycopy(lac, 0, page, 27, lac.length);
            System.arraycopy(body.toByteArray(), 0, page, 27 + lac.length, body.size());
            int crc = OpusDecoder.OggOpusPackets.crc(0, page, 0, page.length);
            for (int i = 0; i < 4; i++) page[22 + i] = (byte) (crc >> (8 * i));
            out.writeBytes(page);
            sequence++;
        }
    }

    static byte[] ogg(Packets p, Map<String, String> o) throws IOException {
        int preskip = Integer.parseInt(o.getOrDefault("preskip", "312"));
        int gain = Integer.parseInt(o.getOrDefault("gain", "0"));
        int perPage = Integer.parseInt(o.getOrDefault("perpage", "10"));
        long cropped = Long.parseLong(o.getOrDefault("cropped", "0"));
        List<byte[]> packets = new ArrayList<>(p.list);
        if (o.containsKey("zeroat")) packets.set(Integer.parseInt(o.get("zeroat")), new byte[0]);
        if (o.containsKey("tocat")) {
            int i = Integer.parseInt(o.get("tocat"));
            packets.set(i, new byte[]{(byte) (packets.get(i)[0] & 0xFC)});
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        OggWriter w = new OggWriter(0x1234);
        OggWriter foreign = new OggWriter(0x7777);
        if (o.containsKey("muxfirst")) {
            // A foreign logical stream's beginning page first, as a skeleton or video track would put it.
            foreign.page(List.of("XYZUnknownStreamHeader-0123456789".getBytes(StandardCharsets.ISO_8859_1)), 2, 0, false);
            file.writeBytes(foreign.out.toByteArray());
            foreign.out.reset();
        }
        w.page(List.of(opusHead(p, preskip, gain)), 2, 0, false);
        file.writeBytes(w.out.toByteArray());
        w.out.reset();
        if (o.containsKey("muxfirst")) {
            foreign.page(List.of(new byte[0]), 4, 0, false);
            file.writeBytes(foreign.out.toByteArray());
            foreign.out.reset();
        }
        w.page(List.of(opusTags()), 0, 0, false);
        long granule = cropped;
        int last = packets.size();
        for (int i = 0; i < last; i += perPage) {
            List<byte[]> page = packets.subList(i, Math.min(last, i + perPage));
            for (byte[] pk : page) {
                int d = pk.length == 0 ? 960 : OpusPacketDecoder.packetSamples(pk, 0, pk.length);
                granule += d;
            }
            boolean end = i + perPage >= last;
            long g = granule;
            if (end && o.containsKey("eosgranule") && !o.containsKey("emptyeos")) g = Long.parseLong(o.get("eosgranule"));
            int flags = end && !o.containsKey("noeos") && !o.containsKey("emptyeos") ? 4 : 0;
            w.page(page, flags, g, false);
        }
        if (o.containsKey("emptyeos")) {
            long g = o.containsKey("eosgranule") ? Long.parseLong(o.get("eosgranule")) : granule;
            w.page(List.of(), 4, g, false);
        }
        file.writeBytes(w.out.toByteArray());
        if (o.containsKey("chain")) {
            Packets q = load(o.get("chain"));
            OggWriter w2 = new OggWriter(0x5678);
            w2.page(List.of(opusHead(q, preskip, 0)), 2, 0, false);
            w2.page(List.of(opusTags()), 0, 0, false);
            long g2 = 0;
            for (int i = 0; i < q.list.size(); i += perPage) {
                List<byte[]> page = q.list.subList(i, Math.min(q.list.size(), i + perPage));
                for (byte[] pk : page) g2 += OpusPacketDecoder.packetSamples(pk, 0, pk.length);
                w2.page(page, i + perPage >= q.list.size() ? 4 : 0, g2, false);
            }
            file.writeBytes(w2.out.toByteArray());
        }
        return file.toByteArray();
    }

    // ---- WebM

    static byte[] id(int id) {
        if (id >= 0x1000000) return new byte[]{(byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id};
        if (id >= 0x10000) return new byte[]{(byte) (id >> 16), (byte) (id >> 8), (byte) id};
        if (id >= 0x100) return new byte[]{(byte) (id >> 8), (byte) id};
        return new byte[]{(byte) id};
    }

    static byte[] size(long size) {
        // Always 8 bytes: simple and valid.
        byte[] b = new byte[8];
        b[0] = 0x01;
        for (int i = 1; i < 8; i++) b[i] = (byte) (size >> (8 * (7 - i)));
        return b;
    }

    static final byte[] UNKNOWN_SIZE = {0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    static byte[] el(int idv, byte[]... children) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] c : children) body.writeBytes(c);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(id(idv));
        b.writeBytes(size(body.size()));
        b.writeBytes(body.toByteArray());
        return b.toByteArray();
    }

    static byte[] uint(int idv, long v) {
        int n = 1;
        while (n < 8 && (v >>> (8 * n)) != 0) n++;
        byte[] d = new byte[n];
        for (int i = 0; i < n; i++) d[i] = (byte) (v >> (8 * (n - 1 - i)));
        return el(idv, d);
    }

    static byte[] sint(int idv, long v) {
        byte[] d = new byte[8];
        for (int i = 0; i < 8; i++) d[i] = (byte) (v >> (8 * (7 - i)));
        return el(idv, d);
    }

    static byte[] str(int idv, String s) {
        return el(idv, s.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] block(int track, int timecode, int flags, List<byte[]> frames, String lacing) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x80 | track);
        b.write((timecode >> 8) & 0xFF);
        b.write(timecode & 0xFF);
        int lace = switch (lacing) {
            case "xiph" -> 1;
            case "fixed" -> 2;
            case "ebml" -> 3;
            default -> 0;
        };
        if (frames.size() > 1 && lace == 0) throw new IllegalArgumentException("several frames need lacing");
        b.write(flags | (frames.size() > 1 || lace != 0 ? lace << 1 : 0));
        if (frames.size() > 1 || lace != 0) {
            b.write(frames.size() - 1);
            if (lace == 1) {
                for (int i = 0; i < frames.size() - 1; i++) {
                    int s = frames.get(i).length;
                    while (s >= 255) {
                        b.write(255);
                        s -= 255;
                    }
                    b.write(s);
                }
            } else if (lace == 3) {
                long prev = 0;
                for (int i = 0; i < frames.size() - 1; i++) {
                    long s = frames.get(i).length;
                    if (i == 0) {
                        b.write(0x40 | (int) (s >> 8));
                        b.write((int) s);
                    } else {
                        long diff = s - prev + 8191;
                        b.write(0x40 | (int) (diff >> 8));
                        b.write((int) diff);
                    }
                    prev = s;
                }
            }
        }
        for (byte[] f : frames) b.writeBytes(f);
        return b.toByteArray();
    }

    static byte[] webm(Packets p, Map<String, String> o) {
        int preskip = Integer.parseInt(o.getOrDefault("preskip", "312"));
        String cd = o.getOrDefault("codecdelay", Long.toString(preskip * 1_000_000_000L / 48000));
        String lacing = o.getOrDefault("lacing", "none");
        int perBlock = Integer.parseInt(o.getOrDefault("perblock", lacing.equals("none") ? "1" : "3"));
        int clusterMs = Integer.parseInt(o.getOrDefault("clusterms", "5000"));
        boolean unknown = o.containsKey("unknownsize");
        boolean groups = o.containsKey("blockgroups");
        int drop = Integer.parseInt(o.getOrDefault("dropblock", "-1"));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(el(0x1A45DFA3, uint(0x4286, 1), uint(0x42F7, 1), uint(0x42F2, 4), uint(0x42F3, 8),
                str(0x4282, "webm"), uint(0x4287, 4), uint(0x4285, 2)));
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        seg.writeBytes(el(0x1549A966, uint(0x2AD7B1, 1_000_000), str(0x4D80, "ContainerLab")));
        List<byte[]> trackEntries = new ArrayList<>();
        int audioTrack = 1;
        if (o.containsKey("videofirst")) {
            trackEntries.add(el(0xAE, uint(0xD7, 1), uint(0x73C5, 11), uint(0x83, 1), str(0x86, "V_VP9"),
                    el(0xE0, uint(0xB0, 64), uint(0xBA, 64))));
            audioTrack = 2;
        }
        List<byte[]> audio = new ArrayList<>(List.of(uint(0xD7, audioTrack), uint(0x73C5, 22), uint(0x83, 2),
                str(0x86, "A_OPUS")));
        if (!cd.equals("none")) audio.add(uint(0x56AA, Long.parseLong(cd)));
        audio.add(uint(0x56BB, 80_000_000));
        audio.add(el(0x63A2, opusHead(p, preskip, p.gain)));
        audio.add(el(0xE1, el(0xB5, new byte[]{0x40, (byte) 0xE7, 0x70, 0, 0, 0, 0, 0}), uint(0x9F, p.channels)));
        trackEntries.add(el(0xAE, audio.toArray(new byte[0][])));
        seg.writeBytes(el(0x1654AE6B, trackEntries.toArray(new byte[0][])));
        // Clusters.
        long timeMs = 0;
        long samples = 0;
        int i = 0;
        int blockIndex = 0;
        List<byte[]> list = p.list;
        while (i < list.size()) {
            long clusterStart = timeMs;
            ByteArrayOutputStream cl = new ByteArrayOutputStream();
            cl.writeBytes(uint(0xE7, clusterStart));
            while (i < list.size() && timeMs - clusterStart < clusterMs) {
                List<byte[]> frames = list.subList(i, Math.min(list.size(), i + perBlock));
                int rel = (int) (timeMs - clusterStart);
                int d = 0;
                for (byte[] f : frames) d += f.length == 0 ? 960 : OpusPacketDecoder.packetSamples(f, 0, f.length);
                boolean first = i == 0;
                boolean lastBlock = i + perBlock >= list.size();
                if (o.containsKey("videofirst") && blockIndex % 2 == 0) {
                    cl.writeBytes(el(0xA3, block(1, rel, 0x80, List.of(new byte[300]), "none")));
                }
                if (blockIndex != drop) {
                    if (groups || (lastBlock && o.containsKey("discard")) || (first && o.containsKey("negdiscard"))) {
                        List<byte[]> g = new ArrayList<>();
                        g.add(el(0xA1, block(audioTrack, rel, 0, frames, lacing)));
                        if (lastBlock && o.containsKey("discard")) g.add(sint(0x75A2, Long.parseLong(o.get("discard"))));
                        if (first && o.containsKey("negdiscard")) g.add(sint(0x75A2, -Long.parseLong(o.get("negdiscard"))));
                        cl.writeBytes(el(0xA0, g.toArray(new byte[0][])));
                    } else {
                        cl.writeBytes(el(0xA3, block(audioTrack, rel, 0x80, frames, lacing)));
                    }
                }
                samples += d;
                timeMs = samples / 48;
                i += perBlock;
                blockIndex++;
            }
            if (unknown) {
                seg.writeBytes(id(0x1F43B675));
                seg.writeBytes(UNKNOWN_SIZE);
                seg.writeBytes(cl.toByteArray());
            } else {
                seg.writeBytes(el(0x1F43B675, cl.toByteArray()));
            }
        }
        file.writeBytes(id(0x18538067));
        file.writeBytes(unknown ? UNKNOWN_SIZE : size(seg.size()));
        file.writeBytes(seg.toByteArray());
        return file.toByteArray();
    }
}
