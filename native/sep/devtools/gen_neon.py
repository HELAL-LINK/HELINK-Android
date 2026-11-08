#!/usr/bin/env python3
"""Writes mdxkernel_arm64.s: the voice network's inner loops (mdxkernel.go, mdxwino.go)
with ARMv8 NEON, for phones.

    python3 devtools/gen_neon.py > mdxkernel_arm64.s

Go's arm64 assembler knows few NEON instructions, so they are written as WORDs, encoded
here by llvm-mc (only needed to run this script, not to build). Integer work, branches
and labels are Go's own.

The products are tiles of 6 rows by 16 columns, the 96 sums in v0-v23 (row i, columns
4q..4q+3 in v(4i+q)); 16 columns of B in v24-v27; the 6 numbers of A per step in v28-v29
(or v30-v31: two sets, so that the next step's numbers are loaded while this step's are
used). Each B register is loaded again for the next step as soon as its 6 products are
issued: phones' small cores do not reorder instructions, and a product waiting for its
load would stall them.
"""

import subprocess
import sys

TRIPLE = 'aarch64'
MATTR = '+neon'


class Asm:
    def __init__(self):
        self.lines = []
        self.n_labels = 0

    def go(self, s):
        self.lines.append(('go', s))

    def n(self, s):
        self.lines.append(('n', s))

    def label(self, s):
        self.lines.append(('label', s))

    def raw(self, s):
        self.lines.append(('raw', s))

    def uniq(self, base):
        self.n_labels += 1
        return '%s%d' % (base, self.n_labels)

    def render(self):
        instrs = sorted(set(t for k, t in self.lines if k == 'n'))
        enc = encode(instrs)
        out = []
        for k, t in self.lines:
            if k == 'go':
                out.append('\t' + t)
            elif k == 'n':
                out.append('\tWORD $0x%08x // %s' % (enc[t], t))
            elif k == 'label':
                out.append(t + ':')
            else:
                out.append(t)
        return '\n'.join(out) + '\n'


def encode(instrs):
    src = '\n'.join(instrs) + '\n'
    r = subprocess.run(['llvm-mc', '-triple=' + TRIPLE, '-mattr=' + MATTR, '-show-encoding'],
                       input=src, capture_output=True, text=True)
    if r.returncode != 0 or r.stderr.strip():
        sys.exit('llvm-mc: ' + r.stderr)
    encs = []
    for line in r.stdout.splitlines():
        if 'encoding:' in line:
            bs = [int(b, 16) for b in line.split('encoding:')[1].strip().strip('[]').split(',')]
            assert len(bs) == 4, line
            encs.append(bs[0] | bs[1] << 8 | bs[2] << 16 | bs[3] << 24)
    if len(encs) != len(instrs):
        sys.exit('llvm-mc gave %d encodings for %d instructions' % (len(encs), len(instrs)))
    return dict(zip(instrs, encs))


def acc(i, q):
    return 'v%d' % (4 * i + q)


def a_elem(i, odd):
    """A's number for row i: v28.s[0..3], v29.s[0..1] (or v30, v31)."""
    lo, hi = (30, 31) if odd else (28, 29)
    return 'v%d.s[%d]' % (lo, i) if i < 4 else 'v%d.s[%d]' % (hi, i - 4)


# ---------------------------------------------------------------------------
# The 6x16 product with A packed (6 numbers per step) and B rows anywhere
# ---------------------------------------------------------------------------

