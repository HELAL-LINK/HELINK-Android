package main

import "unsafe"

func cpuid(leaf, sub uint32) (eax, ebx, ecx, edx uint32)
func xgetbv0() (eax, edx uint32)

// hasAVX2FMA: the processor has AVX2 and FMA, and the system saves their registers.
func hasAVX2FMA() bool {
	max, _, _, _ := cpuid(0, 0)
	if max < 7 {
		return false
	}
	_, _, c1, _ := cpuid(1, 0)
	const fma, osxsave, avx = 1 << 12, 1 << 27, 1 << 28
	if c1&(fma|osxsave|avx) != fma|osxsave|avx {
		return false
	}
	if xcr0, _ := xgetbv0(); xcr0&6 != 6 {
		return false
	}
	_, b7, _, _ := cpuid(7, 0)
	return b7&(1<<5) != 0
}

//go:noescape
func convTileAVX2(k int, panel, base *float32, offs *int, ep *tileEp)

//go:noescape
func rowsTileAVX2(k int, rows *[6]unsafe.Pointer, panel *float32, ep *tileEp)

//go:noescape
func winoInAVX2(src *float32, rs int, dst *float32, xs int, tmp, pf *float32)

//go:noescape
func winoOutAVX2(src *float32, xs int, bias *float32, dst *float32, rs int, tmp *float32)

func winoFence()

//go:noescape
func winoGemmAVX2(cin int, u *float32, uxs int, v *float32, vrs int, m *float32, mrs int, nb int)

func init() {
	if hasAVX2FMA() {
		convTile, rowsTile, mdxKernel = convTileAVX2, rowsTileAVX2, "avx2"
		winoIn, winoOut, winoGemm = winoInAVX2, winoOutAVX2, winoGemmAVX2
	}
}
