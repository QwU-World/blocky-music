#version 150

uniform sampler2D BlockColorSampler;
uniform sampler2D BlockDepthSampler;
uniform sampler2D ExcludedSampler;
uniform sampler2D SceneDepthSampler;
uniform vec2 InSize;
uniform vec3 OutlineColor;
uniform float Alpha;
uniform float Thickness;
uniform float GlowStrength;
uniform float UseSceneDepth;
uniform float UseExcludedMask;
uniform float SuppressColorEdges;
uniform float HandDepthLimit;
uniform mat4 InverseViewProjection;
uniform float UseAudibleArea;
uniform float AudibleCount;
uniform vec3 AudibleFadeRule;
uniform vec4 AudibleSphere0;
uniform vec4 AudibleSphere1;
uniform vec4 AudibleSphere2;
uniform vec4 AudibleSphere3;
uniform vec4 AudibleSphere4;
uniform vec4 AudibleSphere5;
uniform vec4 AudibleSphere6;
uniform vec4 AudibleSphere7;
uniform vec4 AudibleSphere8;
uniform vec4 AudibleSphere9;
uniform vec4 AudibleSphere10;
uniform vec4 AudibleSphere11;
uniform vec4 AudibleSphere12;
uniform vec4 AudibleSphere13;
uniform vec4 AudibleSphere14;
uniform vec4 AudibleSphere15;
uniform float WarpCount;
uniform vec4 WarpA0;
uniform vec4 WarpA1;
uniform vec4 WarpA2;
uniform vec4 WarpA3;
uniform vec4 WarpA4;
uniform vec4 WarpA5;
uniform vec4 WarpA6;
uniform vec4 WarpA7;
uniform vec4 WarpB0;
uniform vec4 WarpB1;
uniform vec4 WarpB2;
uniform vec4 WarpB3;
uniform vec4 WarpB4;
uniform vec4 WarpB5;
uniform vec4 WarpB6;
uniform vec4 WarpB7;

in vec2 texCoord;
out vec4 fragColor;

vec2 safeUv(vec2 uv) {
    return clamp(uv, vec2(0.001), vec2(0.999));
}

vec4 warpA(int index) {
    if (index == 0) return WarpA0;
    if (index == 1) return WarpA1;
    if (index == 2) return WarpA2;
    if (index == 3) return WarpA3;
    if (index == 4) return WarpA4;
    if (index == 5) return WarpA5;
    if (index == 6) return WarpA6;
    return WarpA7;
}

vec4 warpB(int index) {
    if (index == 0) return WarpB0;
    if (index == 1) return WarpB1;
    if (index == 2) return WarpB2;
    if (index == 3) return WarpB3;
    if (index == 4) return WarpB4;
    if (index == 5) return WarpB5;
    if (index == 6) return WarpB6;
    return WarpB7;
}

vec4 audibleSphere(int index) {
    if (index == 0) return AudibleSphere0;
    if (index == 1) return AudibleSphere1;
    if (index == 2) return AudibleSphere2;
    if (index == 3) return AudibleSphere3;
    if (index == 4) return AudibleSphere4;
    if (index == 5) return AudibleSphere5;
    if (index == 6) return AudibleSphere6;
    if (index == 7) return AudibleSphere7;
    if (index == 8) return AudibleSphere8;
    if (index == 9) return AudibleSphere9;
    if (index == 10) return AudibleSphere10;
    if (index == 11) return AudibleSphere11;
    if (index == 12) return AudibleSphere12;
    if (index == 13) return AudibleSphere13;
    if (index == 14) return AudibleSphere14;
    return AudibleSphere15;
}

