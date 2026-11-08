package main

import (
	"bytes"
	"context"
	"errors"
	"math"
	"os"
	"path/filepath"
	"testing"
)

// A run stopped after any chunk continues from its checkpoint and gives exactly what one
// run gives.
func TestMdxRemoverResume(t *testing.T) {
	dir := mdxRefDir(t)
	spec := sepSpec
	spec.dimT = 64 // short chunks: a few of them in 14 s
	net, err := loadMdxNet(testSepModel(t), spec, sepThreads())
	if err != nil {
		t.Fatal(err)
	}
	mix, err := os.ReadFile(filepath.Join(dir, "mix.f32"))
	if err != nil {
		t.Fatal(err)
	}
	var whole bytes.Buffer
	var saves []*mdxResume
	r := newMdxRemover(net, spec)
	n, err := r.runWith(context.Background(), bytes.NewReader(mix), &whole, runOpts{saved: func(next *mdxResume) error {
		c := &mdxResume{chunk: next.chunk, emitted: next.emitted}
		for i := range c.tail {
			c.tail[i] = append([]float64(nil), next.tail[i]...)
		}
		saves = append(saves, c)
		return nil
	}})
	if err != nil || int(n) != len(mix)/8 {
		t.Fatalf("%d samples, %v", n, err)
	}
	if len(saves) < 3 {
		t.Fatalf("only %d checkpoints", len(saves))
	}
	stop := errors.New("stopped")
	for _, at := range []int{0, len(saves) / 2, len(saves) - 1} {
		// Stopped right after checkpoint at (what was written after it is dropped).
		var part bytes.Buffer
		r := newMdxRemover(net, spec)
		_, err := r.runWith(context.Background(), bytes.NewReader(mix), &part, runOpts{saved: func(next *mdxResume) error {
			if next.chunk == saves[at].chunk {
				return stop
			}
			return nil
		}})
		if !errors.Is(err, stop) {
			t.Fatalf("not stopped: %v", err)
		}
		s := saves[at]
		got := append([]byte(nil), part.Bytes()[:s.emitted*8]...)
		// Through the checkpoint file and back.
		p := filepath.Join(t.TempDir(), "state")
		if err := writeState(p, &sepState{tag: "x", dimT: spec.dimT, nfft: spec.nfft, chunk: s.chunk, emitted: s.emitted, tail: s.tail}); err != nil {
			t.Fatal(err)
		}
		st, err := readState(p)
		if err != nil || st.chunk != s.chunk || st.emitted != s.emitted || st.tag != "x" || st.dimT != spec.dimT {
			t.Fatalf("state back: %+v %v", st, err)
		}
		var rest bytes.Buffer
		r = newMdxRemover(net, spec)
		n2, err := r.runWith(context.Background(), bytes.NewReader(mix), &rest,
			runOpts{resume: &mdxResume{chunk: st.chunk, emitted: st.emitted, tail: st.tail}})
		if err != nil || n2 != n {
			t.Fatalf("resumed at chunk %d: %d samples, %v", s.chunk, n2, err)
		}
		got = append(got, rest.Bytes()...)
		if !bytes.Equal(got, whole.Bytes()) {
			t.Fatalf("resumed at chunk %d: not the same (%d vs %d bytes)", s.chunk, len(got), whole.Len())
		}
	}
	// A shorter sound does not fit the checkpoint.
	last := saves[len(saves)-1]
	r = newMdxRemover(net, spec)
	_, err = r.runWith(context.Background(), bytes.NewReader(mix[:8*(last.emitted-int64(r.step))]), &bytes.Buffer{},
		runOpts{resume: last})
	if !errors.Is(err, errResumeTooShort) {
		t.Errorf("shorter sound: %v", err)
	}
}

func TestStateDamaged(t *testing.T) {
	p := filepath.Join(t.TempDir(), "state")
	tail := [2][]float64{{1, 2, math.Pi}, {4, 5, 6}}
	if err := writeState(p, &sepState{tag: "abc", dimT: 128, nfft: 7680, chunk: 3, emitted: 99, tail: tail}); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(p)
	for _, cut := range []int{0, 10, len(b) - 1} {
		os.WriteFile(p, b[:cut], 0o644)
		if _, err := readState(p); err == nil {
			t.Errorf("cut at %d: read", cut)
		}
	}
	b[40] ^= 1
	os.WriteFile(p, b, 0o644)
	if _, err := readState(p); err == nil {
		t.Error("changed: read")
	}
}
