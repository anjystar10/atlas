// A stand-in for the Atlas Supabase link_* functions (same rules as the atlas_link_v1 migration), for tests.
export function createRelay({ latencyMs = 0 } = {}) {
  let nextId = 1;
  const msgs = [], frames = new Map(), presence = new Map();
  const at = () => Date.now();
  const delay = (ms) => new Promise((r) => setTimeout(r, ms));
  const stats = { calls: 0 };
  async function rpc(fn, b) {
    stats.calls++;
    if (latencyMs) await delay(latencyMs);
    const own = (o) => { if (typeof o !== 'string' || o.length !== 64) throw new Error('the cloud answered 400 bad request'); };
    switch (fn) {
      case 'link_head': own(b.p_owner); return msgs.filter((m) => m.owner === b.p_owner).reduce((a, m) => Math.max(a, m.id), 0);
      case 'link_send': {
        own(b.p_owner); if (b.p_payload.length > 1_500_000) throw new Error('the cloud answered 400 message too big');
        for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i].owner === b.p_owner && at() - msgs[i].at > 600_000) msgs.splice(i, 1);
        const id = nextId++; msgs.push({ id, owner: b.p_owner, from: b.p_from, to: b.p_to, payload: b.p_payload, at: at() }); return id;
      }
      case 'link_pull': {
        own(b.p_owner);
        const until = at() + Math.min(Math.max(b.p_wait_ms || 0, 0), 2200);
        for (;;) {
          const rows = msgs.filter((m) => m.owner === b.p_owner && m.id > (b.p_since || 0) && m.from !== b.p_device && (m.to === b.p_device || m.to === '*'))
            .slice(0, 40).map((m) => ({ id: m.id, from_device: m.from, payload: m.payload, at_ms: m.at }));
          if (rows.length || at() >= until) return rows;
          await delay(20);
        }
      }
      case 'link_beat': {
        own(b.p_owner); presence.set(b.p_owner + '|' + b.p_device, { device: b.p_device, info: b.p_info || '', seen: at(), owner: b.p_owner });
        return [...presence.values()].filter((p) => p.owner === b.p_owner).map((p) => ({ device: p.device, info: p.info, seen_ms: p.seen, ago_ms: at() - p.seen }));
      }
      case 'link_frame_put': own(b.p_owner); frames.set(b.p_owner + '|' + b.p_device, { seq: b.p_seq, payload: b.p_payload, at: at() }); return null;
      case 'link_frame_get': {
        own(b.p_owner);
        const until = at() + Math.min(Math.max(b.p_wait_ms || 0, 0), 2200);
        for (;;) {
          const f = frames.get(b.p_owner + '|' + b.p_device);
          if (f && f.seq > (b.p_after ?? -1)) return [{ seq: f.seq, payload: f.payload, at_ms: f.at }];
          if (at() >= until) return [];
          await delay(15);
        }
      }
      default: throw new Error('unknown function ' + fn);
    }
  }
  return { rpc, stats, msgs, frames, presence };
}
