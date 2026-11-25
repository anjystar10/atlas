#version 300 es
precision highp float;
precision highp int;

// Atlas's orb: a living drop of dark glass with streams of blue light moving inside it.
uniform vec2 uRes;      // drawing size in pixels
uniform float uTime;    // seconds
uniform float uEnergy;  // 0 resting .. 1 listening / working
uniform float uLevel;   // 0..1 loudness of the voice it hears
uniform float uFlow;    // how far the inner light has flowed: grows faster while busy, so changes never jump
out vec4 fragColor;

const float PI = 3.14159265;

// ---------- noise (integer hashing: identical on every GPU) ----------
uint hashU(uvec2 p) {
    uint h = p.x * 1664525u + p.y * 1013904223u;
    h ^= h >> 16; h *= 0x7feb352du; h ^= h >> 15; h *= 0x846ca68bu; h ^= h >> 16;
    return h;
}
float h01(ivec2 p) { return float(hashU(uvec2(p + 4096)) & 0xFFFFFFu) / 16777215.0; }
// value noise with a smooth fade, -0.5..0.5 (no hidden grid lines in its contours, unlike gradient noise)
float vnoise(vec2 p) {
    ivec2 i = ivec2(floor(p)); vec2 f = fract(p);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = h01(i), b = h01(i + ivec2(1, 0)), c = h01(i + ivec2(0, 1)), d = h01(i + ivec2(1, 1));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y) - 0.5;
}
float fbm3(vec2 p) {
    float v = 0.0, a = 0.55;
    mat2 r = mat2(0.8, 0.6, -0.6, 0.8);
    for (int k = 0; k < 3; k++) { p = r * p; v += a * vnoise(p); p = p * 2.03 + 7.3; a *= 0.45; }
    return v;
}
float hash1(vec2 p) { return float(hashU(uvec2(ivec2(floor(p)) + 65536)) & 0xFFFFFFu) / 16777215.0; }
float wrapA(float x) { return atan(sin(x), cos(x)); }
float bump(float th, float at, float w) { float d = wrapA(th - at); return exp(-d * d / w); }

// ---------- the shape: radius of the drop at angle th (0 = right, counter-clockwise) ----------
float radiusAt(float th, float t, float e, float lvl) {
    float r = 1.0
        + 0.045 * sin(2.0 * th + 0.6 + t * 0.21)
        + 0.030 * sin(3.0 * th + 2.1 - t * 0.16)
        + 0.015 * sin(5.0 * th + 4.0 + t * 0.27);
    float notch = 0.08 + 0.14 * sin(t * 0.09);      // the signature notch on the right, drifting
    r -= 0.32 * bump(th, notch, 0.045);
    r += 0.13 * bump(th, notch + 0.55, 0.07);      // the lobe that curls over it
    r += 0.05 * bump(th, notch - 0.75, 0.10);       // and a softer one below
    r += 0.05 * bump(th, notch + 2.9, 0.40);        // a fuller belly on the lower left
    // alive with the voice: small fast ripples
    r += (0.010 * e + 0.030 * lvl) * sin(6.0 * th - t * 3.1) + 0.018 * lvl * sin(9.0 * th + t * 4.3);
    return r;
}