def gemm_packed(a, K, A, next_b, B):
    """Sums over K steps (K: register with the count, at least 1, used up). A: register
    pointing at the packed numbers (6 floats per step, moved on). next_b(): instructions
    putting the address of the next step's 16 columns into register B."""
    lastE, lastO, loop, done = a.uniq('lastE'), a.uniq('lastO'), a.uniq('loop'), a.uniq('gdone')
    for r in range(24):
        a.n('movi v%d.2d, #0' % r)
    # Step 0's numbers.
    next_b()
    a.n('ld1 {v24.4s, v25.4s, v26.4s, v27.4s}, [x%d]' % B)
    a.n('ldr q28, [x%d]' % A)
    a.n('ldr d29, [x%d, #16]' % A)
    a.go('ADD $24, R%d, R%d' % (A, A))
    a.go('SUBS $1, R%d, R%d' % (K, K))
    a.go('BEQ ' + lastE)

    def step(odd, load):
        """This step's products (A in set odd); with load, the next step's numbers come
        into the other A set and into v24-v27 meanwhile."""
        if load:
            if odd:
                a.n('ldr q28, [x%d]' % A)
                a.n('ldr d29, [x%d, #16]' % A)
            else:
                a.n('ldr q30, [x%d]' % A)
                a.n('ldr d31, [x%d, #16]' % A)
            a.go('ADD $24, R%d, R%d' % (A, A))
            next_b()
        for q in range(4):
            for i in range(6):
                a.n('fmla %s.4s, v%d.4s, %s' % (acc(i, q), 24 + q, a_elem(i, odd)))
            if load:
                a.n('ldr q%d, [x%d, #%d]' % (24 + q, B, 16 * q))

    a.label(loop)
    step(False, True)
    a.go('SUBS $1, R%d, R%d' % (K, K))
    a.go('BEQ ' + lastO)
    step(True, True)
    a.go('SUBS $1, R%d, R%d' % (K, K))
    a.go('BNE ' + loop)
    a.label(lastE)
    step(False, False)
    a.go('B ' + done)
    a.label(lastO)
    step(True, False)
    a.label(done)


def finish_tile(a, EP):
    """The tileEp at register EP: each row times its scale plus its bias, then the ReLU,
    times or plus the values at drow (flags), written to orow."""
    norelu, nomul, noadd = a.uniq('norelu'), a.uniq('nomul'), a.uniq('noadd')
    a.go('MOVD 144(R%d), R11' % EP)
    a.n('ldr q24, [x%d]' % EP)          # scale 0-3
    a.n('ldr d25, [x%d, #16]' % EP)     # scale 4-5
    a.n('ldur q26, [x%d, #24]' % EP)    # bias 0-3
    a.n('ldr d27, [x%d, #40]' % EP)     # bias 4-5
    for i in range(6):
        sc = 'v24.s[%d]' % i if i < 4 else 'v25.s[%d]' % (i - 4)
        bi = 'v26.s[%d]' % i if i < 4 else 'v27.s[%d]' % (i - 4)
        a.n('dup v28.4s, %s' % bi)
        for q in range(4):
            a.n('fmul %s.4s, %s.4s, %s' % (acc(i, q), acc(i, q), sc))
            a.n('fadd %s.4s, %s.4s, v28.4s' % (acc(i, q), acc(i, q)))
    a.go('TBZ $0, R11, ' + norelu)
    a.n('movi v29.2d, #0')
    for r in range(24):
        a.n('fmax v%d.4s, v%d.4s, v29.4s' % (r, r))
    a.label(norelu)
    for bit, op, lab in ((1, 'fmul', nomul), (2, 'fadd', noadd)):
        a.go('TBZ $%d, R11, %s' % (bit, lab))
        for i in range(6):
            a.go('MOVD %d(R%d), R10' % (96 + 8 * i, EP))
            a.n('ld1 {v28.4s, v29.4s, v30.4s, v31.4s}, [x10]')
            for q in range(4):
                a.n('%s %s.4s, %s.4s, v%d.4s' % (op, acc(i, q), acc(i, q), 28 + q))
        a.label(lab)
    for i in range(6):
        a.go('MOVD %d(R%d), R10' % (48 + 8 * i, EP))
        a.n('st1 {%s.4s, %s.4s, %s.4s, %s.4s}, [x10]' % tuple(acc(i, q) for q in range(4)))


def conv_tile(a):
    a.raw('')
    a.raw('// func convTileNEON(k int, panel, base *float32, offs *int, ep *tileEp)')
    a.raw('// sum[i][j] = Σ_p panel[6p+i] * row_p[j], row_p = base + offs[p] bytes (16 floats each).')
    a.raw('TEXT ·convTileNEON(SB), NOSPLIT, $0-40')
    a.go('MOVD k+0(FP), R0')
    a.go('MOVD panel+8(FP), R1')
    a.go('MOVD base+16(FP), R2')
    a.go('MOVD offs+24(FP), R3')
    a.go('MOVD ep+32(FP), R9')
    a.go('CBZ R0, cdone')

    def next_b():
        a.go('MOVD.P 8(R3), R5')
        a.go('ADD R5, R2, R4')
    gemm_packed(a, 0, 1, next_b, 4)
    a.label('cfinish')
    finish_tile(a, 9)
    a.go('RET')
    # k = 0: zeros, finished as the others.
    a.label('cdone')
    for r in range(24):
        a.n('movi v%d.2d, #0' % r)
    a.go('B cfinish')


