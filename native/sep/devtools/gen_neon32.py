#!/usr/bin/env python3
"""Writes mdxkernel_arm.s: the voice network's inner loops (mdxkernel.go, mdxwino.go) with
ARMv7 NEON, for 32-bit phones (armeabi-v7a).

    python3 devtools/gen_neon32.py > mdxkernel_arm.s

As gen_neon.py, the NEON instructions are WORDs encoded by llvm-mc. ARMv7 has 16 vector
registers instead of 32, so a tile of 6 rows by 16 columns is made in two halves of 8
columns: the 48 sums of a half in q4-q15 (row i in q(4+2i), q(5+2i)), 8 columns of B in
q2-q3, the 6 numbers of A per step in d0-d2 (a scalar operand must be in d0-d15).
Products and sums are separate operations (VMLA), as ARMv7's NEON has no fused one by a
scalar.

Registers: R0-R8 and R12 (R9 is avoided, R10 is g, R11 the assembler's temporary).
tileEp on 32 bits: scale at 0, bias at 24, orow at 48, drow at 72, flags at 96.
"""

import subprocess
import sys

TRIPLE = 'armv7a'
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


def acc(i, h):
    return 'q%d' % (4 + 2 * i + h)


def acc_d(i):
    """The 4 d registers of row i's two halves (consecutive)."""
    b = 8 + 4 * i
    return '{d%d, d%d, d%d, d%d}' % (b, b + 1, b + 2, b + 3)


