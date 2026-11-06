import sys; sys.path.insert(0, sys.path[0])
from render import *
o = Orb(open(sys.argv[1]).read()); N = 300
rows = [np.concatenate([over(o.frame(N, N, t), DARK) for t in (0, 9, 23, 41)], 1),
        np.concatenate([over(o.frame(N, N, t, e, l), DARK) for t, e, l in ((5, 1, 0), (5.3, 1, 0.5), (5.6, 1, 1), (60, 0.5, 0.2))], 1)]
r3 = np.zeros((N, N * 4, 3)) + DARK; x = 0
for im in (over(o.frame(126, 126, 3), DARK), over(o.frame(126, 126, 3), LIGHT), over(o.frame(200, 200, 12), LIGHT)):
    h, w = im.shape[:2]; r3[:h, x:x + w] = im; x += w + 10
rows.append(r3)
save(np.concatenate(rows, 0), sys.argv[2])