def rows_tile(a):
    """6 rows anywhere (pointers) times a packed panel of 16 columns. The rows' numbers
    are read two steps at a time: rows 2r and 2r+1 in v(28+r) (lanes 0-1 and 2-3)."""
    a.raw('')
    a.raw('// func rowsTileNEON(k int, rows *[6]unsafe.Pointer, panel *float32, ep *tileEp)')
    a.raw('// sum[i][j] = Σ_p rows[i][p] * panel[16p+j].')
    a.raw('TEXT ·rowsTileNEON(SB), NOSPLIT, $0-32')
    a.go('MOVD k+0(FP), R0')
    a.go('MOVD rows+8(FP), R1')
    a.go('MOVD panel+16(FP), R2')
    a.go('MOVD ep+24(FP), R9')
    for i in range(6):
        a.go('MOVD %d(R1), R%d' % (8 * i, 12 + i))   # R12-R17
    for r in range(24):
        a.n('movi v%d.2d, #0' % r)
    a.go('LSR $1, R0, R7')

    def el(i, s):
        return 'v%d.s[%d]' % (28 + i // 2, (i % 2) * 2 + s)

    a.go('CBZ R7, rodd')
    a.n('ld1 {v24.4s, v25.4s, v26.4s, v27.4s}, [x2], #64')
    a.label('rpair')
    for r in range(3):
        a.n('ldr d%d, [x%d], #8' % (28 + r, 12 + 2 * r))
        a.n('ld1 {v%d.d}[1], [x%d], #8' % (28 + r, 13 + 2 * r))
    # Step 2p, loading step 2p+1's columns.
    for q in range(4):
        for i in range(6):
            a.n('fmla %s.4s, v%d.4s, %s' % (acc(i, q), 24 + q, el(i, 0)))
        a.n('ldr q%d, [x2, #%d]' % (24 + q, 16 * q))
    a.go('ADD $64, R2, R2')
    a.go('SUBS $1, R7, R7')
    a.go('BEQ rlast')
    # Step 2p+1, loading the next pair's first columns.
    for q in range(4):
        for i in range(6):
            a.n('fmla %s.4s, v%d.4s, %s' % (acc(i, q), 24 + q, el(i, 1)))
        a.n('ldr q%d, [x2, #%d]' % (24 + q, 16 * q))
    a.go('ADD $64, R2, R2')
    a.go('B rpair')
    a.label('rlast')
    for q in range(4):
        for i in range(6):
            a.n('fmla %s.4s, v%d.4s, %s' % (acc(i, q), 24 + q, el(i, 1)))
    a.label('rodd')
    a.go('TBZ $0, R0, rfinish')
    for i in range(6):
        a.n('ld1 {v%d.s}[%d], [x%d]' % (28 + i // 2, (i % 2) * 2, 12 + i))
    a.n('ld1 {v24.4s, v25.4s, v26.4s, v27.4s}, [x2]')
    for q in range(4):
        for i in range(6):
            a.n('fmla %s.4s, v%d.4s, %s' % (acc(i, q), 24 + q, el(i, 0)))
    a.label('rfinish')
    finish_tile(a, 9)
    a.go('RET')


def wino_gemm(a):
    a.raw('')
    a.raw('// func winoGemmNEON(cin int, u *float32, uxs int, v *float32, vrs int, m *float32, mrs int, nb int)')
    a.raw('// For each of the 36 and each 16 of the nb tiles: M[xi] = U[xi] V[xi] (see mdxwino.go).')
    a.raw('TEXT ·winoGemmNEON(SB), NOSPLIT, $0-64')
    a.go('MOVD cin+0(FP), R0')
    a.go('MOVD u+8(FP), R1')
    a.go('MOVD uxs+16(FP), R2')
    a.go('MOVD v+24(FP), R3')
    a.go('MOVD vrs+32(FP), R4')
    a.go('MOVD m+40(FP), R5')
    a.go('MOVD mrs+48(FP), R6')
    a.go('MOVD nb+56(FP), R7')
    a.go('LSL $2, R7, R7')        # nb*4: bytes between the 36
    a.go('MOVD $36, R8')
    a.label('wxloop')
    a.go('MOVD $0, R9')           # byte offset of the 16 tiles within V[xi], M[xi]
    a.label('wjloop')
    a.go('MOVD R1, R10')          # U[xi]
    a.go('ADD R9, R3, R11')       # V[xi] row 0, these tiles
    a.go('MOVD R0, R12')

    def next_b():
        a.go('MOVD R11, R13')
        a.go('ADD R4, R11, R11')
    gemm_packed(a, 12, 10, next_b, 13)
    a.go('ADD R9, R5, R14')
    for i in range(6):
        a.n('st1 {%s.4s, %s.4s, %s.4s, %s.4s}, [x14]' % tuple(acc(i, q) for q in range(4)))
        if i < 5:
            a.go('ADD R6, R14, R14')
    a.go('ADD $64, R9, R9')
    a.go('CMP R7, R9')
    a.go('BLT wjloop')
    a.go('ADD R2, R1, R1')
    a.go('ADD R7, R3, R3')
    a.go('ADD R7, R5, R5')
    a.go('SUBS $1, R8, R8')
    a.go('BNE wxloop')
    a.go('RET')


# ---------------------------------------------------------------------------
# The Winograd transforms, 8 tiles a time (two groups of 4 lanes)
# ---------------------------------------------------------------------------

# v31 holds the constants [2, 4, 5, 8].
C2, C4, C5, C8 = 'v31.s[0]', 'v31.s[1]', 'v31.s[2]', 'v31.s[3]'


def mov(a, d, s):
    a.n('mov %s.16b, %s.16b' % (d, s))


def bt(a, x, out, tmp):
    """out[k] = (Bt x)_k for the 6 registers x (unchanged). tmp: 2 free registers."""
    p, q = tmp
    # t1 = (x4 - 4x2) + (x3 - 4x1), t2 = (x4 - 4x2) - (x3 - 4x1)
    mov(a, p, x[4])
    a.n('fmls %s.4s, %s.4s, %s' % (p, x[2], C4))
    mov(a, q, x[3])
    a.n('fmls %s.4s, %s.4s, %s' % (q, x[1], C4))
    a.n('fadd %s.4s, %s.4s, %s.4s' % (out[1], p, q))
    a.n('fsub %s.4s, %s.4s, %s.4s' % (out[2], p, q))
    # t3 = (x4 - x2) - 2(x1 - x3), t4 = (x4 - x2) + 2(x1 - x3)
    a.n('fsub %s.4s, %s.4s, %s.4s' % (p, x[4], x[2]))
    a.n('fsub %s.4s, %s.4s, %s.4s' % (q, x[1], x[3]))
    a.n('fadd %s.4s, %s.4s, %s.4s' % (q, q, q))
    a.n('fsub %s.4s, %s.4s, %s.4s' % (out[3], p, q))
    a.n('fadd %s.4s, %s.4s, %s.4s' % (out[4], p, q))
    # t0 = 4x0 - 5x2 + x4, t5 = 4x1 - 5x3 + x5
    mov(a, out[0], x[4])
    a.n('fmls %s.4s, %s.4s, %s' % (out[0], x[2], C5))
    a.n('fmla %s.4s, %s.4s, %s' % (out[0], x[0], C4))
    mov(a, out[5], x[5])
    a.n('fmls %s.4s, %s.4s, %s' % (out[5], x[3], C5))
    a.n('fmla %s.4s, %s.4s, %s' % (out[5], x[1], C4))


def at(a, m, out, tmp):
    """out[k] = (At m)_k, k = 0..3, for the 6 registers m (unchanged). tmp: 4 free registers."""
    p, q, r, s = tmp
    a.n('fadd %s.4s, %s.4s, %s.4s' % (p, m[1], m[2]))
    a.n('fsub %s.4s, %s.4s, %s.4s' % (q, m[1], m[2]))
    a.n('fadd %s.4s, %s.4s, %s.4s' % (r, m[3], m[4]))
    a.n('fsub %s.4s, %s.4s, %s.4s' % (s, m[3], m[4]))
    # y0 = m0 + p + r
    a.n('fadd %s.4s, %s.4s, %s.4s' % (out[0], m[0], p))
    a.n('fadd %s.4s, %s.4s, %s.4s' % (out[0], out[0], r))
    # y1 = q + 2s
    mov(a, out[1], q)
    a.n('fmla %s.4s, %s.4s, %s' % (out[1], s, C2))
    # y2 = p + 4r
    mov(a, out[2], p)
    a.n('fmla %s.4s, %s.4s, %s' % (out[2], r, C4))
    # y3 = q + 8s + m5
    a.n('fadd %s.4s, %s.4s, %s.4s' % (out[3], q, m[5]))
    a.n('fmla %s.4s, %s.4s, %s' % (out[3], s, C8))


def pair(a, op, r1, r2, base, base2, off):
    """ldp/stp of q registers r1, r2 at base+off (base2 = base + 1024 for the far ones)."""
    if off > 1008:
        base, off = base2, off - 1024
    a.n('%s q%s, q%s, [x%d, #%d]' % (op, r1[1:], r2[1:], base, off))


def wino_in(a):
    a.raw('')
    a.raw('// func winoInNEON(src *float32, rs int, dst *float32, xs int, tmp *float32, pf *float32)')
    a.raw('// See winoInGo: the 36 values V = Bt d B of 8 tiles (6 rows of 40 floats at src, row')
    a.raw('// stride rs bytes) to dst + xi*xs (8 floats each); tmp: 288 floats of scratch; pf: the')
    a.raw('// same rows of the channel to come, asked from memory now.')
    a.raw('TEXT ·winoInNEON(SB), NOSPLIT, $0-48')
    a.go('MOVD src+0(FP), R0')
    a.go('MOVD rs+8(FP), R1')
    a.go('MOVD dst+16(FP), R2')
    a.go('MOVD xs+24(FP), R3')
    a.go('MOVD tmp+32(FP), R4')
    a.go('MOVD pf+40(FP), R5')
    a.go('ADD $1024, R4, R6')     # tmp + 1024, for the far ones
    for r in range(6):
        for off in (0, 64, 128):
            a.n('prfm pldl1keep, [x5, #%d]' % off)
        if r < 5:
            a.go('ADD R1, R5, R5')
    a.go('MOVD $winoC<>(SB), R7')
    a.n('ldr q31, [x7]')
    a.go('MOVD R0, R7')
    xa = ['v8', 'v0', 'v1', 'v2', 'v3', 'v10']
    xb = ['v9', 'v4', 'v5', 'v6', 'v7', 'v11']
    ta = ['v%d' % (12 + k) for k in range(6)]
    tb = ['v%d' % (18 + k) for k in range(6)]
    for r in range(6):
        # Columns 4..35: the 4 phases of tiles 0-3 (v0-v3) and 4-7 (v4-v7).
        a.go('ADD $16, R7, R8')
        a.n('ld4 {v0.4s, v1.4s, v2.4s, v3.4s}, [x8], #64')
        a.n('ld4 {v4.4s, v5.4s, v6.4s, v7.4s}, [x8]')
        a.n('ldr q16, [x7]')
        a.n('ldr q17, [x7, #144]')
        # x0 = columns 4m+3: phase 3 one lane up (column 3 first); x5 = columns 4m+8:
        # phase 0 one lane down (column 36 last).
        a.n('ext v8.16b, v16.16b, v3.16b, #12')
        a.n('ext v9.16b, v3.16b, v7.16b, #12')
        a.n('ext v10.16b, v0.16b, v4.16b, #4')
        a.n('ext v11.16b, v4.16b, v17.16b, #4')
        bt(a, xa, ta, ['v24', 'v25'])
        bt(a, xb, tb, ['v26', 'v27'])
        for k in range(6):
            pair(a, 'stp', ta[k], tb[k], 4, 6, 32 * (6 * r + k))
        if r < 5:
            a.go('ADD R1, R7, R7')
    # Down the rows: V[6i+j] = (Bt T)[i][j], T[r][j] in tmp; R10-R15 = dst + 6i*xs + j*xs.
    a.go('MOVD $6, R9')
    a.go('MUL R9, R3, R9')        # 6*xs
    a.go('MOVD R2, R10')
    for i in range(1, 6):
        a.go('ADD R9, R%d, R%d' % (9 + i, 10 + i))
    for j in range(6):
        for r in range(6):
            pair(a, 'ldp', 'v%d' % r, 'v%d' % (6 + r), 4, 6, 32 * (6 * r + j))
        bt(a, ['v%d' % k for k in range(6)], ta, ['v24', 'v25'])
        bt(a, ['v%d' % (6 + k) for k in range(6)], tb, ['v26', 'v27'])
        for i in range(6):
            a.n('stp q%s, q%s, [x%d]' % (ta[i][1:], tb[i][1:], 10 + i))
            if j < 5:
                a.go('ADD R3, R%d, R%d' % (10 + i, 10 + i))
    a.go('RET')


def wino_out(a):
    a.raw('')
    a.raw('// func winoOutNEON(src *float32, xs int, bias *float32, dst *float32, rs int, tmp *float32)')
    a.raw('// See winoOutGo: M[xi] of 8 tiles at src + xi*xs; the 4x4 outputs Y = At M A of each, plus')
    a.raw('// the bias, ReLU, to dst (4 rows of 32 floats, row stride rs bytes); tmp: 320 floats.')
    a.raw('TEXT ·winoOutNEON(SB), NOSPLIT, $0-48')
    a.go('MOVD src+0(FP), R0')
    a.go('MOVD xs+8(FP), R1')
    a.go('MOVD bias+16(FP), R2')
    a.go('MOVD dst+24(FP), R3')
    a.go('MOVD rs+32(FP), R4')
    a.go('MOVD tmp+40(FP), R5')
    a.go('ADD $1024, R5, R6')
    a.go('MOVD $winoC<>(SB), R7')
    a.n('ldr q31, [x7]')
    ya = ['v%d' % (16 + k) for k in range(4)]
    yb = ['v%d' % (20 + k) for k in range(4)]
    # Along j: R[i][b] = (At M[i][.])[b], into tmp[4i+b].
    a.go('MOVD R0, R7')
    for i in range(6):
        for j in range(6):
            a.n('ldp q%d, q%d, [x7]' % (j, 6 + j))
            a.go('ADD R1, R7, R7')
        at(a, ['v%d' % k for k in range(6)], ya, ['v24', 'v25', 'v26', 'v27'])
        at(a, ['v%d' % (6 + k) for k in range(6)], yb, ['v24', 'v25', 'v26', 'v27'])
        for b in range(4):
            pair(a, 'stp', ya[b], yb[b], 5, 6, 32 * (4 * i + b))
    # Along i: Y[a][b] = (At R[.][b])[a], into tmp[24 + 4a + b].
    for b in range(4):
        for i in range(6):
            pair(a, 'ldp', 'v%d' % i, 'v%d' % (6 + i), 5, 6, 32 * (4 * i + b))
        at(a, ['v%d' % k for k in range(6)], ya, ['v24', 'v25', 'v26', 'v27'])
        at(a, ['v%d' % (6 + k) for k in range(6)], yb, ['v24', 'v25', 'v26', 'v27'])
        for r in range(4):
            pair(a, 'stp', ya[r], yb[r], 5, 6, 32 * (24 + 4 * r + b))
    # Each output row: plus the bias, ReLU, the 4 phases interleaved: tiles 0-3 are columns
    # 0-15, tiles 4-7 columns 16-31.
    a.n('ld1r {v30.4s}, [x2]')
    a.n('movi v29.2d, #0')
    for r in range(4):
        for b in range(4):
            pair(a, 'ldp', 'v%d' % b, 'v%d' % (4 + b), 5, 6, 32 * (24 + 4 * r + b))
        for reg in range(8):
            a.n('fadd v%d.4s, v%d.4s, v30.4s' % (reg, reg))
            a.n('fmax v%d.4s, v%d.4s, v29.4s' % (reg, reg))
        a.n('st4 {v0.4s, v1.4s, v2.4s, v3.4s}, [x3]')
        a.go('ADD $64, R3, R8')
        a.n('st4 {v4.4s, v5.4s, v6.4s, v7.4s}, [x8]')
        if r < 3:
            a.go('ADD R4, R3, R3')
    a.go('RET')


HEAD = '''#include "textflag.h"

// Generated by devtools/gen_neon.py: the voice network's inner loops with NEON (see
// mdxkernel.go, mdxwino.go). The WORDs are NEON instructions, written beside them.

DATA winoC<>+0(SB)/4, $2.0
DATA winoC<>+4(SB)/4, $4.0
DATA winoC<>+8(SB)/4, $5.0
DATA winoC<>+12(SB)/4, $8.0
GLOBL winoC<>(SB), RODATA|NOPTR, $16
'''


def main():
    a = Asm()
    conv_tile(a)
    rows_tile(a)
    wino_gemm(a)
    wino_in(a)
    wino_out(a)
    sys.stdout.write(HEAD + a.render())


main()
