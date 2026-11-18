// Atlas in the corner of the screen: drag it anywhere; click to talk. It stirs while Atlas listens or works.
import { Orb } from './orb.js';
const host = window.atlasHost;
const orb = new Orb(document.getElementById('o'), { size: 108 });
// the pointer is captured while dragging, so a quick drag that leaves the little window still moves it
let down = null, moved = false;
window.addEventListener('pointerdown', (e) => { if (e.button !== 0) return; down = { x: e.screenX, y: e.screenY }; moved = false; try { document.body.setPointerCapture(e.pointerId); } catch {} });
window.addEventListener('pointermove', (e) => {
  if (!down) return;
  const dx = e.screenX - down.x, dy = e.screenY - down.y;
  if (!moved && Math.hypot(dx, dy) < 4) return;
  moved = true; host.orbMoveBy(dx, dy); down = { x: e.screenX, y: e.screenY };
});
window.addEventListener('pointerup', () => { if (down && !moved) host.orbClicked(); down = null; });
window.addEventListener('pointercancel', () => { down = null; });
host.on('orb-state', (st) => { orb.energy = st.energy || 0; orb.level = st.level || 0; });