vec2 sourceUv(vec2 outputUv) {
    vec2 uv = outputUv;
    // Fullscreen passes compose newest-to-oldest when resolving an output pixel back into
    // the original camera snapshot. This keeps a late outline attached to earlier pulse/fish-eye.
    for (int reverseIndex = 0; reverseIndex < 8; reverseIndex++) {
        int index = 7 - reverseIndex;
        if (float(index) >= WarpCount) continue;
        vec4 a = warpA(index);
        vec4 b = warpB(index);
        if (a.x < 1.5) {
            uv = vec2(0.5) + (uv - vec2(0.5)) / max(1.0, a.w);
        } else {
            vec2 delta = uv - a.yz;
            float radius = max(0.08, b.x);
            float radial = dot(delta / radius, delta / radius);
            float curve = 1.0 + a.w * 0.34 * radial;
            vec2 fishUv = a.yz + delta * curve / max(0.01, b.y);
            uv = mix(uv, fishUv, clamp(b.z, 0.0, 1.0));
        }
        uv = safeUv(uv);
    }
    return safeUv(uv);
}

// The block source is a copy of the world pass's own depth buffer. The far plane (clear sky) is no geometry.
float sampledDepth(vec2 sampledUv) {
    return texture(BlockDepthSampler, sampledUv).r;
}

float geometryAt(float depth) {
    return 1.0 - step(0.9999995, depth);
}

// Iris draws the first-person hand into the world depth before translucent blocks, squeezed into the middle
// of the depth range. HandDepthLimit is 0 everywhere else, so nothing counts as a hand there.
float handAt(float depth) {
    return 1.0 - step(HandDepthLimit, depth);
}

float blockDepth(vec2 uv) {
    float depth = sampledDepth(sourceUv(uv));
    return geometryAt(depth) * (1.0 - handAt(depth)) < 0.5 ? 1.0 : depth;
}

float blockPresence(vec2 uv) {
    vec2 sampledUv = sourceUv(uv);
    float excluded = texture(ExcludedSampler, sampledUv).r * UseExcludedMask;
    float depth = sampledDepth(sampledUv);
    return geometryAt(depth) * (1.0 - handAt(depth)) * (1.0 - step(0.05, excluded));
}

float excludedPresence(vec2 uv) {
    vec2 sampledUv = sourceUv(uv);
    float masked = step(0.05, texture(ExcludedSampler, sampledUv).r * UseExcludedMask);
    return max(masked, handAt(sampledDepth(sampledUv)));
}

vec3 blockColor(vec2 uv) {
    return texture(BlockColorSampler, sourceUv(uv)).rgb;
}

float pseudoLinearDepth(float depth) {
    return 1.0 / max(0.00035, 1.0 - min(depth, 0.99965));
}

