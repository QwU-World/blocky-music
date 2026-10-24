#version 150

uniform sampler2D DiffuseSampler;
uniform float Amount;
uniform float Brightness;
uniform float Contrast;
uniform float Saturation;
uniform float Hue;
uniform float Gamma;
uniform float Temperature;

in vec2 texCoord;
out vec4 fragColor;

vec3 rotateHue(vec3 color, float angle) {
    const vec3 axis = vec3(0.57735026919);
    float cosine = cos(angle);
    float sine = sin(angle);
    return color * cosine
        + cross(axis, color) * sine
        + axis * dot(axis, color) * (1.0 - cosine);
}

void main() {
    vec4 original = texture(DiffuseSampler, texCoord);
    vec3 corrected = original.rgb;

    corrected += vec3(Brightness);
    corrected = (corrected - vec3(0.5)) * Contrast + vec3(0.5);
    float luminance = dot(corrected, vec3(0.2126, 0.7152, 0.0722));
    corrected = mix(vec3(luminance), corrected, Saturation);
    corrected = rotateHue(corrected, Hue);
    corrected += vec3(0.12, 0.015, -0.12) * Temperature;
    corrected = pow(max(corrected, vec3(0.0)), vec3(1.0 / max(0.2, Gamma)));
    corrected = clamp(corrected, vec3(0.0), vec3(1.0));

    fragColor = vec4(mix(original.rgb, corrected, clamp(Amount, 0.0, 1.0)), 1.0);
}
