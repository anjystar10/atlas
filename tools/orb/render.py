import moderngl, numpy as np
from PIL import Image
ctx = moderngl.create_standalone_context(backend='egl')
VS = '#version 330\nin vec2 v; void main(){gl_Position=vec4(v,0,1);}'
class Orb:
    def __init__(self, src):
        s = src.replace('#version 300 es', '#version 330').replace('precision highp float;', '').replace('precision highp int;', '')
        self.prog = ctx.program(vertex_shader=VS, fragment_shader=s)
        vbo = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], 'f4'))
        self.vao = ctx.vertex_array(self.prog, [(vbo, '2f', 'v')])
    def frame(self, w, h, t, e=0.0, l=0.0, flow=None):
        if flow is None: flow = t * (0.035 + 0.09 * e + 0.05 * l)
        fbo = ctx.simple_framebuffer((w, h)); fbo.use(); fbo.clear(0, 0, 0, 0)
        for k, v in (('uRes', (w, h)), ('uTime', t), ('uEnergy', e), ('uLevel', l), ('uFlow', flow)):
            if k in self.prog: self.prog[k].value = v
        self.vao.render(moderngl.TRIANGLE_STRIP)
        a = np.frombuffer(fbo.read(components=4), np.uint8).reshape(h, w, 4)[::-1].astype(np.float32) / 255
        fbo.release(); return a
def over(a, bg): return a[..., :3] + np.asarray(bg) * (1 - a[..., 3:4])
def save(arr, path): Image.fromarray((np.clip(arr, 0, 1) * 255).astype(np.uint8)).save(path)
DARK = np.array([0.035, 0.035, 0.07]); LIGHT = np.array([0.80, 0.80, 0.93])
