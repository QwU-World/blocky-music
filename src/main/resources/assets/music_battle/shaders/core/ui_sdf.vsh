#version 150

// Blocky Music Workshop UI: anti-aliased rounded rectangles, strokes, soft shadows and progress rings in one
// batch. Parameter encoding: ru.gora.musicbattle.client.ui.SdfMath.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec2 localPos;
flat out ivec2 shapeA;
flat out ivec2 shapeB;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    localPos = UV0;
    shapeA = UV1;
    shapeB = UV2;
}
