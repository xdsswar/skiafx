$input v_lpos

// Shadow depth pass fragment stage: write the light-space NDC depth into the R32F
// shadow map. The phong pass compares each fragment's light-space depth against this
// to decide if it is occluded. Using z/w (the same value the phong pass computes)
// makes the comparison range-agnostic across D3D ([0,1]) and GL ([-1,1]) NDC.
#include <bgfx_shader.sh>

void main()
{
    float depth = v_lpos.z / v_lpos.w;
    gl_FragColor = vec4(depth, depth, depth, 1.0);
}
