package main

import (
	"fmt"
	"os"
	"testing"
	"unsafe"
)

// How long one chunk takes on phones' processors, from the work the arm64 loops do
// (counted here on this computer: the calls and their sizes), and what each kind of core
// does per cycle. Only an estimate: memory, heat and the other apps change it.
//
//	HELINK_ESTIMATE=1 go test -run TestPhoneEstimate -v
func TestPhoneEstimate(t *testing.T) {
	if os.Getenv("HELINK_ESTIMATE") == "" {
		t.Skip("set HELINK_ESTIMATE")
	}
	for _, dimT := range []int{256, 128} {
		spec := sepSpec
		spec.dimT = dimT
		net, err := loadMdxNet(testSepModel(t), spec, 1)
		if err != nil {
			t.Fatal(err)
		}
		// The arm64 loops' instructions: FMLAs, and the rest (loads, address arithmetic).
		var fmla, other, wino float64
		oc, or, oi, oo, og := convTile, rowsTile, winoIn, winoOut, winoGemm
		convTile = func(k int, panel, base *float32, offs *int, ep *tileEp) {
			fmla += 24 * float64(k)
			other += 11*float64(k) + 120
			oc(k, panel, base, offs, ep)
		}
		rowsTile = func(k int, rows *[6]unsafe.Pointer, panel *float32, ep *tileEp) {
			fmla += 24 * float64(k)
			other += 7*float64(k) + 140
			or(k, rows, panel, ep)
		}
		if winoIn != nil {
			winoIn = func(src *float32, rs int, dst *float32, xs int, tmp, pf *float32) {
				wino += 700
				oi(src, rs, dst, xs, tmp, pf)
			}
			winoOut = func(src *float32, xs int, bias *float32, dst *float32, rs int, tmp *float32) {
				wino += 600
				oo(src, xs, bias, dst, rs, tmp)
			}
			winoGemm = func(cin int, u *float32, uxs int, v *float32, vrs int, m *float32, mrs int, nb int) {
				calls := 36 * float64(nb/16)
				fmla += calls * 24 * float64(cin)
				other += calls * (11*float64(cin) + 60)
				og(cin, u, uxs, v, vrs, m, mrs, nb)
			}
		}
		r := newMdxRemover(net, spec)
		r.spectrum()
		if err := net.forward(t.Context()); err != nil {
			t.Fatal(err)
		}
		convTile, rowsTile, winoIn, winoOut, winoGemm = oc, or, oi, oo, og
		secs := float64(r.step) / mrRate // sound each chunk adds
		t.Logf("dim_t %d: %.1f G FMLA, %.1f G other, %.2f G in the Winograd transforms, per %.2f s of sound",
			dimT, fmla/1e9, other/1e9, wino/1e9, secs)
		// Cycles per core kind: big out-of-order cores issue 2 FMLA a cycle and hide the rest;
		// little in-order ones 1 (A55) or one every 2 cycles (A53), the rest partly paired.
		kinds := []struct {
			name          string
			fmlaPerCycle  float64
			otherPerCycle float64
			transPerCycle float64
		}{{"A76/A78", 2, 4, 3}, {"A55", 1, 2, 1.5}, {"A53", 0.5, 1.5, 1}}
		cycles := map[string]float64{}
		for _, k := range kinds {
			c := fmla/k.fmlaPerCycle + wino/k.transPerCycle
			if o := other / k.otherPerCycle; k.name != "A76/A78" {
				c += o * 0.3 // in-order: some of the rest does not pair
			}
			cycles[k.name] = c
			t.Logf("  one %s core: %.1f G cycles per chunk", k.name, c/1e9)
		}
		// Typical phones: cores × GHz, at 80% of the ideal (memory, heat, scheduling).
		phones := []struct {
			name  string
			cores map[string]float64 // GHz × count
		}{
			{"mid-range 2×A76 2.2 GHz + 6×A55 1.8 GHz (Helio G99, Snapdragon 680-ish)", map[string]float64{"A76/A78": 2 * 2.2, "A55": 6 * 1.8}},
			{"high-end 4×A78 2.4 GHz + 4×A55 2.0 GHz", map[string]float64{"A76/A78": 4 * 2.4, "A55": 4 * 2.0}},
			{"low-end 8×A53 1.6 GHz", map[string]float64{"A53": 8 * 1.6}},
			{"entry 4×A53 1.5 GHz", map[string]float64{"A53": 4 * 1.5}},
		}
		for _, p := range phones {
			rate := 0.0 // chunks per second
			for kind, ghz := range p.cores {
				rate += ghz * 1e9 / cycles[kind]
			}
			rate *= 0.8
			perChunk := 1 / rate
			song := 240 / secs * perChunk
			t.Logf("  %s: %.1f s per chunk; a 4-minute song in %s", p.name, perChunk, fmtMin(song))
		}
	}
}

func fmtMin(s float64) string { return fmt.Sprintf("%.1f min", s/60) }