def a_scalar(i):
    return 'd%d[%d]' % (i // 2, i % 2)


def zero_acc(a):
    for i in range(6):
        for h in range(2):
            a.n('vmov.i32 %s, #0' % acc(i, h))


def mla_step(a):
    """One step of the half tile: B in q2 (columns 0-3) and q3 (4-7), A in d0-d2."""
    for h in range(2):
        for i in range(6):
            a.n('vmla.f32 %s, q%d, %s' % (acc(i, h), 2 + h, a_scalar(i)))


def finish_half(a, EP, half):
    """The tileEp (register EP) for columns 8*half..8*half+7: times scale plus bias, ReLU,
    times or plus drow (flags), written to orow."""
    norelu, nomul, noadd = a.uniq('norelu'), a.uniq('nomul'), a.uniq('noadd')
    a.go('MOVW 96(R%d), R8' % EP)
    a.go('MOVW R%d, R5' % EP)
    a.n('vld1.32 {d0, d1, d2}, [r5]')          # scale 0-5
    a.go('ADD $24, R%d, R5' % EP)
    a.n('vld1.32 {d3, d4, d5}, [r5]')          # bias 0-5
    for i in range(6):
        bias = 'd%d[%d]' % (3 + i // 2, i % 2)
        a.n('vdup.32 q3, %s' % bias)
        for h in range(2):
            a.n('vmul.f32 %s, %s, %s' % (acc(i, h), acc(i, h), a_scalar(i)))
            a.n('vadd.f32 %s, %s, q3' % (acc(i, h), acc(i, h)))
    a.go('TST $1, R8')
    a.go('BEQ ' + norelu)
    a.n('vmov.i32 q3, #0')
    for i in range(6):
        for h in range(2):
            a.n('vmax.f32 %s, %s, q3' % (acc(i, h), acc(i, h)))
    a.label(norelu)
    for bit, op, lab in ((2, 'vmul.f32', nomul), (4, 'vadd.f32', noadd)):
        a.go('TST $%d, R8' % bit)
        a.go('BEQ ' + lab)
        for i in range(6):
            a.go('MOVW %d(R%d), R5' % (72 + 4 * i, EP))
            if half:
                a.go('ADD $32, R5, R5')
            a.n('vld1.32 {d0, d1, d2, d3}, [r5]')
            for h in range(2):
                a.n('%s %s, %s, q%d' % (op, acc(i, h), acc(i, h), h))
        a.label(lab)
    for i in range(6):
        a.go('MOVW %d(R%d), R5' % (48 + 4 * i, EP))
        if half:
            a.go('ADD $32, R5, R5')
        a.n('vst1.32 %s, [r5]' % acc_d(i))


def conv_tile(a):
    a.raw('')
    a.raw('// func convTileNEON(k int, panel, base *float32, offs *int, ep *tileEp)')
    a.raw('// sum[i][j] = Σ_p panel[6p+i] * row_p[j], row_p = base + offs[p] bytes (16 floats each).')
    a.raw('TEXT ·convTileNEON(SB), NOSPLIT, $0-20')
    a.go('MOVW ep+16(FP), R12')
    for half in range(2):
        loop, fin = a.uniq('cloop'), a.uniq('cfin')
        a.go('MOVW k+0(FP), R0')
        a.go('MOVW panel+4(FP), R1')
        a.go('MOVW base+8(FP), R2')
        a.go('MOVW offs+12(FP), R3')
        if half:
            a.go('ADD $32, R2, R2')
        zero_acc(a)
        a.go('CMP $0, R0')
        a.go('BEQ ' + fin)
        a.label(loop)
        a.go('MOVW.P 4(R3), R5')
        a.go('ADD R5, R2, R4')
        a.n('vld1.32 {d0, d1, d2}, [r1]!')
        a.n('vld1.32 {d4, d5, d6, d7}, [r4]')
        mla_step(a)
        a.go('SUB.S $1, R0, R0')
        a.go('BNE ' + loop)
        a.label(fin)
        finish_half(a, 12, half)
    a.go('RET')


def rows_tile(a):
    """6 rows anywhere times a packed panel of 16 columns, in halves of 8 columns. The rows'
    numbers two steps at a time: row i in d(i) (lane 0: step 2p, lane 1: 2p+1); B one
    vector at a time in q3."""
    a.raw('')
    a.raw('// func rowsTileNEON(k int, rows *[6]unsafe.Pointer, panel *float32, ep *tileEp)')
    a.raw('// sum[i][j] = Σ_p rows[i][p] * panel[16p+j].')
    a.raw('TEXT ·rowsTileNEON(SB), NOSPLIT, $0-16')
    a.go('MOVW ep+12(FP), R12')
    for half in range(2):
        pair, odd, fin = a.uniq('rpair'), a.uniq('rodd'), a.uniq('rfin')
        a.go('MOVW rows+4(FP), R0')
        for i in range(6):
            a.go('MOVW %d(R0), R%d' % (4 * i, 1 + i))   # R1-R6
        a.go('MOVW panel+8(FP), R7')
        if half:
            a.go('ADD $32, R7, R7')
        a.go('MOVW k+0(FP), R0')
        zero_acc(a)
        a.go('MOVW R0>>1, R8')
        a.go('CMP $0, R8')
        a.go('BEQ ' + odd)
        a.label(pair)
        for i in range(6):
            a.n('vld1.32 {d%d}, [r%d]!' % (i, 1 + i))
        for s in range(2):
            for h in range(2):
                a.n('vld1.32 {d6, d7}, [r7]')
                a.go('ADD $%d, R7, R7' % (16 if h == 0 else 48))
                for i in range(6):
                    a.n('vmla.f32 %s, q3, d%d[%d]' % (acc(i, h), i, s))
        a.go('SUB.S $1, R8, R8')
        a.go('BNE ' + pair)
        a.label(odd)
        a.go('TST $1, R0')
        a.go('BEQ ' + fin)
        for i in range(6):
            a.n('vld1.32 {d%d[0]}, [r%d]' % (i, 1 + i))
        for h in range(2):
            a.n('vld1.32 {d6, d7}, [r7]')
            a.go('ADD $16, R7, R7')
            for i in range(6):
                a.n('vmla.f32 %s, q3, d%d[0]' % (acc(i, h), i))
        a.label(fin)
        finish_half(a, 12, half)
    a.go('RET')


def wino_gemm(a):
    a.raw('')
    a.raw('// func winoGemmNEON(cin int, u *float32, uxs int, v *float32, vrs int, m *float32, mrs int, nb int)')
    a.raw('// For each of the 36 and each 16 of the nb tiles: M[xi] = U[xi] V[xi] (see mdxwino.go).')
    a.raw('TEXT ·winoGemmNEON(SB), NOSPLIT, $0-32')
    a.go('MOVW nb+28(FP), R7')
    a.go('MOVW R7<<2, R7')          # nb*4: bytes between the 36
    a.go('MOVW $36, R8')
    a.go('MOVW u+4(FP), R1')        # U[xi]
    a.go('MOVW v+12(FP), R3')       # V[xi]
    a.go('MOVW m+20(FP), R5')       # M[xi]
    a.label('wxloop')
    a.go('MOVW $0, R6')             # byte offset of the 8 tiles within V[xi], M[xi]
    a.label('wjloop')
    loop = a.uniq('wkloop')
    a.go('MOVW R1, R2')
    a.go('ADD R6, R3, R4')
    a.go('MOVW cin+0(FP), R0')
    a.go('MOVW vrs+16(FP), R12')
    zero_acc(a)
    a.label(loop)
    a.n('vld1.32 {d0, d1, d2}, [r2]!')
    a.n('vld1.32 {d4, d5, d6, d7}, [r4], r12')
    mla_step(a)
    a.go('SUB.S $1, R0, R0')
    a.go('BNE ' + loop)
    a.go('ADD R6, R5, R4')
    a.go('MOVW mrs+24(FP), R12')
    for i in range(6):
        a.n('vst1.32 %s, [r4], r12' % acc_d(i))
    a.go('ADD $32, R6, R6')
    a.go('CMP R7, R6')
    a.go('BLT wjloop')
    a.go('MOVW uxs+8(FP), R0')
    a.go('ADD R0, R1, R1')
    a.go('ADD R7, R3, R3')
    a.go('ADD R7, R5, R5')
    a.go('SUB.S $1, R8, R8')
    a.go('BNE wxloop')
    a.go('RET')


# ---------------------------------------------------------------------------
# The Winograd transforms, 8 tiles a call, 4 at a time. q0 holds [2, 4, 5, 8]: d0[0] = 2,
# d0[1] = 4, d1[0] = 5, d1[1] = 8.
# ---------------------------------------------------------------------------

C2, C4, C5, C8 = 'd0[0]', 'd0[1]', 'd1[0]', 'd1[1]'


def mov(a, d, s):
    a.n('vmov %s, %s' % (d, s))


def bt(a, x, out, tmp):
    """out[k] = (Bt x)_k for the 6 q registers x (unchanged). tmp: 2 free q registers."""
    p, q = tmp
    mov(a, p, x[4])
    a.n('vmls.f32 %s, %s, %s' % (p, x[2], C4))
    mov(a, q, x[3])
    a.n('vmls.f32 %s, %s, %s' % (q, x[1], C4))
    a.n('vadd.f32 %s, %s, %s' % (out[1], p, q))
    a.n('vsub.f32 %s, %s, %s' % (out[2], p, q))
    a.n('vsub.f32 %s, %s, %s' % (p, x[4], x[2]))
    a.n('vsub.f32 %s, %s, %s' % (q, x[1], x[3]))
    a.n('vadd.f32 %s, %s, %s' % (q, q, q))
    a.n('vsub.f32 %s, %s, %s' % (out[3], p, q))
    a.n('vadd.f32 %s, %s, %s' % (out[4], p, q))
    mov(a, out[0], x[4])
    a.n('vmls.f32 %s, %s, %s' % (out[0], x[2], C5))
    a.n('vmla.f32 %s, %s, %s' % (out[0], x[0], C4))
    mov(a, out[5], x[5])
    a.n('vmls.f32 %s, %s, %s' % (out[5], x[3], C5))
    a.n('vmla.f32 %s, %s, %s' % (out[5], x[1], C4))


def at(a, m, out, tmp):
    """out[k] = (At m)_k, k = 0..3, for the 6 q registers m (unchanged). tmp: 4 q registers."""
    p, q, r, s = tmp
    a.n('vadd.f32 %s, %s, %s' % (p, m[1], m[2]))
    a.n('vsub.f32 %s, %s, %s' % (q, m[1], m[2]))
    a.n('vadd.f32 %s, %s, %s' % (r, m[3], m[4]))
    a.n('vsub.f32 %s, %s, %s' % (s, m[3], m[4]))
    a.n('vadd.f32 %s, %s, %s' % (out[0], m[0], p))
    a.n('vadd.f32 %s, %s, %s' % (out[0], out[0], r))
    mov(a, out[1], q)
    a.n('vmla.f32 %s, %s, %s' % (out[1], s, C2))
    mov(a, out[2], p)
    a.n('vmla.f32 %s, %s, %s' % (out[2], r, C4))
    a.n('vadd.f32 %s, %s, %s' % (out[3], q, m[5]))
    a.n('vmla.f32 %s, %s, %s' % (out[3], s, C8))


def qd(qn):
    """{dA, dB} of q register qn."""
    n = int(qn[1:])
    return '{d%d, d%d}' % (2 * n, 2 * n + 1)


def wino_in(a):
    a.raw('')
    a.raw('// func winoInNEON(src *float32, rs int, dst *float32, xs int, tmp *float32, pf *float32)')
    a.raw('// See winoInGo: the 36 values V = Bt d B of 8 tiles (6 rows of 40 floats at src, row')
    a.raw('// stride rs bytes) to dst + xi*xs (8 floats each); tmp: 144 floats of scratch.')
    a.raw('TEXT ·winoInNEON(SB), NOSPLIT, $0-24')
    a.go('MOVW pf+20(FP), R5')
    a.go('MOVW rs+4(FP), R1')
    for r in range(6):
        for off in (0, 64, 128):
            a.n('pld [r5, #%d]' % off)
        if r < 5:
            a.go('ADD R1, R5, R5')
    a.go('MOVW $winoC<>(SB), R5')
    a.n('vld1.32 {d0, d1}, [r5]')
    xs = ['q5', 'q1', 'q2', 'q3', 'q4', 'q6']
    outs = ['q%d' % (7 + k) for k in range(6)]
    for g in range(2):
        # The 6 rows: T[r][k] = (Bt d_r)[k] into tmp + (6r+k)*16.
        a.go('MOVW src+0(FP), R7')
        a.go('MOVW tmp+16(FP), R4')
        a.go('MOVW rs+4(FP), R1')
        for r in range(6):
            a.go('ADD $%d, R7, R8' % (16 + 64 * g))
            a.n('vld4.32 {d2, d4, d6, d8}, [r8]!')
            a.n('vld4.32 {d3, d5, d7, d9}, [r8]')
            a.go('ADD $%d, R7, R8' % (64 * g))
            a.n('vld1.32 {d10, d11}, [r8]')
            a.go('ADD $80, R8, R8')
            a.n('vld1.32 {d12, d13}, [r8]')
            # x0 = columns 4m+3: phase 3 one lane up; x5 = columns 4m+8: phase 0 one lane down.
            a.n('vext.32 q5, q5, q4, #3')
            a.n('vext.32 q6, q1, q6, #1')
            bt(a, xs, outs, ['q13', 'q14'])
            a.n('vst1.32 {d14, d15, d16, d17}, [r4]!')
            a.n('vst1.32 {d18, d19, d20, d21}, [r4]!')
            a.n('vst1.32 {d22, d23, d24, d25}, [r4]!')
            if r < 5:
                a.go('ADD R1, R7, R7')
        # Down the rows: V[6i+j] = (Bt T)[i][j] to dst + (6i+j)*xs + 16g; P[i] = those of j.
        P = [1, 2, 3, 5, 6, 7]
        a.go('MOVW xs+12(FP), R12')
        a.go('MOVW dst+8(FP), R1')
        if g:
            a.go('ADD $16, R1, R1')
        a.go('MOVW R12<<1, R8')
        a.go('ADD R12<<2, R8, R8')      # 6*xs
        for i in range(1, 6):
            a.go('ADD R8, R%d, R%d' % (P[i - 1], P[i]))
        a.go('MOVW $96, R8')
        for j in range(6):
            a.go('MOVW tmp+16(FP), R4')
            a.go('ADD $%d, R4, R4' % (16 * j))
            for r in range(6):
                a.n('vld1.32 %s, [r4], r8' % qd('q%d' % (1 + r)))
            bt(a, ['q%d' % (1 + r) for r in range(6)], outs, ['q13', 'q14'])
            for i in range(6):
                a.n('vst1.32 %s, [r%d], r12' % (qd(outs[i]), P[i]))
    a.go('RET')


def wino_out(a):
    a.raw('')
    a.raw('// func winoOutNEON(src *float32, xs int, bias *float32, dst *float32, rs int, tmp *float32)')
    a.raw('// See winoOutGo: M[xi] of 8 tiles at src + xi*xs; the 4x4 outputs Y = At M A of each, plus')
    a.raw('// the bias, ReLU, to dst (4 rows of 32 floats, row stride rs bytes); tmp: 160 floats.')
    a.raw('TEXT ·winoOutNEON(SB), NOSPLIT, $0-24')
    a.go('MOVW $winoC<>(SB), R5')
    a.n('vld1.32 {d0, d1}, [r5]')
    a.go('MOVW xs+4(FP), R12')
    m = ['q%d' % (1 + k) for k in range(6)]
    outs = ['q%d' % (7 + k) for k in range(4)]
    tmps = ['q11', 'q12', 'q13', 'q14']
    for g in range(2):
        # Along j: R[i][b] = (At M[i][.])[b] into tmp + (4i+b)*16.
        a.go('MOVW src+0(FP), R7')
        if g:
            a.go('ADD $16, R7, R7')
        a.go('MOVW tmp+20(FP), R4')
        for i in range(6):
            for j in range(6):
                a.n('vld1.32 %s, [r7], r12' % qd(m[j]))
            at(a, m, outs, tmps)
            a.n('vst1.32 {d14, d15, d16, d17}, [r4]!')
            a.n('vst1.32 {d18, d19, d20, d21}, [r4]!')
        # Along i: Y[a][b] = (At R[.][b])[a] into tmp + (24 + 4a + b)*16.
        a.go('MOVW $64, R8')
        for b in range(4):
            a.go('MOVW tmp+20(FP), R4')
            a.go('ADD $%d, R4, R4' % (16 * b))
            for i in range(6):
                a.n('vld1.32 %s, [r4], r8' % qd(m[i]))
            at(a, m, outs, tmps)
            a.go('MOVW tmp+20(FP), R4')
            a.go('ADD $%d, R4, R4' % (16 * (24 + b)))
            for r in range(4):
                a.n('vst1.32 %s, [r4], r8' % qd(outs[r]))
        # Each output row: plus the bias, ReLU, the 4 phases interleaved (tiles 4g..4g+3 are
        # columns 16g..16g+15).
        a.go('MOVW bias+8(FP), R5')
        a.n('vld1.32 {d10[], d11[]}, [r5]')
        a.n('vmov.i32 q6, #0')
        a.go('MOVW dst+12(FP), R7')
        if g:
            a.go('ADD $64, R7, R7')
        a.go('MOVW tmp+20(FP), R4')
        a.go('ADD $384, R4, R4')
        a.go('MOVW rs+16(FP), R1')
        for r in range(4):
            a.n('vld1.32 {d2, d3, d4, d5}, [r4]!')
            a.n('vld1.32 {d6, d7, d8, d9}, [r4]!')
            for k in range(1, 5):
                a.n('vadd.f32 q%d, q%d, q5' % (k, k))
                a.n('vmax.f32 q%d, q%d, q6' % (k, k))
            a.go('MOVW R7, R8')
            a.n('vst4.32 {d2, d4, d6, d8}, [r8]!')
            a.n('vst4.32 {d3, d5, d7, d9}, [r8]')
            if r < 3:
                a.go('ADD R1, R7, R7')
    a.go('RET')


HEAD = '''#include "textflag.h"

// Generated by devtools/gen_neon32.py: the voice network's inner loops with ARMv7 NEON (see
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
