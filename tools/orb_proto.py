"""Per-pixel prototype of the Atlas orb shader. Every line here maps 1:1 to the AGSL in OrbShader.kt."""
import numpy as np
from PIL import Image

def fract(x): return x - np.floor(x)
def hash2(px, py): return fract(np.sin(px * 127.1 + py * 311.7) * 43758.5453)
def vnoise(x, y):
    ix, iy = np.floor(x), np.floor(y); fx, fy = x - ix, y - iy
    ux, uy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a = hash2(ix, iy); b = hash2(ix + 1, iy); c = hash2(ix, iy + 1); d = hash2(ix + 1, iy + 1)
    return (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy
def fbm(x, y):
    v = 0.0; amp = 0.5
    for _ in range(3):
        v = v + amp * vnoise(x, y); x, y = x * 2.03 + 1.7, y * 2.03 + 9.2; amp *= 0.5
    return v
def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t)

def radius(th, t, energy):
    k = 1.0 + 0.060 * np.sin(2 * th + 0.4 + t * 0.31) + 0.045 * np.sin(3 * th + 1.9 - t * 0.23) \
        + 0.025 * np.sin(5 * th + 4.1 + t * 0.41) + 0.015 * energy * np.sin(7 * th + t * 1.7)
    bite = -0.42 + 0.12 * np.sin(t * 0.21)
    def wrap(x): return np.arctan2(np.sin(x), np.cos(x))     # angle difference, always within -pi..pi
    d0 = wrap(th - bite); d1 = wrap(th - bite + 0.62); d2 = wrap(th - bite - 2.6)
    k = k - 0.46 * np.exp(-(d0 * d0) / (2 * 0.26 ** 2)) + 0.10 * np.exp(-(d1 * d1) / (2 * 0.28 ** 2)) \
        + 0.07 * np.exp(-(d2 * d2) / (2 * 0.5 ** 2))
    return k

def render(W, t, energy=0.0, level=0.0):
    yy, xx = np.mgrid[0:W, 0:W].astype(np.float64)
    size = W
    # p: units of the orb's radius, centred, y squashed so the shape is a little taller than wide
    s = size * 0.33 * (1 + 0.08 * level)
    px = (xx + 0.5 - size / 2) / s
    py = (yy + 0.5 - size / 2) / s / 1.12
    th = np.arctan2(py, px)
    R = radius(th, t, energy)
    d = np.sqrt(px * px + py * py) / R                    # 0 at the centre, 1 on the edge
    aa = 1.5 / s
    inside = 1 - smoothstep(1 - aa, 1 + aa, d)

    # the light inside: ribbons along the contour lines of drifting noise, densest between centre and edge
    fx = px * 1.05 + 0.35 * np.sin(t * 0.21); fy = py * 1.05 + 0.3 * np.cos(t * 0.17)
    warpx = fbm(fx + 3.1, fy - 1.3 + t * 0.05); warpy = fbm(fx - 2.4 + t * 0.04, fy + 5.2)
    n = fbm(fx + 1.4 * warpx, fy + 1.4 * warpy)
    rib = 1 - smoothstep(0.0, 0.07, np.abs(n - 0.52))
    rib2 = 1 - smoothstep(0.0, 0.045, np.abs(n - 0.42))
    halo = 1 - smoothstep(0.0, 0.16, np.abs(n - 0.50))         # the glow the ribbons sit in
    zone = smoothstep(0.15, 0.7, d) * (1 - smoothstep(0.97, 1.0, d))
    grain = hash2(np.floor(xx), np.floor(yy))
    speck = 0.7 + 0.6 * grain
    band = (rib * 1.0 + rib2 * 0.5) * zone * speck
    soft = smoothstep(0.30, 0.8, fbm(fx * 0.8 + 4.0, fy * 0.8 - t * 0.03)) * zone * 0.35   # haze around them
    blue = np.stack([0.06 + 0 * d, 0.32 + 0 * d, 1.0 + 0 * d], -1)
    bright = np.stack([0.55 + 0 * d, 0.80 + 0 * d, 1.0 + 0 * d], -1)
    col = np.zeros((W, W, 3))
    col += np.stack([0.004, 0.008, 0.03]) * np.ones((W, W, 1))                      # near-black glass
    col += blue * (band * (0.9 + 0.5 * energy))[..., None]
    col += bright * ((band ** 3) * 0.35)[..., None]
    col += blue * (soft * 0.4 + halo * zone * 0.35)[..., None]

    # the glass shell: silver at the edge, with fine horizontal lines, lit from the top left
    nx, ny = px / np.maximum(1e-6, np.sqrt(px * px + py * py)), py / np.maximum(1e-6, np.sqrt(px * px + py * py))
    light = np.clip(0.7 - 0.75 * (nx * 0.70 + ny * 0.72), 0, 1)             # top-left faces the light
    shell = smoothstep(0.66, 0.995, d)
    lines = 0.62 + 0.38 * np.sin(yy * np.pi / 1.15)                            # ~2.3 px pitch
    silver = np.stack([0.86 + 0 * d, 0.91 + 0 * d, 1.0 + 0 * d], -1)
    col += silver * (shell ** 1.5 * light * lines * 1.15)[..., None]
    col += blue * (shell * (1 - light) * 0.25)[..., None]                     # the shadow side keeps a blue edge
    # warm light catching the lower right edge
    warm = np.clip(nx * 0.75 + ny * 0.66, 0, 1) ** 3 * smoothstep(0.88, 1.0, d)
    col += np.stack([1.0 + 0 * d, 0.50 + 0 * d, 0.32 + 0 * d], -1) * (warm * 0.85)[..., None]

    alpha = inside
    # soft blue glow just outside the edge
    glow = np.exp(-np.maximum(d - 1, 0) * 6) * (1 - inside) * (0.10 + 0.25 * energy)
    out = np.zeros((W, W, 4))
    out[..., :3] = np.clip(col, 0, 1) * alpha[..., None] + np.array([0.1, 0.3, 1.0]) * glow[..., None]
    out[..., 3] = np.clip(alpha + glow, 0, 1)
    return out

if __name__ == "__main__":
    W = 520
    frames = []
    for i, (t, e) in enumerate([(0.0, 0.0), (6.0, 0.0), (12.0, 1.0)]):
        o = render(W, t, e)
        bg = np.array([4, 4, 12]) / 255.0
        rgb = o[..., :3] + bg * (1 - o[..., 3:4])
        Image.fromarray((np.clip(rgb, 0, 1) * 255).astype(np.uint8)).save(f"sh{i}.png")
    print("ok")
