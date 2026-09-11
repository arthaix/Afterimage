"""Afterimage phase 0: explain verifier mismatches.

For every mismatch_*_A.bin / _B.bin pair in minecraft/afterimage/mismatch the buffers are split into
BLOCK-format quads (4 vertices x 28 bytes: pos 3f, color 4ub, uv 2f, lightmap 2s, little endian)
and compared as multisets. Prints how many quads differ and which vertex fields changed, which is
what decides whether a cache can ever be byte-exact for that section.
"""
import glob
import os
import struct
from collections import Counter

import sys
DIR = sys.argv[1] if len(sys.argv) > 1 else r"afterimage/mismatch"
VERTEX = 28
QUAD = VERTEX * 4
FIELDS = ("pos", "color", "uv", "light")


def quads(data):
    if len(data) % QUAD:
        return None
    return [data[i:i + QUAD] for i in range(0, len(data), QUAD)]


def decode(q):
    out = []
    for v in range(4):
        o = v * VERTEX
        x, y, z = struct.unpack_from("<3f", q, o)
        r, g, b, a = struct.unpack_from("<4B", q, o + 12)
        u, w = struct.unpack_from("<2f", q, o + 16)
        sl, bl = struct.unpack_from("<2h", q, o + 24)
        out.append(((round(x, 4), round(y, 4), round(z, 4)), (r, g, b, a), (round(u, 6), round(w, 6)), (sl, bl)))
    return out


def main():
    for a_path in sorted(glob.glob(os.path.join(DIR, "*_A.bin"))):
        base = a_path[:-6]
        b_path = base + "_B.bin"
        meta = open(base + ".txt").read().strip() if os.path.exists(base + ".txt") else ""
        print("=" * 100)
        print(os.path.basename(base))
        print(meta)
        if not os.path.exists(b_path):
            print("  B missing")
            continue
        a = open(a_path, "rb").read()
        b = open(b_path, "rb").read()
        qa, qb = quads(a), quads(b)
        if qa is None or qb is None:
            print(f"  not quad-aligned: A {len(a)} B {len(b)} bytes")
            continue
        ca, cb = Counter(qa), Counter(qb)
        only_a = list((ca - cb).elements())
        only_b = list((cb - ca).elements())
        print(f"  quads A {len(qa)}, B {len(qb)}, only in A {len(only_a)}, only in B {len(only_b)}")
        if not only_a or not only_b:
            continue
        # pair differing quads by nearest position to see which fields moved
        field_changes = Counter()
        pairs = 0
        remaining = list(only_b)
        for qa_bytes in only_a[:2000]:
            va = decode(qa_bytes)
            key_a = sorted(v[0] for v in va)
            best, best_i = None, -1
            for i, qb_bytes in enumerate(remaining[:4000]):
                if sorted(v[0] for v in decode(qb_bytes)) == key_a:
                    best, best_i = qb_bytes, i
                    break
            if best is None:
                field_changes["no same-position partner"] += 1
                continue
            remaining.pop(best_i)
            vb = decode(best)
            pairs += 1
            sa = sorted(va)
            sb = sorted(vb)
            for f, name in enumerate(FIELDS):
                if [v[f] for v in sa] != [v[f] for v in sb]:
                    field_changes[name] += 1
            if pairs <= 3:
                print("  example A:", sa)
                print("  example B:", sb)
        print(f"  paired {pairs}; changed fields: {dict(field_changes)}")


if __name__ == "__main__":
    main()
