package main

import "unsafe"

// The NEON loops (mdxkernel_arm64.s, written by devtools/gen_neon.py). Every 64-bit ARM
// phone has NEON.

//go:noescape
func convTileNEON(k int, panel, base *float32, offs *int, ep *tileEp)

//go:noescape
func rowsTileNEON(k int, rows *[6]unsafe.Pointer, panel *float32, ep *tileEp)

//go:noescape
func winoInNEON(src *float32, rs int, dst *float32, xs int, tmp, pf *float32)

//go:noescape
func winoOutNEON(src *float32, xs int, bias *float32, dst *float32, rs int, tmp *float32)

//go:noescape
func winoGemmNEON(cin int, u *float32, uxs int, v *float32, vrs int, m *float32, mrs int, nb int)

func init() {
	convTile, rowsTile, mdxKernel = convTileNEON, rowsTileNEON, "neon"
	winoIn, winoOut, winoGemm = winoInNEON, winoOutNEON, winoGemmNEON
}
