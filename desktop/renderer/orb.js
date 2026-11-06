// Atlas's orb on the PC: the very same shader as on the phone (orb.frag), drawn with WebGL2.
// It checks the graphics chip first: a software-simulated one (or very slow frames) gets the still picture instead,
// so the orb can never slow the app down.
let source = null;
async function frag() { if (!source) source = await (await fetch('orb.frag')).text(); return source; }

export class Orb {
  constructor(container, { size = 180 } = {}) {
    this.el = document.createElement('div'); this.el.className = 'orb'; this.el.style.width = this.el.style.height = size + 'px';
    container.appendChild(this.el);
    this.energy = 0; this.level = 0; this.shownE = 0; this.shownL = 0; this.flow = 0; this.last = 0; this.t0 = performance.now(); this.slow = 0;
    this.still = false;
    this.start();
  }
  async start() {
    const c = document.createElement('canvas'); this.canvas = c; this.el.appendChild(c);
    const gl = c.getContext('webgl2', { premultipliedAlpha: true, alpha: true, antialias: false });
    if (!gl || Orb.software(gl)) return this.fallback();
    const vs = '#version 300 es\nin vec2 v; void main(){ gl_Position = vec4(v, 0.0, 1.0); }';
    const prog = gl.createProgram();
    for (const [type, src] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, await frag()]]) {
      const sh = gl.createShader(type); gl.shaderSource(sh, src); gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) { console.warn('orb shader', gl.getShaderInfoLog(sh)); return this.fallback(); }
      gl.attachShader(prog, sh);
    }
    gl.bindAttribLocation(prog, 0, 'v'); gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return this.fallback();
    const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    this.gl = gl; this.prog = prog;
    this.u = Object.fromEntries(['uRes', 'uTime', 'uEnergy', 'uLevel', 'uFlow'].map((n) => [n, gl.getUniformLocation(prog, n)]));
    this.frame = this.frame.bind(this); requestAnimationFrame(this.frame);
  }
  static software(gl) {
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    const r = ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
    return /swiftshader|llvmpipe|software|basic render/i.test(String(r));
  }
  fallback() {
    if (this.canvas) this.canvas.remove();
    const img = document.createElement('img'); img.src = 'orb_still.png'; img.alt = ''; img.className = 'orb-still'; this.el.appendChild(img);
    this.img = img; this.gl = null;
    const breathe = () => { if (!this.el.isConnected) return; const s = 1 + 0.03 * Math.sin(performance.now() / 900) + 0.06 * this.level; img.style.transform = `scale(${s})`; requestAnimationFrame(breathe); };
    requestAnimationFrame(breathe);
  }
  frame(now) {
    if (!this.el.isConnected) return;
    const busy = this.energy > 0.05 || this.shownE > 0.05 || this.level > 0.02;
    const gap = busy ? 15 : 32;
    if (now - this.last < gap || document.hidden) { requestAnimationFrame(this.frame); return; }
    const dt = this.last ? Math.min(0.1, (now - this.last) / 1000) : 0; this.last = now;
    this.shownL += (this.level - this.shownL) * (1 - Math.pow(0.0005, dt));
    this.shownE += (this.energy - this.shownE) * (1 - Math.pow(0.08, dt));
    this.flow += dt * (0.045 + 0.09 * this.shownE + 0.05 * this.shownL);
    const gl = this.gl, c = this.canvas, dpr = Math.min(2, window.devicePixelRatio || 1);
    const w = Math.round(this.el.clientWidth * dpr), h = Math.round(this.el.clientHeight * dpr);
    if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
    const t0 = performance.now();
    gl.viewport(0, 0, w, h); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.useProgram(this.prog);
    gl.uniform2f(this.u.uRes, w, h); gl.uniform1f(this.u.uTime, this.still ? 4 : ((now - this.t0) / 1000) % 20000);
    gl.uniform1f(this.u.uEnergy, this.shownE); gl.uniform1f(this.u.uLevel, this.shownL); gl.uniform1f(this.u.uFlow, this.still ? 0.2 : this.flow);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    // a chip that takes far too long gets the still picture rather than slowing everything
    if (performance.now() - t0 > 60) { if (++this.slow > 5) return this.fallback(); } else this.slow = Math.max(0, this.slow - 1);
    if (!this.still) requestAnimationFrame(this.frame);
  }
}
