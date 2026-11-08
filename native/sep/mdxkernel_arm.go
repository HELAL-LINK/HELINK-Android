package main

import (
	"encoding/binary"
	"os"
	"unsafe"
)

// The NEON loops for 32-bit ARM (mdxkernel_arm.s, written by devtools/gen_neon32.py), when
// the processor has NEON (nearly every armeabi-v7a phone); the plain Go loops otherwise.

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

// hasNEON reads the processor's features the kernel gives the program (AT_HWCAP).
func hasNEON() bool {
	b, err := os.ReadFile("/proc/self/auxv")
	if err != nil {
		return false
	}
	const atHWCAP, hwcapNEON = 16, 1 << 12
	for i := 0; i+8 <= len(b); i += 8 {
		if binary.LittleEndian.Uint32(b[i:]) == atHWCAP {
			return binary.LittleEndian.Uint32(b[i+4:])&hwcapNEON != 0
		}
	}
	return false
}

func init() {
	if hasNEON() {
		convTile, rowsTile, mdxKernel = convTileNEON, rowsTileNEON, "neon32"
		winoIn, winoOut, winoGemm = winoInNEON, winoOutNEON, winoGemmNEON
	}
}
