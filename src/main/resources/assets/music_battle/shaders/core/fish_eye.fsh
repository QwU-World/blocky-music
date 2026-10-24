#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D ModelMaskSampler;
uniform float Strength;
uniform float Radius;
uniform float Zoom;
uniform vec2 Center;
uniform float Opacity;
uniform float Chromatic;
uniform float EdgeDarkening;
uniform float RemoveBlackEdges;
uniform float UseModelMask;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 delta = texCoord - Center;
    float safeRadius = max(0.08, Radius);
    float radial = dot(delta / safeRadius, delta / safeRadius);
    float curve = 1.0 + Strength * 0.34 * radial;
    float safeZoom = max(0.01, Zoom);
    vec2 warpedUv = Center + delta * curve / safeZoom;
    vec2 direction = delta / max(length(delta), 0.0001);
    vec2 chromaOffset = direction * Chromatic * Strength * radial * 0.006 / safeZoom;

    vec4 original = texture(DiffuseSampler, texCoord);
    vec2 safeUv = clamp(warpedUv, vec2(0.001), vec2(0.999));
    vec3 warped;
    vec2 redUv = clamp(safeUv + chromaOffset, vec2(0.001), vec2(0.999));
    vec2 blueUv = clamp(safeUv - chromaOffset, vec2(0.001), vec2(0.999));
    warped.r = texture(DiffuseSampler, redUv).r;
    warped.g = texture(DiffuseSampler, safeUv).g;
    warped.b = texture(DiffuseSampler, blueUv).b;

    float sourceModel = max(texture(ModelMaskSampler, redUv).r,
                            max(texture(ModelMaskSampler, safeUv).r,
                                texture(ModelMaskSampler, blueUv).r));
    float destinationModel = texture(ModelMaskSampler, texCoord).r;
    float protectedSource = UseModelMask * sourceModel * (1.0 - destinationModel);
    warped = mix(warped, original.rgb, smoothstep(0.05, 0.95, protectedSource));

    float inside = step(0.0, warpedUv.x) * step(warpedUv.x, 1.0)
                 * step(0.0, warpedUv.y) * step(warpedUv.y, 1.0);
    float edge = smoothstep(0.55 * safeRadius, 1.18 * safeRadius, length(delta) * 2.0);
    warped *= 1.0 - edge * EdgeDarkening * clamp(Strength, 0.0, 1.0);
    // With edge removal enabled the already clamped UV stretches the nearest edge
    // pixel into the otherwise empty area instead of painting it black.
    warped *= mix(inside, 1.0, clamp(RemoveBlackEdges, 0.0, 1.0));
    vec4 distorted = vec4(warped, 1.0);
    fragColor = vec4(mix(original.rgb, distorted.rgb, clamp(Opacity, 0.0, 1.0)), 1.0);
}
