$input a_position, a_normal
$output v_normal

#include <bgfx_shader.sh>

void main()
{
    gl_Position = mul(u_modelViewProj, vec4(a_position, 1.0));
    // World-space normal (model has no non-uniform scale here).
    v_normal = mul(u_model[0], vec4(a_normal, 0.0)).xyz;
}
