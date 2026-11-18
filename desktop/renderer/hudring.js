// The HUD's core: Atlas's liquid orb inside a holographic gyroscope (three tilted rings turning in 3D, a tick ring, arcs,
// orbiting points and a glow), drawn on a 2D canvas with additive light. It reacts to its state (idle, listening,
// thinking, speaking, working) and to the sound level (the mic while listening, the voice while speaking).
// Cheap on purpose: about a thousand short lines a frame, 30 fps when quiet, 60 when active, nothing when hidden.
import { Orb } from './orb.js';

const TAU = Math.PI * 2;
const COLORS = {
  ice: [159, 232, 255], violet: [169, 139, 255], blue: [125, 151, 255], white: [235, 242, 255],
};
const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${Math.max(0, Math.min(1, a)).toFixed(3)})`;
// how each state looks: how fast the rings turn, how bright, which colour leads
const LOOK = {
  idle: { spin: 0.18, glow: 0.55, lead: COLORS.blue, spread: 0 },
  listening: { spin: 0.35, glow: 0.9, lead: COLORS.ice, spread: 0.05 },
  thinking: { spin: 0.9, glow: 0.85, lead: COLORS.violet, spread: 0.03 },
  speaking: { spin: 0.4, glow: 1.0, lead: COLORS.ice, spread: 0.02 },
  working: { spin: 0.6, glow: 0.8, lead: COLORS.violet, spread: 0.02 },
};

export class Core {
  /** [reduceMotion]: one still frame, redrawn only when the state changes. */
  constructor(container, { reduceMotion = false, orb = true } = {}) {
    this.el = document.createElement('div'); this.el.className = 'hud-core';
    this.canvas = document.createElement('canvas'); this.canvas.setAttribute('aria-hidden', 'true');
    this.el.appendChild(this.canvas);
    container.appendChild(this.el);
    this.ctx = this.canvas.getContext('2d');
    this.state = 'idle'; this.level = 0; this.shown = { level: 0, spin: LOOK.idle.spin, glow: LOOK.idle.glow, spread: 0 };
    this.lead = [...LOOK.idle.lead];
    this.reduce = reduceMotion; this.t = 0; this.last = 0;
    if (orb) {
      const slot = document.createElement('div'); slot.className = 'hud-core-orb'; this.el.appendChild(slot);
      try { this.orb = new Orb(slot, { size: 10 }); this.orbSlot = slot; } catch { this.orb = null; }
    }
    this.frame = this.frame.bind(this);
    requestAnimationFrame(this.frame);
  }
  setState(s) { if (LOOK[s] && s !== this.state) { this.state = s; if (this.reduce) this.draw(0); } }
  setLevel(l) { this.level = Math.max(0, Math.min(1, Number(l) || 0)); }
  destroy() { this.dead = true; this.el.remove(); }

  frame(now) {
    if (this.dead || !this.el.isConnected) return;
    const active = this.state !== 'idle' || this.shown.level > 0.02;
    const gap = this.reduce ? 250 : active ? 15 : 32;
    if (document.hidden || now - this.last < gap) { requestAnimationFrame(this.frame); return; }
    const dt = this.last ? Math.min(0.1, (now - this.last) / 1000) : 0.016; this.last = now;
    const look = LOOK[this.state];
    const ease = (from, to, k) => from + (to - from) * (1 - Math.pow(k, dt));
    this.shown.level = ease(this.shown.level, this.level, this.level > this.shown.level ? 0.0002 : 0.02);
    this.shown.spin = ease(this.shown.spin, look.spin, 0.2);
    this.shown.glow = ease(this.shown.glow, look.glow, 0.1);
    this.shown.spread = ease(this.shown.spread, look.spread, 0.1);
    for (let i = 0; i < 3; i++) this.lead[i] = ease(this.lead[i], look.lead[i], 0.15);
    if (!this.reduce) this.t += dt * (0.6 + this.shown.spin * 1.6 + this.shown.level * 1.2);
    this.draw();
    if (this.orb) { this.orb.energy = this.state === 'idle' ? 0 : 1; this.orb.level = this.shown.level; }
    requestAnimationFrame(this.frame);
  }

  draw() {
    const c = this.canvas, ctx = this.ctx, dpr = Math.min(2, window.devicePixelRatio || 1);
    const size = Math.max(10, Math.min(this.el.clientWidth, this.el.clientHeight));
    const px = Math.round(size * dpr);
    if (c.width !== px || c.height !== px) { c.width = c.height = px; c.style.width = c.style.height = size + 'px'; }
    if (this.orbSlot) {
      const os = Math.round(size * 0.34);
      if (this.orbSlot._s !== os) { this.orbSlot._s = os; this.orbSlot.style.width = this.orbSlot.style.height = os + 'px'; if (this.orb) this.orb.el.style.width = this.orb.el.style.height = os + 'px'; }
    }
    const L = this.shown.level, G = this.shown.glow, t = this.t, lead = this.lead;
    const cx = px / 2, cy = px / 2, R = px * 0.27 * (1 + this.shown.spread + L * 0.07);
    ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.clearRect(0, 0, px, px);
    ctx.globalCompositeOperation = 'lighter'; ctx.lineCap = 'butt';

    // the glow behind everything
    const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, R * 2.1);
    g.addColorStop(0, rgba(lead, 0.20 * G + 0.18 * L)); g.addColorStop(0.35, rgba(COLORS.violet, 0.07 * G)); g.addColorStop(1, rgba(COLORS.blue, 0));
    ctx.fillStyle = g; ctx.fillRect(0, 0, px, px);

    // the long diagonal line through the middle
    const da = -0.62 + 0.05 * Math.sin(t * 0.3);
    const dl = R * 1.9;
    const lg = ctx.createLinearGradient(cx - Math.cos(da) * dl, cy - Math.sin(da) * dl, cx + Math.cos(da) * dl, cy + Math.sin(da) * dl);
    lg.addColorStop(0, rgba(lead, 0)); lg.addColorStop(0.5, rgba(lead, 0.55 * G)); lg.addColorStop(1, rgba(lead, 0));
    ctx.strokeStyle = lg; ctx.lineWidth = 1.2 * dpr;
    ctx.beginPath(); ctx.moveTo(cx - Math.cos(da) * dl, cy - Math.sin(da) * dl); ctx.lineTo(cx + Math.cos(da) * dl, cy + Math.sin(da) * dl); ctx.stroke();

    // the outer tick ring, turning slowly; a lit sweep follows the sound level
    const tr = R * 1.62, ticks = 120, rot = t * 0.05;
    for (let i = 0; i < ticks; i++) {
      const a = rot + (i / ticks) * TAU;
      const major = i % 10 === 0, mid = i % 5 === 0;
      const len = (major ? 0.09 : mid ? 0.055 : 0.03) * R;
      const litUpTo = L * ticks * 0.5;
      const lit = (i < litUpTo) || (ticks - i < litUpTo);
      ctx.strokeStyle = rgba(lit ? lead : COLORS.white, lit ? 0.9 : major ? 0.42 : 0.18);
      ctx.lineWidth = (major ? 1.6 : 1) * dpr;
      ctx.beginPath(); ctx.moveTo(cx + Math.cos(a) * tr, cy + Math.sin(a) * tr); ctx.lineTo(cx + Math.cos(a) * (tr + len), cy + Math.sin(a) * (tr + len)); ctx.stroke();
    }
    ctx.strokeStyle = rgba(COLORS.white, 0.12); ctx.lineWidth = 1 * dpr;
    ctx.beginPath(); ctx.arc(cx, cy, tr - 3 * dpr, 0, TAU); ctx.stroke();

    // arcs on a wider ring, turning the other way
    const ar = R * 1.86;
    for (let i = 0; i < 3; i++) {
      const a0 = -t * 0.12 + (i * TAU) / 3;
      ctx.strokeStyle = rgba(i === 0 ? lead : COLORS.blue, 0.55 * G); ctx.lineWidth = 2 * dpr;
      ctx.beginPath(); ctx.arc(cx, cy, ar, a0, a0 + 0.55 + 0.25 * Math.sin(t * 0.7 + i)); ctx.stroke();
      ctx.fillStyle = rgba(COLORS.white, 0.8);
      ctx.beginPath(); ctx.arc(cx + Math.cos(a0) * ar, cy + Math.sin(a0) * ar, 1.8 * dpr, 0, TAU); ctx.fill();
    }

    // three gyroscope rings in 3D: bright where they face you, faint behind
    const rings = [
      { r: R * 1.0, ax: t * 0.9, ay: t * 0.55 + 1.1, col: COLORS.ice, w: 1.6, dash: false },
      { r: R * 1.17, ax: -t * 0.6 + 0.7, ay: t * 0.8, col: COLORS.violet, w: 1.4, dash: false },
      { r: R * 1.33, ax: t * 0.35 + 2.0, ay: -t * 0.45, col: lead, w: 1.1, dash: true },
    ];
    const seg = 96, f = R * 6;
    for (const ring of rings) {
      const cxr = Math.cos(ring.ax), sxr = Math.sin(ring.ax), cyr = Math.cos(ring.ay), syr = Math.sin(ring.ay);
      const pts = [];
      for (let i = 0; i <= seg; i++) {
        const th = (i / seg) * TAU; let x = Math.cos(th) * ring.r, y = Math.sin(th) * ring.r, z = 0;
        let y2 = y * cxr - z * sxr, z2 = y * sxr + z * cxr; y = y2; z = z2;      // turn about x
        const x2 = x * cyr + z * syr; z2 = -x * syr + z * cyr; x = x2; z = z2;   // turn about y
        const k = f / (f + z);
        pts.push([cx + x * k, cy + y * k, z / ring.r]);
      }
      // three passes of light: a wide soft bloom, a glow, and the bright thin line
      for (const pass of [0, 1, 2]) {
        for (let i = 0; i < seg; i++) {
          if (ring.dash && (i % 6) > 3) continue;
          const [x1, y1, z1] = pts[i], [x2, y2] = pts[i + 1];
          const front = 0.5 - z1 * 0.5;   // 1 facing you, 0 behind
          const a = pass === 0 ? (0.035 + 0.075 * front) * G + L * 0.06 : pass === 1 ? (0.06 + 0.22 * front) * G + L * 0.1 : (0.14 + 0.8 * front) * (0.75 + 0.25 * G) + L * 0.2;
          ctx.strokeStyle = rgba(pass === 2 && front > 0.8 ? COLORS.white : ring.col, a);
          ctx.lineWidth = (pass === 0 ? 12 : pass === 1 ? 4 : ring.w * (0.7 + 0.7 * front)) * dpr;
          ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
        }
      }
      // a bright bead travelling round each ring
      const bi = Math.floor(((t * 0.25 * ring.r) / R) * seg) % seg; const [bx, by, bz] = pts[(bi + seg) % seg];
      ctx.fillStyle = rgba(COLORS.white, 0.5 + 0.5 * (0.5 - bz * 0.5));
      ctx.beginPath(); ctx.arc(bx, by, 2.4 * dpr, 0, TAU); ctx.fill();
    }

    // orbiting specks
    for (let i = 0; i < 18; i++) {
      const a = t * (0.15 + (i % 5) * 0.03) + i * 2.4, rr = R * (1.42 + ((i * 37) % 23) / 100);
      ctx.fillStyle = rgba(i % 3 ? COLORS.white : lead, 0.25 + 0.35 * ((i * 13) % 7) / 7);
      ctx.fillRect(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr, 1.6 * dpr, 1.6 * dpr);
    }
    ctx.globalCompositeOperation = 'source-over';
  }
}
