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

float blockDepth(vec2 uv) {
    vec4 encodedDepth = texture(BlockDepthSampler, sourceUv(uv));
    float decoded = dot(encodedDepth.rgb, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
    return encodedDepth.a < 0.5 ? 1.0 : decoded;
}

float blockPresence(vec2 uv) {
    vec2 sampledUv = sourceUv(uv);
    float excluded = texture(ExcludedSampler, sampledUv).r * UseExcludedMask;
    return step(0.5, texture(BlockDepthSampler, sampledUv).a) * (1.0 - step(0.05, excluded));
}

float excludedPresence(vec2 uv) {
    return step(0.05, texture(ExcludedSampler, sourceUv(uv)).r * UseExcludedMask);
}

vec3 blockColor(vec2 uv) {
    return texture(BlockColorSampler, sourceUv(uv)).rgb;
}

float pseudoLinearDepth(float depth) {
    return 1.0 / max(0.00035, 1.0 - min(depth, 0.99965));
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

    // Strong colour changes retain contours between coplanar neighbouring block textures.
    vec3 centerColor = blockColor(texCoord);
    float colorDelta = 0.0;
    colorDelta = max(colorDelta, length(blockColor(leftUv) - centerColor) * leftMask);
    colorDelta = max(colorDelta, length(blockColor(rightUv) - centerColor) * rightMask);
    colorDelta = max(colorDelta, length(blockColor(downUv) - centerColor) * downMask);
    colorDelta = max(colorDelta, length(blockColor(upUv) - centerColor) * upMask);
    float textureEdge = smoothstep(0.22, 0.58, colorDelta) * centerMask;

    // The camera preview's block snapshot is taken before entities. Compare its nearest
    // contributing block against the final camera depth so foreground players/mobs correctly
    // cover the contour. The regular world path keeps UseSceneDepth=0 and its stencil mask.
    float nearestBlockDepth = min(
            mix(1.0, centerDepth, centerMask),
            min(min(mix(1.0, leftDepth, leftMask), mix(1.0, rightDepth, rightMask)),
                    min(mix(1.0, downDepth, downMask), mix(1.0, upDepth, upMask))));
    float sceneDepth = texture(SceneDepthSampler, sourceUv(texCoord)).r;
    float depthTolerance = max(0.0000015, (1.0 - nearestBlockDepth) * 0.0015);
    float depthVisible = step(nearestBlockDepth - depthTolerance, sceneDepth);
    float occlusion = mix(1.0, depthVisible, step(0.5, UseSceneDepth));

    return max(silhouette, max(depthEdge, textureEdge)) * support * occlusion * (1.0 - excluded);
}

void main() {
    float hardEdge = contourAt(Thickness);
    float glowAmount = clamp(GlowStrength / 5.0, 0.0, 1.0);
    float softEdge = contourAt(Thickness + GlowStrength * 1.45);
    float edge = max(hardEdge, softEdge * glowAmount * 0.42);
    fragColor = vec4(OutlineColor, edge * clamp(Alpha, 0.0, 1.0));
}