void main() {
    vec2 frag = gl_FragCoord.xy;
    float side = min(uRes.x, uRes.y);
    float t = uTime, e = uEnergy, lvl = uLevel;
    float S = side * 0.33 * (1.0 + 0.06 * lvl + 0.03 * e);
    vec2 p = (frag - 0.5 * uRes) / S;
    float sway = 0.07 * sin(t * 0.13);
    p = mat2(cos(sway), -sin(sway), sin(sway), cos(sway)) * p;
    p.y /= 1.16;

    float rad = length(p);
    float th = atan(p.y, p.x);
    float R = radiusAt(th, t, e, lvl);
    float d = rad / R;                       // 1.0 on the outline
    float aa = max(fwidth(d), 1e-4) * 0.9;
    float inside = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, d);

    // ---------- outside: a soft halo ----------
    float g = exp(-max(d - 1.0, 0.0) * 6.0) * (1.0 - inside);
    vec3 glowCol = mix(vec3(0.12, 0.32, 1.0), vec3(0.40, 0.28, 1.0), 0.5 + 0.5 * sin(t * 0.21 + th));
    float glowA = g * (0.10 + 0.32 * e + 0.25 * lvl);

    vec3 col = vec3(0.0);
    if (inside > 0.0) {
        float dd = clamp(d, 0.0, 0.999);
        vec2 dir = rad > 1e-4 ? p / rad : vec2(0.0);
        // the drop as a dome: a surface direction for lighting
        vec3 n = normalize(vec3(dir * dd * 1.25, sqrt(1.0 - dd * dd)));
        float fres = pow(1.0 - n.z, 1.8);

        // ---- light inside: a few broad streams, flowing ----
        float flow = uFlow;
        vec2 q = p * 0.95;
        vec2 w = vec2(fbm3(q + vec2(1.7, 9.2) + vec2(flow, -flow * 0.7)),
                      fbm3(q + vec2(8.3, 2.8) + vec2(-flow * 0.8, flow * 0.5)));
        float f = fbm3(q * 0.9 + 1.6 * w + vec2(3.1, 1.3));
        // streams are contour lines of the field: there are always a few, spread through the drop
        float wave = 0.5 + 0.5 * cos(f * 30.0 + flow * 1.5);
        float stream = pow(wave, 3.0);                               // broad soft stream
        float core = pow(wave, 16.0);                                // its bright heart line
        // some stretches of a stream are bright, others fade to nothing
        float strength = 0.35 + 0.65 * smoothstep(-0.12, 0.18, fbm3(q * 1.3 + w * 0.8 + vec2(-4.0, 6.0) + flow * 0.5));
        stream *= strength; core *= strength;
        float stream2 = 0.0;

        // streams live near the glass; the heart of the drop stays deep and dark
        float rimBand = smoothstep(0.40, 0.88, d);
        float hollow = 1.0 - smoothstep(0.0, 0.62, d);         // (smoothstep with its edges reversed is undefined in GLSL)
        // a band of light that always hugs the inside of the shell
        // (squared by hand: pow() of a negative number is undefined in GLSL, and some chips return NaN for it)
        float hb = (d - 0.88) / 0.065;
        float hug = exp(-hb * hb) * smoothstep(-0.05, 0.25, fbm3(vec2(cos(th), sin(th)) * 1.8 + vec2(t * 0.05, -t * 0.04) + w * 1.5));

        // particle texture: light made of fine glitter, like the reference
        float grain = hash1(frag + floor(t * 8.0) * vec2(37.0, 11.0));
        float glitter = mix(0.55, 1.45, grain);
        float stip = step(0.62, grain);

        float L1 = (stream * 1.0 + core * 1.3) * mix(0.30, 1.0, rimBand) + hug * 0.9;
        L1 *= (1.0 - 0.6 * hollow);
        L1 *= 0.9 + 0.35 * e + 0.30 * lvl;

        vec3 deep = vec3(0.006, 0.010, 0.035);
        vec3 blue = vec3(0.03, 0.26, 1.00);
        vec3 cyan = vec3(0.50, 0.82, 1.00);
        vec3 violet = vec3(0.42, 0.30, 1.00);
        float tint = smoothstep(0.1, 0.5, fbm3(q * 0.5 + vec2(5.0, -2.0) + t * 0.02));
        vec3 lc = mix(blue, violet, tint * 0.55);

        col = deep + vec3(0.0, 0.01, 0.04) * (1.0 - hollow);          // a faint inner blue haze
        col += lc * L1 * glitter;
        col += cyan * core * rimBand * 0.30 * (0.5 + 0.9 * stip);
        col += cyan * pow(L1, 3.0) * 0.12;

        // ---- the glass shell: silver ribs, lit from the top left ----
        float rib = 0.5 + 0.5 * sin(d * S * 2.7);             // hair-fine lines that follow the outline
        float shell = smoothstep(0.80, 0.99, d) * (1.0 - 0.35 * smoothstep(0.985, 1.0, d));
        float lit = smoothstep(-0.25, 0.85, dot(dir, normalize(vec2(-0.72, 0.69))));
        vec3 silver = mix(vec3(0.30, 0.50, 1.0), vec3(0.80, 0.90, 1.0), lit);
        col += silver * shell * (0.06 + 0.94 * lit * lit) * mix(0.30, 1.0, rib) * (0.55 + 0.45 * fres) * 0.85;
        // a soft window reflection: a long highlight along the top-left glass
        float win = smoothstep(0.55, 0.92, d) * (1.0 - smoothstep(0.92, 1.0, d)) * pow(lit, 3.0);
        col += vec3(0.55, 0.75, 1.0) * win * 0.12;
        // thin-film colour on the far edge: warm at the lower right
        float warm = smoothstep(0.0, 0.85, dot(dir, normalize(vec2(0.70, -0.71))));
        col += vec3(1.0, 0.50, 0.36) * smoothstep(0.88, 1.0, d) * warm * 0.75;
        col += vec3(0.35, 0.65, 1.0) * smoothstep(0.93, 1.0, d) * (1.0 - warm) * 0.25;
        col = 1.0 - exp(-col * 1.9);                       // soft roll-off: bright light keeps its colour
    }

    vec3 outc = col * inside + glowCol * glowA;     // premultiplied
    fragColor = vec4(outc, clamp(inside + glowA, 0.0, 1.0));
}
