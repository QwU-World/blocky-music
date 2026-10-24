#version 150

uniform sampler2D DepthSampler;

in vec2 texCoord;
out vec4 fragColor;

vec3 packDepth24(float depth) {
    vec3 encodedDepth = fract(depth * vec3(1.0, 255.0, 65025.0));
    encodedDepth -= encodedDepth.yzz * vec3(1.0 / 255.0, 1.0 / 255.0, 0.0);
    return encodedDepth;
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    // The target is RGBA8. Store depth across all RGB channels instead of quantising it to
    // a single 8-bit red value; alpha distinguishes actual far geometry from clear sky (1.0).
    float hasGeometry = 1.0 - step(0.9999995, depth);
    fragColor = vec4(packDepth24(min(depth, 0.999999)), hasGeometry);
}
