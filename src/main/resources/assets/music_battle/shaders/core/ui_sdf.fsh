#version 150

// Kinds (shapeB.y & 3): 0 fill, 1 inner stroke, 2 ring, 3 soft shadow. Sizes are fixed point * 8.
// A CPU copy of this maths lives in ru.gora.musicbattle.client.ui.SdfMath and is checked by ui-toolkit.

in vec4 vertexColor;
in vec2 localPos;
flat in ivec2 shapeA;
flat in ivec2 shapeB;

uniform vec4 ColorModulator;

out vec4 fragColor;

const float TAU = 6.2831853;

float roundBox(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + vec2(radius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    int kind = shapeB.y & 3;
    float d;
    if (kind == 2) {
        float outer = float(shapeA.x) / 8.0;
        float inner = float(shapeA.y) / 8.0;
        float r = length(localPos);
        d = max(r - outer, inner - r);
        float sweep = float(shapeB.x) / 32767.0;
        if (sweep < 0.9999) {
            // GUI y grows downwards: angle 0 is 12 o'clock and the sweep runs clockwise on screen.
            float a = sweep * TAU;
            float startSide = -localPos.x;                        // < 0 right of 12 o'clock
            float endSide = dot(localPos, vec2(cos(a), sin(a)));  // < 0 before the end edge
            float wedge = sweep <= 0.5 ? max(startSide, endSide) : min(startSide, endSide);
            d = max(d, wedge);
        }
    } else {
        vec2 halfSize = vec2(shapeA) / 8.0;
        float radius = float(shapeB.x) / 8.0;
        d = roundBox(localPos, halfSize, radius);
        if (kind == 1) {
            float width = float(shapeB.y >> 2) / 8.0;
            d = abs(d + width * 0.5) - width * 0.5;
        }
    }
    // GUI units covered by one physical pixel: right at any GUI scale and under any PoseStack scale.
    float px = max(max(fwidth(localPos.x), fwidth(localPos.y)), 1.0e-4);
    float coverage;
    if (kind == 3) {
        float soft = max(float(shapeB.y >> 2) / 8.0, px);
        coverage = 1.0 - smoothstep(-soft, soft, d);
    } else {
        coverage = clamp(0.5 - d / px, 0.0, 1.0);
    }
    if (coverage <= 0.0) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, vertexColor.a * coverage) * ColorModulator;
}
