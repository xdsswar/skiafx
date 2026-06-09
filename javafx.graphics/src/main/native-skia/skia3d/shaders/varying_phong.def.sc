vec3 a_position  : POSITION;
vec2 a_texcoord0 : TEXCOORD0;
vec4 a_tangent   : TANGENT;

vec3 v_normal      : TEXCOORD1 = vec3(0.0, 0.0, 1.0);
vec3 v_wpos        : TEXCOORD2 = vec3(0.0, 0.0, 0.0);
vec2 v_texcoord0   : TEXCOORD3 = vec2(0.0, 0.0);
vec3 v_tangent     : TEXCOORD4 = vec3(1.0, 0.0, 0.0);
vec3 v_bitangent   : TEXCOORD5 = vec3(0.0, 1.0, 0.0);
vec4 v_shadowCoord : TEXCOORD6 = vec4(0.0, 0.0, 0.0, 1.0);
