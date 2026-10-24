#version 150

// Blocky Music Workshop UI: a texture region clipped to a rectangle with per-corner radii, dimmed towards
// black, faded by the per-vertex colour (left-to-right gradients) and anti-aliased at any scale.

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform vec2 RectSize;   // GUI units
uniform vec4 Radii;      // top-left, top-right, bottom-right, bottom-left, GUI units
uniform vec4 UvRect;     // u0, v0, u1, v1 of the drawn region
uniform float Dim;       // 0..1 towards black

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 span = UvRect.zw - UvRect.xy;  // negative when the region is mirrored
    span = vec2(abs(span.x) < 1.0e-6 ? 1.0e-6 : span.x, abs(span.y) < 1.0e-6 ? 1.0e-6 : span.y);
    vec2 t = (texCoord0 - UvRect.xy) / span;
    vec2 p = (t - 0.5) * RectSize;
    float r = p.x < 0.0 ? (p.y < 0.0 ? Radii.x : Radii.w) : (p.y < 0.0 ? Radii.y : Radii.z);
    r = min(r, min(RectSize.x, RectSize.y) * 0.5);
    vec2 q = abs(p) - RectSize * 0.5 + vec2(r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float px = max(max(fwidth(p.x), fwidth(p.y)), 1.0e-4);
    float coverage = clamp(0.5 - d / px, 0.0, 1.0);
    if (coverage <= 0.0) {
        discard;
    }
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    color.rgb *= 1.0 - clamp(Dim, 0.0, 1.0);
    fragColor = vec4(color.rgb, color.a * coverage) * ColorModulator;
}
