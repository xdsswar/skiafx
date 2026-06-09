$input v_normal, v_wpos, v_texcoord0, v_tangent, v_bitangent, v_shadowCoord

#include <bgfx_shader.sh>

#define MAX_LIGHTS 8

uniform vec4 u_ambient;             // rgb ambient term
uniform vec4 u_diffuse;             // rgb diffuse color, a = alpha
uniform vec4 u_specular;            // rgb specular color, a = power (0 = none)
uniform vec4 u_camPos;             // xyz camera world position
uniform vec4 u_mapFlags;           // x,y,z,w = diffuse,specular,bump,self-illum present (1/0)

// Unified light model (mirrors the stock JavaFX shader), MAX_LIGHTS slots:
uniform vec4 u_lightPos[MAX_LIGHTS];   // xyz world position
uniform vec4 u_lightColor[MAX_LIGHTS]; // rgb color, w = light on (1/0)
uniform vec4 u_lightDir[MAX_LIGHTS];   // xyz spotlight direction
uniform vec4 u_lightAttn[MAX_LIGHTS];  // ca, la, qa, isAttenuated (0 = directional)
uniform vec4 u_lightSpot[MAX_LIGHTS];  // cosOuter, denom(cosInner-cosOuter), falloff, range

// Texture maps — stages match the native bind order (diffuse=0, specular=1, bump=2,
// self-illum=3). u_mapFlags is a uniform, so the branches are uniform control flow.
SAMPLER2D(s_diffuse,   0);
SAMPLER2D(s_specular,  1);
SAMPLER2D(s_bump,      2);
SAMPLER2D(s_selfIllum, 3);
SAMPLER2D(s_shadow,    4); // R32F shadow map (light-space depth)

uniform vec4 u_shadowParams; // x = enabled (1/0), y = depth bias, z = shadow strength, w = unused

// Fraction of light reaching the fragment (1 = lit, 1-strength = fully shadowed).
// Compares the fragment's light-space depth against the nearest occluder stored in
// the shadow map. 2x2 PCF softens the edge. Range-agnostic on D3D/GL NDC since both
// the map and this comparison use z/w from the same light projection.
float shadowFactor(vec4 shadowCoord)
{
    if (u_shadowParams.x < 0.5) { return 1.0; }
    vec3 sc = shadowCoord.xyz / shadowCoord.w;
    vec2 uv = sc.xy * 0.5 + 0.5;
#if BGFX_SHADER_LANGUAGE_HLSL || BGFX_SHADER_LANGUAGE_PSSL || BGFX_SHADER_LANGUAGE_METAL || BGFX_SHADER_LANGUAGE_SPIRV
    uv.y = 1.0 - uv.y; // bottom-left vs top-left texture origin
#endif
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { return 1.0; } // outside the map
    float frag = sc.z - u_shadowParams.y; // bias against shadow acne
    float texel = 1.0 / 2048.0;
    // texture2DLod (explicit LOD 0, no gradients) — the shadow map has no mips, and
    // a gradient-taking sample inside this loop is illegal in HLSL.
    float lit = 0.0;
    lit += (frag <= texture2DLod(s_shadow, uv + vec2(-texel, -texel), 0.0).r) ? 1.0 : 0.0;
    lit += (frag <= texture2DLod(s_shadow, uv + vec2( 0.0,   -texel), 0.0).r) ? 1.0 : 0.0;
    lit += (frag <= texture2DLod(s_shadow, uv + vec2(-texel,  0.0),   0.0).r) ? 1.0 : 0.0;
    lit += (frag <= texture2DLod(s_shadow, uv,                        0.0).r) ? 1.0 : 0.0;
    lit *= 0.25;
    return mix(1.0 - u_shadowParams.z, 1.0, lit);
}

// Spotlight cone factor (matches stock computeSpotlightFactor). cosOuter == -1 with
// falloff 0 is the point-light case (180° cone) → no cutoff.
float spotFactor(vec3 l, vec3 lightDir, float cosOuter, float denom, float falloff)
{
    if (falloff == 0.0 && cosOuter == -1.0) { return 1.0; }
    float cosAngle = dot(normalize(-lightDir), l);
    float cutoff = cosAngle - cosOuter;
    if (falloff != 0.0) { return pow(clamp(cutoff / denom, 0.0, 1.0), falloff); }
    return cutoff >= 0.0 ? 1.0 : 0.0;
}

void main()
{
    vec2 uv = v_texcoord0;

    // Diffuse base = diffuse map (if present) × material diffuse color.
    vec4 diffSample = (u_mapFlags.x > 0.5) ? texture2D(s_diffuse, uv) : vec4(1.0, 1.0, 1.0, 1.0);
    vec3 base   = diffSample.rgb * u_diffuse.rgb;
    float alpha = diffSample.a   * u_diffuse.a;

    // Surface normal, optionally perturbed by a tangent-space normal map.
    vec3 N = normalize(v_normal);
    if (u_mapFlags.z > 0.5)
    {
        vec3 tn = texture2D(s_bump, uv).xyz * 2.0 - 1.0;
        vec3 T  = normalize(v_tangent);
        vec3 B  = normalize(v_bitangent);
        N = normalize(T * tn.x + B * tn.y + N * tn.z);
    }

    // Specular color (optionally map-modulated) + power.
    vec3 specColor = u_specular.rgb;
    if (u_mapFlags.y > 0.5) { specColor *= texture2D(s_specular, uv).rgb; }
    float specPow = u_specular.a;

    // View reflection vector for the Phong specular term (matches stock).
    vec3 refl = reflect(normalize(v_wpos - u_camPos.xyz), N);

    vec3 d = vec3(0.0, 0.0, 0.0);
    vec3 s = vec3(0.0, 0.0, 0.0);

    for (int i = 0; i < MAX_LIGHTS; ++i)
    {
        vec3 lc = u_lightColor[i].rgb;
        if (u_lightColor[i].w < 0.5) { continue; } // light off

        vec3 ldir = u_lightDir[i].xyz;
        if (u_lightAttn[i].w < 0.5)
        {
            // Directional light.
            d += clamp(dot(N, -ldir), 0.0, 1.0) * lc;
            if (specPow > 0.0) {
                s += pow(clamp(dot(-refl, -ldir), 0.0, 1.0), specPow) * lc;
            }
        }
        else
        {
            // Positional (point / spot) light with attenuation + range.
            vec3 toLight = u_lightPos[i].xyz - v_wpos;
            float dist = length(toLight);
            if (dist > u_lightSpot[i].w) { continue; } // out of range
            vec3 L = normalize(toLight);
            float spot = spotFactor(L, ldir, u_lightSpot[i].x, u_lightSpot[i].y, u_lightSpot[i].z);
            float invAttn = u_lightAttn[i].x + u_lightAttn[i].y * dist + u_lightAttn[i].z * dist * dist;
            vec3 atten = lc * spot / invAttn;
            d += clamp(dot(N, L), 0.0, 1.0) * atten;
            if (specPow > 0.0) {
                s += pow(clamp(dot(-refl, L), 0.0, 1.0), specPow) * atten;
            }
        }
    }

    // Shadowing dims the direct (diffuse + specular) terms; ambient is unshadowed.
    float shadow = shadowFactor(v_shadowCoord);
    vec3 color = (u_ambient.rgb + d * shadow) * base + (s * shadow) * specColor;
    if (u_mapFlags.w > 0.5) { color += texture2D(s_selfIllum, uv).rgb; } // self-illumination

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), alpha);
}