// 1 well inside the audible area, falling smoothly to 0 at its outer edge, as FxAudibleArea.fadeAt does in Java.
// The spheres' fades add up to at most 1, so the seam between two overlapping speakers stays lit. The sphere
// centres are relative to the camera, and so is the position rebuilt from the copied depth.
float audibleFade(vec2 sampledUv, float depth) {
    if (UseAudibleArea < 0.5) return 1.0;
    vec4 world = InverseViewProjection * vec4(sampledUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 position = world.xyz / world.w;
    float fade = 0.0;
    for (int index = 0; index < 16; index++) {
        if (float(index) >= AudibleCount || fade >= 1.0) break;
        vec4 sphere = audibleSphere(index);
        float width = max(0.001, min(sphere.w,
                clamp(sphere.w * AudibleFadeRule.x, AudibleFadeRule.y, AudibleFadeRule.z)));
        fade = min(1.0, fade + 1.0 - smoothstep(sphere.w - width, sphere.w, length(position - sphere.xyz)));
    }
    return fade;
}

float contourAt(float radius) {
    vec2 pixel = vec2(max(0.5, radius)) / max(InSize, vec2(1.0));
    vec2 leftUv = texCoord - vec2(pixel.x, 0.0);
    vec2 rightUv = texCoord + vec2(pixel.x, 0.0);
    vec2 downUv = texCoord - vec2(0.0, pixel.y);
    vec2 upUv = texCoord + vec2(0.0, pixel.y);

    float centerDepth = blockDepth(texCoord);
    float centerMask = blockPresence(texCoord);
    float leftMask = blockPresence(leftUv);
    float rightMask = blockPresence(rightUv);
    float downMask = blockPresence(downUv);
    float upMask = blockPresence(upUv);
    float excluded = max(excludedPresence(texCoord), max(max(excludedPresence(leftUv),
            excludedPresence(rightUv)), max(excludedPresence(downUv), excludedPresence(upUv))));
    float leftDepth = blockDepth(leftUv);
    float rightDepth = blockDepth(rightUv);
    float downDepth = blockDepth(downUv);
    float upDepth = blockDepth(upUv);
    float support = max(centerMask, max(max(leftMask, rightMask), max(downMask, upMask)));

    // A mask discontinuity is the outside silhouette of block geometry.
    float silhouette = max(max(abs(leftMask - centerMask), abs(rightMask - centerMask)),
            max(abs(downMask - centerMask), abs(upMask - centerMask)));

    // A Laplacian reacts to creases and geometry boundaries while remaining almost zero on
    // a uniformly sloped surface. The old first derivative colored whole walls and floors.
    float centerLinear = pseudoLinearDepth(centerDepth);
    float depthLaplacian = abs(
            pseudoLinearDepth(leftDepth)
            + pseudoLinearDepth(rightDepth)
            + pseudoLinearDepth(downDepth)
            + pseudoLinearDepth(upDepth)
            - 4.0 * centerLinear) / max(1.0, centerLinear);
    float depthEdge = smoothstep(0.012, 0.075, depthLaplacian) * centerMask;

    // Strong colour changes retain contours between coplanar neighbouring block textures. A shader pack
    // draws the world into its own buffers, so its copied colour is a flat fill and contributes nothing.
    vec3 centerColor = blockColor(texCoord);
    float colorDelta = 0.0;
    colorDelta = max(colorDelta, length(blockColor(leftUv) - centerColor) * leftMask);
    colorDelta = max(colorDelta, length(blockColor(rightUv) - centerColor) * rightMask);
    colorDelta = max(colorDelta, length(blockColor(downUv) - centerColor) * downMask);
    colorDelta = max(colorDelta, length(blockColor(upUv) - centerColor) * upMask);
    float textureEdge = smoothstep(0.22, 0.58, colorDelta) * centerMask * (1.0 - SuppressColorEdges);

    // The camera preview's block snapshot is taken before entities. Compare its nearest
    // contributing block against the final camera depth so foreground players/mobs correctly
    // cover the contour. The regular world path keeps UseSceneDepth=0 and its stencil mask.
    float nearestBlockDepth = min(
            mix(1.0, centerDepth, centerMask),
            min(min(mix(1.0, leftDepth, leftMask), mix(1.0, rightDepth, rightMask)),
                    min(mix(1.0, downDepth, downMask), mix(1.0, upDepth, upMask))));
    vec2 centerUv = sourceUv(texCoord);
    float sceneDepth = texture(SceneDepthSampler, centerUv).r;
    float depthTolerance = max(0.0000015, (1.0 - nearestBlockDepth) * 0.0015);
    float depthVisible = step(nearestBlockDepth - depthTolerance, sceneDepth);
    float occlusion = mix(1.0, depthVisible, step(0.5, UseSceneDepth));

    // The nearest contributing block decides how far into the audible area this contour lies, so a
    // silhouette against the sky fades with the block that casts it.
    float audible = audibleFade(centerUv, nearestBlockDepth);

    return max(silhouette, max(depthEdge, textureEdge)) * support * occlusion * (1.0 - excluded) * audible;
}

void main() {
    float hardEdge = contourAt(Thickness);
    float glowAmount = clamp(GlowStrength / 5.0, 0.0, 1.0);
    float softEdge = contourAt(Thickness + GlowStrength * 1.45);
    float edge = max(hardEdge, softEdge * glowAmount * 0.42);
    fragColor = vec4(OutlineColor, edge * clamp(Alpha, 0.0, 1.0));
}
