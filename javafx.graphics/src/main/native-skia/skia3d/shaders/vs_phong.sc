$input a_position, a_texcoord0, a_tangent
$output v_normal, v_wpos, v_texcoord0, v_tangent, v_bitangent, v_shadowCoord

#include <bgfx_shader.sh>

uniform mat4 u_lightViewProj; // world → light clip space, for shadow-map lookup

void main()
{
    vec4 wpos = mul(u_model[0], vec4(a_position, 1.0));
    v_wpos = wpos.xyz;
    gl_Position = mul(u_viewProj, wpos);
    v_texcoord0 = a_texcoord0;
    v_shadowCoord = mul(u_lightViewProj, wpos); // fragment position in the light's clip space

    // JavaFX (BaseMesh) stores the tangent frame as a quaternion in the 4-float
    // "normal" slot. The normal/tangent/bitangent are the columns of the
    // quaternion's rotation matrix (q applied to +Z / +X / +Y) — we need all three
    // (a full TBN) so the fragment shader can apply a tangent-space normal map.
    vec4 q = a_tangent;
    vec3 n = vec3(
        2.0 * (q.x * q.z + q.w * q.y),
        2.0 * (q.y * q.z - q.w * q.x),
        1.0 - 2.0 * (q.x * q.x + q.y * q.y));
    vec3 t = vec3(
        1.0 - 2.0 * (q.y * q.y + q.z * q.z),
        2.0 * (q.x * q.y + q.w * q.z),
        2.0 * (q.x * q.z - q.w * q.y));
    vec3 b = vec3(
        2.0 * (q.x * q.y - q.w * q.z),
        1.0 - 2.0 * (q.x * q.x + q.z * q.z),
        2.0 * (q.y * q.z + q.w * q.x));

    v_normal    = normalize(mul(u_model[0], vec4(n, 0.0)).xyz);
    v_tangent   = normalize(mul(u_model[0], vec4(t, 0.0)).xyz);
    v_bitangent = normalize(mul(u_model[0], vec4(b, 0.0)).xyz);
}
