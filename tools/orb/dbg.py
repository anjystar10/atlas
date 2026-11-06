# usage: dbg.py orb.frag out.png "expr1" "expr2" ...   (expressions evaluated at the end of the inside block)
import sys; sys.path.insert(0, sys.path[0])
from render import *
src = open(sys.argv[1]).read()
src = src.replace('vec3 col = vec3(0.0);', 'vec3 col = vec3(0.0); vec3 DBG = vec3(0.0);')
ims = []
for ex in sys.argv[3:]:
    s = src.replace('col = clamp(col, 0.0, 1.0);', 'col = clamp(col, 0.0, 1.0); DBG = vec3(' + ex + ');')
    s = s.replace('vec3 outc = col * inside + glowCol * glowA;', 'vec3 outc = DBG * inside;')
    ims.append(Orb(s).frame(300, 300, 0.0)[..., :3])
save(np.concatenate(ims, 1), sys.argv[2])
