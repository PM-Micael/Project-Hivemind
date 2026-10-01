#version 150

// Fog of war: darkens every pixel whose world position is not marked visible in the visibility grid.
// The world position is rebuilt from the depth buffer, so the fog covers terrain, entities and everything else
// drawn in the world, but leaves the sky alone.

uniform sampler2D DepthSampler;
uniform sampler2D VisSampler;

uniform mat4 InvProj;
uniform mat4 InvView;
uniform vec3 CameraPos;
// x, z of the grid's corner in world blocks, and the grid's width in blocks.
uniform vec4 Grid;
uniform vec4 HiveFog;

in vec2 ndc;

out vec4 fragColor;

void main() {
    vec2 uv = ndc * 0.5 + 0.5;
    float depth = texture(DepthSampler, uv).r;
    if (depth >= 1.0) {
        discard;
    }

    vec4 view = InvProj * vec4(ndc, depth * 2.0 - 1.0, 1.0);
    view /= view.w;
    vec3 world = (InvView * view).xyz + CameraPos;

    vec2 cell = (world.xz - Grid.xy) / Grid.z;
    float seen = 0.0;
    if (cell.x >= 0.0 && cell.y >= 0.0 && cell.x <= 1.0 && cell.y <= 1.0) {
        seen = texture(VisSampler, cell).r;
    }

    float fog = 1.0 - seen;
    if (fog <= 0.004) {
        discard;
    }
    fragColor = vec4(HiveFog.rgb, HiveFog.a * fog);
}
