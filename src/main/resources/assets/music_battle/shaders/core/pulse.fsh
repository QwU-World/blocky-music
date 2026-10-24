#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D ModelMaskSampler;
uniform float Scale;
uniform float UseModelMask;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 center = vec2(0.5);
    vec2 sourceUv = center + (texCoord - center) / max(1.0, Scale);
    sourceUv = clamp(sourceUv, vec2(0.001), vec2(0.999));

    vec4 original = texture(DiffuseSampler, texCoord);
    vec4 pulsed = texture(DiffuseSampler, sourceUv);
    float sourceModel = texture(ModelMaskSampler, sourceUv).r;
    float destinationModel = texture(ModelMaskSampler, texCoord).r;
    float protectedSource = UseModelMask * sourceModel * (1.0 - destinationModel);
    fragColor = vec4(mix(pulsed.rgb, original.rgb,
            smoothstep(0.05, 0.95, protectedSource)), 1.0);
}
