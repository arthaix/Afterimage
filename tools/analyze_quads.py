"""Afterimage: how compressible is real section geometry?

Reads minecraft/afterimage/samples/sample_*_first.bin (+ _nextN.bin). Vertex format must be BLOCK (28 bytes:
pos 3f, color 4ub, uv 2f, lightmap 2h, little endian). Per quad it checks whether the quad is an axis-aligned
rectangle, whether its UVs are an axis-aligned affine map of the in-plane coordinates, whether colour and light
are constant, the position grid, duplicates and back-to-back pairs, then estimates the size of a lossless
per-quad "rectangle instance" encoding with a full-vertex fallback. For a _first/_next pair the quads only in
the later upload are attributed to LittleTiles (it re-uploads the merged buffer).
"""
import glob, os, re, struct
from collections import Counter
import sys
DIR = sys.argv[1] if len(sys.argv) > 1 else r"afterimage/samples"
VS = 28; QUAD = VS * 4
RECT_BYTES_CONST_LIGHT = 22   # plane/axis 2 + plane coord 2 + min corner 4 + size 4 + colour 4 + uv rect 5..6 + light 1
RECT_BYTES_CORNER_LIGHT = 25  # same with 4 corner light bytes
FALLBACK_BYTES = 64           # 4 vertices x 16 bytes quantized

def verts(q):
    out = []
    for v in range(4):
        o = v * VS
        x, y, z = struct.unpack_from("<3f", q, o)
        col = q[o + 12:o + 16]
        u, w = struct.unpack_from("<2f", q, o + 16)
        sl, bl = struct.unpack_from("<2h", q, o + 24)
        out.append(((x, y, z), bytes(col), (u, w), (sl, bl)))
    return out

def grid(vals):
    for g in (1, 2, 4, 8, 16, 32, 64, 128, 256):
        if all(abs(v * g - round(v * g)) < 1e-4 for v in vals):
            return g
    return 0

def analyze_quad(q):
    vs = verts(q)
    P = [v[0] for v in vs]
    plane = [a for a in range(3) if max(p[a] for p in P) - min(p[a] for p in P) < 1e-5]
    rect = False; uv_affine = False
    if len(plane) == 1:
        a = plane[0]; b, c = [i for i in range(3) if i != a]
        bs = sorted(set(round(p[b], 5) for p in P)); cs = sorted(set(round(p[c], 5) for p in P))
        if len(bs) == 2 and len(cs) == 2:
            corners = {(round(p[b], 5), round(p[c], 5)) for p in P}
            rect = len(corners) == 4
            if rect:
                # u and v must each depend on exactly one in-plane axis, linearly
                ok = True
                for t in range(2):
                    byb = {}; byc = {}
                    for p, v in zip(P, vs):
                        byb.setdefault(round(p[b], 5), set()).add(round(v[2][t], 7))
                        byc.setdefault(round(p[c], 5), set()).add(round(v[2][t], 7))
                    dep_b = all(len(s) == 1 for s in byb.values())
                    dep_c = all(len(s) == 1 for s in byc.values())
                    ok = ok and (dep_b or dep_c)
                uv_affine = ok
    color_const = len({v[1] for v in vs}) == 1
    light_const = len({v[3] for v in vs}) == 1
    g = grid([coord for p in P for coord in p])
    return rect, uv_affine, color_const, light_const, g

def summarize(label, quads):
    n = len(quads)
    if n == 0:
        print("  %s: no quads" % label); return 0, 0
    c = Counter(); grids = Counter(); est = 0
    for q in quads:
        rect, uva, cc, lc, g = analyze_quad(q)
        grids[g] += 1
        c["rect"] += rect; c["rect+uv"] += rect and uva; c["color const"] += cc; c["light const"] += lc
        if rect and uva and cc:
            est += RECT_BYTES_CONST_LIGHT if lc else RECT_BYTES_CORNER_LIGHT
        else:
            est += FALLBACK_BYTES
    dup = sum(k - 1 for k in Counter(quads).values() if k > 1)
    keyset = Counter(tuple(sorted(tuple(round(x, 5) for x in v[0]) for v in verts(q))) for q in quads)
    back = sum(k - 1 for k in keyset.values() if k > 1)
    raw = n * QUAD
    print("  %s: %d quads, %.2f MB" % (label, n, raw / 2**20))
    print("    rect %.1f%%, rect+affine uv %.1f%%, colour const %.1f%%, light const %.1f%%, exact dup %d, same-position (back-to-back) %d"
          % (100.0 * c["rect"] / n, 100.0 * c["rect+uv"] / n, 100.0 * c["color const"] / n, 100.0 * c["light const"] / n, dup, back))
    print("    position grid: " + ", ".join("1/%d:%.1f%%" % (g, 100.0 * k / n) if g else "other:%.1f%%" % (100.0 * k / n) for g, k in sorted(grids.items())))
    print("    estimate: %.2f MB rect-instance encoding = %.1fx smaller than 28 B/vertex, %.1fx smaller than 16 B/vertex"
          % (est / 2**20, raw / max(est, 1), (n * 64) / max(est, 1)))
    return raw, est

def quads_of(path):
    data = open(path, "rb").read()
    if len(data) % QUAD:
        return None
    return [data[i:i + QUAD] for i in range(0, len(data), QUAD)]

def main():
    firsts = sorted(glob.glob(os.path.join(DIR, "sample_*_first.bin")))
    if not firsts:
        print("no samples yet in", DIR); return
    tot_raw = tot_est = 0; lt_raw = 0; all_raw = 0
    for f in firsts:
        m = re.search(r"_vs(\d+)_first", f)
        if not m or int(m.group(1)) != VS:
            print("skip (vertex size not 28):", os.path.basename(f)); continue
        base = f[:-len("_first.bin")]
        nexts = sorted(glob.glob(base + "_next*.bin"))
        final = nexts[-1] if nexts else f
        print("=" * 110)
        print(os.path.basename(base), "uploads:", 1 + len(nexts))
        qf = quads_of(f); qz = quads_of(final)
        if qz is None:
            print("  not quad aligned"); continue
        raw, est = summarize("final buffer", qz)
        tot_raw += raw; tot_est += est; all_raw += raw
        if nexts and qf is not None:
            added = list((Counter(qz) - Counter(qf)).elements())
            lt_raw += len(added) * QUAD
            print("  later uploads added %d quads (%.1f%% of final): attributed to LittleTiles" % (len(added), 100.0 * len(added) / max(len(qz), 1)))
            if added:
                summarize("LittleTiles part", added)
    if tot_raw:
        print("=" * 110)
        print("ALL SAMPLES: %.1f MB raw, estimated %.1f MB rect-instance, ratio %.1fx; LittleTiles share of bytes %.1f%%"
              % (tot_raw / 2**20, tot_est / 2**20, tot_raw / max(tot_est, 1), 100.0 * lt_raw / max(all_raw, 1)))

if __name__ == "__main__":
    main()
