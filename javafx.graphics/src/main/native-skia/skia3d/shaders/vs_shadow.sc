$input a_position, a_texcoord0, a_tangent
$output v_lpos

// Shadow depth pass: render the scene from the light's point of view. The vertex
// layout matches the phong mesh (pos + uv + normal-quaternion) so the SAME vertex
// buffer feeds both passes; only the position is used here. u_viewProj is set to the
// light's view-projection (see the shadow view's setViewTransform).
#include <bgfx_shader.sh>

void main()
{
    vec4 wpos = mul(u_model[0], vec4(a_position, 1.0));
    gl_Position = mul(u_viewProj, wpos);
    v_lpos = gl_Position;
}
