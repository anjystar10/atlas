# a strip of consecutive frames, simulating Atlas resting, then hearing "Hey Atlas" and talking, then resting
import sys, math; sys.path.insert(0, sys.path[0])
from render import *
o = Orb(open(sys.argv[1]).read()); N = 150
fps = 15; frames = []; flow = 0.0; e = 0.0; l = 0.0
for k in range(fps * 12):
    t = k / fps
    target_e = 1.0 if 4 <= t < 9 else 0.0
    e += (target_e - e) * 0.12
    voice = max(0.0, math.sin(t * 7.0) * math.sin(t * 2.3)) if 5 <= t < 8 else 0.0
    l += (voice - l) * 0.35
    flow += (1 / fps) * (0.035 + 0.09 * e + 0.05 * l)
    if k % 6 == 0: frames.append(over(o.frame(N, N, t, e, l, flow), DARK))
rows = [np.concatenate(frames[i:i + 10], 1) for i in range(0, 30, 10)]
save(np.concatenate(rows, 0), sys.argv[2])
