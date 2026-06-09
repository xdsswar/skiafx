$input v_normal

#include <bgfx_shader.sh>

uniform vec4 u_lightDir; // xyz = direction to light
uniform vec4 u_color;    // rgb = base color

void main()
{
    vec3 n = normalize(v_normal);
    float ndl = max(dot(n, normalize(u_lightDir.xyz)), 0.0);
    // Ambient + diffuse.
    gl_FragColor = vec4(u_color.rgb * (0.25 + 0.75 * ndl), 1.0);
}
