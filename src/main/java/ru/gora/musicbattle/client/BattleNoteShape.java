package ru.gora.musicbattle.client;

/** Filled glyph geometry, shared by note heads, receptors, and hit bursts. */
final class BattleNoteShape {
    private static final float[][] ARROW = {{-1F,0F},{0F,-1F},{1F,0F},{.36F,0F},{.36F,1F},{-.36F,1F},{-.36F,0F}};
    private static final float[][] ARROW_INSET = insetDirections(ARROW);
    private static final float[][] CIRCLE = circle();
    private BattleNoteShape() { }

    @FunctionalInterface
    interface TriangleFill { void triangle(float x1,float y1,float x2,float y2,float x3,float y3,int color); }

    static void drawTriangles(TriangleFill fill, int skin, int lane, float left, float top, float right, float bottom, int color) {
        if (right <= left || bottom <= top) return;
        float centerX = (left + right) * .5F, centerY = (top + bottom) * .5F;
        float halfWidth = (right - left) * .5F, halfHeight = (bottom - top) * .5F;
        if (skin == 0) {
            fill.triangle(left,top,right,top,right,bottom,color);
            fill.triangle(left,top,right,bottom,left,bottom,color);
            return;
        }
        float[][] outline = skin == 2 ? CIRCLE : ARROW;
        for (int point = 0; point < outline.length; point++) {
            float[] a = outline[point], b = outline[(point + 1) % outline.length];
            float ax=a[0],ay=a[1],bx=b[0],by=b[1];
            if (skin == 1) {
                switch (lane) {
                    case 0 -> { ax=a[1]; ay=-a[0]; bx=b[1]; by=-b[0]; }
                    case 1 -> { ax=-a[0]; ay=-a[1]; bx=-b[0]; by=-b[1]; }
                    case 3 -> { ax=-a[1]; ay=a[0]; bx=-b[1]; by=b[0]; }
                    default -> { }
                }
            }
            fill.triangle(centerX,centerY,centerX+ax*halfWidth,centerY+ay*halfHeight,
                    centerX+bx*halfWidth,centerY+by*halfHeight,color);
        }
    }

    static void drawInsetArrowTriangles(TriangleFill fill, int lane, float centerX, float centerY,
                                         float halfSize, float thickness, int color) {
        if (halfSize <= 0F) return;
        float inset = Math.max(0F, Math.min(thickness, halfSize * .25F));
        // The inset shoulders move above y=0. Move the fan origin with them so no triangle
        // crosses the concave notches or changes winding. Tiny arrows keep an open stem.
        float originX = centerX + rotateX(lane, 0F, -inset);
        float originY = centerY + rotateY(lane, 0F, -inset);
        for (int point = 0; point < ARROW.length; point++) {
            int next = (point + 1) % ARROW.length;
            float ax = ARROW[point][0] * halfSize + ARROW_INSET[point][0] * inset;
            float ay = ARROW[point][1] * halfSize + ARROW_INSET[point][1] * inset;
            float bx = ARROW[next][0] * halfSize + ARROW_INSET[next][0] * inset;
            float by = ARROW[next][1] * halfSize + ARROW_INSET[next][1] * inset;
            fill.triangle(originX, originY, centerX + rotateX(lane, ax, ay), centerY + rotateY(lane, ax, ay),
                    centerX + rotateX(lane, bx, by), centerY + rotateY(lane, bx, by), color);
        }
    }

    private static float rotateX(int lane, float x, float y) {
        return switch (lane) { case 0 -> y; case 1 -> -x; case 3 -> -y; default -> x; };
    }

    private static float rotateY(int lane, float x, float y) {
        return switch (lane) { case 0 -> -x; case 1 -> -y; case 3 -> x; default -> y; };
    }

    private static float[][] insetDirections(float[][] polygon) {
        float[][] offsets = new float[polygon.length][2];
        for (int i = 0; i < polygon.length; i++) {
            float[] previous = polygon[(i + polygon.length - 1) % polygon.length];
            float[] point = polygon[i], next = polygon[(i + 1) % polygon.length];
            float ax = point[0] - previous[0], ay = point[1] - previous[1];
            float bx = next[0] - point[0], by = next[1] - point[1];
            float aLength = (float) Math.hypot(ax, ay), bLength = (float) Math.hypot(bx, by);
            float aNormalX = -ay / aLength, aNormalY = ax / aLength;
            float bNormalX = -by / bLength, bNormalY = bx / bLength;
            // Intersection of adjacent edges shifted inward by one unit, including reflex corners.
            float scale = 1F / (1F + aNormalX * bNormalX + aNormalY * bNormalY);
            offsets[i][0] = (aNormalX + bNormalX) * scale;
            offsets[i][1] = (aNormalY + bNormalY) * scale;
        }
        return offsets;
    }

    private static float[][] circle() {
        float[][] points = new float[48][2];
        for (int point=0; point<points.length; point++) {
            double angle = point * Math.PI * 2 / points.length;
            points[point][0] = (float) Math.cos(angle);
            points[point][1] = (float) Math.sin(angle);
        }
        return points;
    }

}
