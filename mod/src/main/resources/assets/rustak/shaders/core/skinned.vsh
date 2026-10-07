#version 330

// Rust's skinned meshes skinned on the GPU: core/entity's vertex shader with linear blend skinning in front of it.
// The mesh stays in a static buffer; per draw only the Skin block (bone matrices, light, overlay) changes.

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec3 Normal;
in ivec4 BoneIds;
in vec4 BoneWeights;

layout(std140) uniform Skin {
    ivec4 OverlayLight; // overlay uv, lightmap uv
    mat4 Bones[128];    // bone * bind, into the space ModelViewMat expects
};

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    mat4 skin = Bones[BoneIds.x] * BoneWeights.x + Bones[BoneIds.y] * BoneWeights.y
        + Bones[BoneIds.z] * BoneWeights.z + Bones[BoneIds.w] * BoneWeights.w;
    vec3 pos = (skin * vec4(Position, 1.0)).xyz;
    vec3 normal = normalize(mat3(skin) * Normal);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, vec4(1.0));
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, vec4(1.0));
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, vec4(1.0));
#endif
    lightMapColor = texelFetch(Sampler2, OverlayLight.zw / 16, 0);
    overlayColor = texelFetch(Sampler1, OverlayLight.xy, 0);
    texCoord0 = UV0;
}
