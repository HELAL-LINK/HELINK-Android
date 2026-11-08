package main

import (
	"fmt"
	"math/rand"
	"testing"
	"unsafe"
)

func addr(s []float32, i int) unsafe.Pointer { return unsafe.Pointer(&s[i]) }

// The vector loops give what the plain ones give, for every way of finishing a tile (the
// same check as "helinksep selftest").
func TestMdxKernels(t *testing.T) {
	if mdxKernel == "go" {
		t.Skip("no vector loops here")
	}
	if err := checkKernels(); err != nil {
		t.Fatal(err)
	}
}

func BenchmarkMdxConvTile(b *testing.B) {
	k := 432
	panel := make([]float32, 6*k)
	src := make([]float32, 64*k)
	offs := make([]int, k)
	for p := range offs {
		offs[p] = (p % 48) * 64 * 4
	}
	out := make([]float32, 96)
	ep := new(tileEp)
	for i := range ep.orow {
		ep.orow[i] = addr(out, 16*i)
	}
	for i := 0; i < b.N; i++ {
		convTile(k, &panel[0], &src[0], &offs[0], ep)
	}
	b.ReportMetric(float64(96*k)*float64(b.N)/b.Elapsed().Seconds()/1e9, "GMAC/s")
}

func BenchmarkMdxRowsTile(b *testing.B) {
	k := 3072
	rows := make([]float32, 6*k)
	panel := make([]float32, 16*k)
	var rp [6]unsafe.Pointer
	for i := range rp {
		rp[i] = addr(rows, i*k)
	}
	out := make([]float32, 96)
	ep := new(tileEp)
	for i := range ep.orow {
		ep.orow[i] = addr(out, 16*i)
	}
	for i := 0; i < b.N; i++ {
		rowsTile(k, &rp, &panel[0], ep)
	}
	b.ReportMetric(float64(96*k)*float64(b.N)/b.Elapsed().Seconds()/1e9, "GMAC/s")
}

func BenchmarkWinoIn(b *testing.B) {
	if winoIn == nil {
		b.Skip()
	}
	src := make([]float32, 6*3088)
	dst := make([]float32, 36*32)
	tmp := make([]float32, 320)
	for i := 0; i < b.N; i++ {
		winoIn(&src[0], 4*3088, &dst[0], 4*32, &tmp[0], &src[0])
	}
}

func BenchmarkWinoOut(b *testing.B) {
	if winoOut == nil {
		b.Skip()
	}
	src := make([]float32, 36*32)
	dst := mdxAlloc(4 * 3088)
	tmp := make([]float32, 320)
	bias := float32(0)
	for i := 0; i < b.N; i++ {
		winoOut(&src[0], 4*32, &bias, &dst[0], 4*3088, &tmp[0])
	}
}

// One Winograd convolution of level 0's size, on one thread, the three steps timed apart.
func BenchmarkWinoLayer(b *testing.B) {
	if winoIn == nil {
		b.Skip()
	}
	c, T, F := 48, 256, 3072
	rnd := rand.New(rand.NewSource(1))
	w := make([]float32, c*c*9)
	for i := range w {
		w[i] = float32(rnd.NormFloat64() * 0.05)
	}
	l := newMdxWino(c, c, w, make([]float32, c), 32)
	n := &mdxNet{offCache: map[string][]int{}, par: &parRunner{workers: 1}}
	n.work = []*mdxWorker{{ep: new(tileEp), junk: make([]float32, 16), wv: make([]float32, 36*c*32), wm: make([]float32, 36*c*32), wtmp: make([]float32, 320)}}
	inb, outb := mdxAlloc(mdxActSize(c, T, F)), mdxAlloc(mdxActSize(c, T, F))
	for i := range inb {
		inb[i] = float32(rnd.NormFloat64())
	}
	in, out := mdxActOn(inb, c, T, F), mdxActOn(outb, c, T, F)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		n.wconv(l, &in, &out)
	}
	b.ReportMetric(float64(c*c*9*T*F)*float64(b.N)/b.Elapsed().Seconds()/1e9, "direct-GMAC/s")
}

func BenchmarkMdxConvTileK48(b *testing.B) {
	k := 48
	panel := make([]float32, 6*k)
	src := make([]float32, 36*32*k)
	offs := make([]int, k)
	for p := range offs {
		offs[p] = p * 36 * 32 * 4
	}
	out := make([]float32, 6*36*32)
	ep := new(tileEp)
	for i := range ep.orow {
		ep.orow[i] = addr(out, i*36*32)
	}
	for i := 0; i < b.N; i++ {
		convTile(k, &panel[0], &src[0], &offs[0], ep)
	}
	b.ReportMetric(float64(96*k)*float64(b.N)/b.Elapsed().Seconds()/1e9, "GMAC/s")
}

func BenchmarkWinoGemm(b *testing.B) {
	if winoGemm == nil {
		b.Skip()
	}
	cin, nb := 48, 32
	cs := 36 * nb
	u := make([]float32, 36*8*cin*6)
	v := make([]float32, cin*cs)
	m := make([]float32, 6*cs)
	for i := 0; i < b.N; i++ {
		winoGemm(cin, &u[0], 4*8*cin*6, &v[0], 4*cs, &m[0], 4*cs, nb)
	}
	b.ReportMetric(float64(36*6*cin*nb)*float64(b.N)/b.Elapsed().Seconds()/1e9, "GMAC/s")
}

func BenchmarkWinoGemmStride(b *testing.B) {
	if winoGemm == nil {
		b.Skip()
	}
	for _, extra := range []int{0, 16, 48, 112} {
		b.Run(fmt.Sprint(extra), func(b *testing.B) {
			cin, nb := 48, 32
			cs := 36*nb + extra
			u := make([]float32, 36*8*cin*6)
			v := make([]float32, cin*cs)
			m := make([]float32, 6*cs)
			for i := 0; i < b.N; i++ {
				winoGemm(cin, &u[0], 4*8*cin*6, &v[0], 4*cs, &m[0], 4*cs, nb)
			}
			b.ReportMetric(float64(36*6*cin*nb)*float64(b.N)/b.Elapsed().Seconds()/1e9, "GMAC/s")
		})
	}
}
