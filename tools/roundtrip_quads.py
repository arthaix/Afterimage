"""Bit-exact round trip of real section quads through a per-quad rectangle encoding.
For each quad: rectangle corners on a 1/256-block grid (u16), vertex start corner + winding, separable UV ends,
colour and light either constant (1 value) or per corner. Reconstructs the 4 vertices and compares bytes exactly.
Variants: UV stored as raw float32 (always exact) or quantized to 1/65536 (u16) or 1/1048576 (u20).
"""
import glob, os, struct
from collections import Counter
import sys
DIR = sys.argv[1] if len(sys.argv) > 1 else r"afterimage/samples"
V = struct.Struct("<3f4B2f2h"); VS = 28; QUAD = 112
f32 = struct.Struct("<f")
def exact_q(x, scale):
    q = round(x * scale)
    return f32.pack(q / scale) == f32.pack(x), q
files = []
for first in sorted(glob.glob(os.path.join(DIR, "sample_*_first.bin"))):
    if "_vs28_" not in first: continue
    base = first[:-len("_first.bin")]
    nexts = sorted(glob.glob(base + "_next*.bin"))
    files.append(nexts[-1] if nexts else first)
st = Counter(); bytes_lossless = 0; bytes_u16 = 0; nq = 0; max_uv_err_texels = 0.0
for path in files:
    data = open(path, "rb").read()
    if len(data) % QUAD: continue
    vs = list(V.iter_unpack(data))
    for qi in range(len(data) // QUAD):
        q = vs[qi * 4:qi * 4 + 4]
        nq += 1
        P = [v[0:3] for v in q]
        plane = [a for a in range(3) if P[0][a] == P[1][a] == P[2][a] == P[3][a]]
        if len(plane) != 1:
            st["not rect"] += 1; bytes_lossless += 112; bytes_u16 += 112; continue
        a = plane[0]; b, c = [i for i in range(3) if i != a]
        bset = sorted({p[b] for p in P}); cset = sorted({p[c] for p in P})
        if len(bset) != 2 or len(cset) != 2 or len({(p[b], p[c]) for p in P}) != 4:
            st["not rect"] += 1; bytes_lossless += 112; bytes_u16 += 112; continue
        # position exactness on 1/256 grid
        pos_ok = all(exact_q(x, 256)[0] for x in (P[0][a], bset[0], bset[1], cset[0], cset[1]))
        st["pos exact 1/256"] += pos_ok
        # corner order reproducible: corners are (b-end,c-end) pairs; order is a cycle -> start + winding
        corners = [(bset.index(p[b]), cset.index(p[c])) for p in P]
        cyc = [(0, 0), (1, 0), (1, 1), (0, 1)]
        ok_order = False
        for start in range(4):
            for d in (1, -1):
                if [cyc[(start + d * k) % 4] for k in range(4)] == corners:
                    ok_order = True
        st["order encodable"] += ok_order
        # separable uv: each of u,v determined by b-end or by c-end
        uv_ok = True; uv_vals = []
        for t in (0, 1):
            by_b = {}; by_c = {}
            for (bi, ci), v in zip(corners, q):
                by_b.setdefault(bi, set()).add(v[7 + t]); by_c.setdefault(ci, set()).add(v[7 + t])
            if all(len(s) == 1 for s in by_b.values()):
                uv_vals += [next(iter(by_b[0])), next(iter(by_b[1]))]
            elif all(len(s) == 1 for s in by_c.values()):
                uv_vals += [next(iter(by_c[0])), next(iter(by_c[1]))]
            else:
                uv_ok = False
        st["uv separable"] += uv_ok
        uv16 = uv_ok and all(exact_q(u, 65536)[0] for u in uv_vals)
        uv20 = uv_ok and all(exact_q(u, 1048576)[0] for u in uv_vals)
        st["uv exact 1/65536"] += uv16; st["uv exact 1/2^20"] += uv20
        if uv_ok:
            for u in uv_vals:
                err = abs(round(u * 65536) / 65536 - u) * 16384
                max_uv_err_texels = max(max_uv_err_texels, err)
        col_const = len({v[3:7] for v in q}) == 1
        light_const = len({v[9:11] for v in q}) == 1
        st["colour const"] += col_const; st["light const"] += light_const
        if not (pos_ok and ok_order and uv_ok):
            st["fallback"] += 1; bytes_lossless += 112; bytes_u16 += 112; continue
        head = 1 + 2 + 8 + 1          # plane+flags, plane coord, 4 corner coords, order/uv-axis bits
        colour = 4 if col_const else 16
        light = 4 if light_const else 16
        bytes_lossless += head + 16 + colour + light
        bytes_u16 += head + (8 if uv16 else 16) + colour + light
        st["encodable"] += 1
n = max(nq, 1)
print("files %d, quads %d, raw %.1f MB" % (len(files), nq, nq * 112 / 2**20))
for k in ("not rect", "pos exact 1/256", "order encodable", "uv separable", "uv exact 1/65536", "uv exact 1/2^20",
          "colour const", "light const", "encodable", "fallback"):
    print("  %-18s %6.2f%%" % (k, 100.0 * st[k] / n))
print("  max uv error if quantized to 1/65536: %.4f texel of a 16384 atlas" % max_uv_err_texels)
print("lossless (float uv): %.1f B/quad, %.2fx smaller than 28 B/vertex" % (bytes_lossless / n, nq * 112.0 / bytes_lossless))
print("u16 uv where exact:  %.1f B/quad, %.2fx smaller than 28 B/vertex" % (bytes_u16 / n, nq * 112.0 / bytes_u16))
for label, bq in (("lossless", bytes_lossless / n), ("u16 uv", bytes_u16 / n)):
    print("PROJECTED city (18.6 GB raw) with %s encoding: %.2f GB" % (label, 18.6 * bq / 112))
