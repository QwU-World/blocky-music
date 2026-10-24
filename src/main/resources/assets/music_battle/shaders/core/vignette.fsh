#version 150

uniform vec3 Tint;
uniform float Radius;
uniform float Softness;
uniform float Roundness;
uniform float Alpha;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 point = abs(texCoord * 2.0 - 1.0);
    float boxDistance = max(point.x, point.y);
    float ellipseDistance = length(point);
    float distanceFromCenter = mix(boxDistance, ellipseDistance, clamp(Roundness, 0.0, 1.0));
    float edge = smoothstep(Radius, Radius + max(0.01, Softness), distanceFromCenter);
    fragColor = vec4(Tint, edge * clamp(Alpha, 0.0, 1.0));
}
