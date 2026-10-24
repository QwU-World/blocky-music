package ru.gora.musicbattle.client;

import java.nio.ByteBuffer;

/** Expands a fresh shell buffer in render space without changing model pivots or other attributes. */
public final class GlowMeshExpansion {
    private GlowMeshExpansion() {
    }

    /** Position is three floats, normal three normalized signed bytes (NEW_ENTITY). */
    public static void expand(ByteBuffer vertices, int vertexCount, int stride,
                              int positionOffset, int normalOffset, float distance) {
        if (!Float.isFinite(distance) || distance <= 0.0F) return;
        prepare(vertices, vertexCount, stride, positionOffset, normalOffset).apply(vertices, distance);
    }

    /** Prepared only for this capture: never retained across entities, poses, or frames. */
    public static Prepared prepare(ByteBuffer vertices, int vertexCount, int stride,
                                   int positionOffset, int normalOffset) {
        if (vertexCount <= 0 || stride <= 0 || positionOffset < 0 || normalOffset < 0
                || (long) positionOffset + 12 > stride || (long) normalOffset + 3 > stride
                || (long) vertexCount * stride > vertices.remaining()) return Prepared.EMPTY;
        ByteBuffer buffer = vertices.duplicate().order(vertices.order());
        int start = buffer.position();
        float[] positions = new float[vertexCount * 3];
        double[] directions = new double[vertexCount * 3];
        int[] groups = new int[vertexCount];
        int[] nextNormal = new int[vertexCount];
        int capacity = 1;
        while (capacity < vertexCount * 2L) capacity <<= 1;
        int[] slots = new int[capacity];
        for (int i = 0; i < vertexCount; i++) {
            int base = start + i * stride;
            float x = buffer.getFloat(base + positionOffset);
            float y = buffer.getFloat(base + positionOffset + 4);
            float z = buffer.getFloat(base + positionOffset + 8);
            positions[i * 3] = x;
            positions[i * 3 + 1] = y;
            positions[i * 3 + 2] = z;
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) continue;
            int nx = buffer.get(base + normalOffset);
            int ny = buffer.get(base + normalOffset + 1);
            int nz = buffer.get(base + normalOffset + 2);
            if (nx == 0 && ny == 0 && nz == 0) continue;
            int slot = hash(bits(x), bits(y), bits(z)) & (capacity - 1);
            while (slots[slot] != 0) {
                int position = (slots[slot] - 1) * 3;
                if (positions[position] == x && positions[position + 1] == y && positions[position + 2] == z) break;
                slot = (slot + 1) & (capacity - 1);
            }
            if (slots[slot] == 0) slots[slot] = i + 1;
            int group = slots[slot] - 1;
            groups[i] = group + 1;
            // Exact-coordinate weld and unique normals, as before, but without a Position,
            // HashMap entry, Normals, List and boxed direction for each face vertex.
            boolean duplicate = false;
            if (group != i) {
                for (int entry = group + 1; entry != 0; entry = nextNormal[entry - 1]) {
                    int normal = start + (entry - 1) * stride + normalOffset;
                    if (buffer.get(normal) == nx && buffer.get(normal + 1) == ny && buffer.get(normal + 2) == nz) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    nextNormal[i] = nextNormal[group];
                    nextNormal[group] = i + 1;
                }
            }
            if (!duplicate) {
                double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                directions[group * 3] += nx / length;
                directions[group * 3 + 1] += ny / length;
                directions[group * 3 + 2] += nz / length;
            }
        }
        for (int i = 0; i < vertexCount; i++) {
            if (groups[i] != i + 1) continue;
            int index = i * 3;
            double nx = directions[index], ny = directions[index + 1], nz = directions[index + 2];
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            directions[index] = length < 1.0E-6 ? 0 : nx / length;
            directions[index + 1] = length < 1.0E-6 ? 0 : ny / length;
            directions[index + 2] = length < 1.0E-6 ? 0 : nz / length;
        }
        return new Prepared(buffer, vertexCount, stride, positionOffset, normalOffset, positions, directions, groups);
    }

    private static int bits(float value) {
        return value == 0.0F ? 0 : Float.floatToIntBits(value);
    }

    private static int hash(int x, int y, int z) {
        int hash = x * 0x8DA6B343 ^ y * 0xD8163841 ^ z * 0xCB1AB31F;
        hash ^= hash >>> 16;
        return hash;
    }

    public static final class Prepared {
        private static final Prepared EMPTY = new Prepared(null, 0, 0, 0, 0, null, null, null);
        private final ByteBuffer source;
        private final int vertexCount, stride, positionOffset, normalOffset;
        private final float[] positions;
        private final double[] directions;
        private final int[] groups;

        private Prepared(ByteBuffer source, int vertexCount, int stride, int positionOffset, int normalOffset,
                         float[] positions, double[] directions, int[] groups) {
            this.source = source;
            this.vertexCount = vertexCount;
            this.stride = stride;
            this.positionOffset = positionOffset;
            this.normalOffset = normalOffset;
            this.positions = positions;
            this.directions = directions;
            this.groups = groups;
        }

        /** Each radius starts at captured positions, never at the preceding shell. */
        public void apply(ByteBuffer target, float distance) {
            if (!Float.isFinite(distance) || distance < 0 || vertexCount == 0
                    || (long) vertexCount * stride > target.remaining()) return;
            ByteBuffer buffer = target.duplicate().order(target.order());
            for (int i = 0; i < vertexCount; i++) {
                if (groups[i] == 0) continue;
                int direction = (groups[i] - 1) * 3;
                double nx = directions[direction], ny = directions[direction + 1], nz = directions[direction + 2];
                if (nx == 0 && ny == 0 && nz == 0) {
                    // Opposing normals of a zero-thickness cape keep their own outward sides.
                    int normal = source.position() + i * stride + normalOffset;
                    nx = source.get(normal);
                    ny = source.get(normal + 1);
                    nz = source.get(normal + 2);
                    double inverseLength = 1.0 / Math.sqrt(nx * nx + ny * ny + nz * nz);
                    nx *= inverseLength;
                    ny *= inverseLength;
                    nz *= inverseLength;
                }
                int position = buffer.position() + i * stride + positionOffset;
                buffer.putFloat(position, (float) (positions[i * 3] + nx * distance));
                buffer.putFloat(position + 4, (float) (positions[i * 3 + 1] + ny * distance));
                buffer.putFloat(position + 8, (float) (positions[i * 3 + 2] + nz * distance));
            }
        }
    }
}
