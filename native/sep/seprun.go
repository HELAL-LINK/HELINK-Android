package main

// What the music remover's parts share: the threads it runs on, and the stream of samples
// it reads (ffmpeg's 44.1 kHz stereo float samples).

import (
	"bufio"
	"encoding/binary"
	"errors"
	"io"
	"math"
	"runtime"
	"sync"
	"sync/atomic"
)

// mrRate is the sample rate the remover works at.
const mrRate = 44100

var errCutSample = errors.New("audio ends in the middle of a sample")

// parRunner runs body(index, worker) for index 0..n-1 on a few goroutines; worker (0..n-1)
// picks the per-goroutine scratch memory.
type parRunner struct{ workers int }

// sepThreads is how many threads the remover uses by default: one per core (the work is
// handed out piece by piece, so the fast cores of a phone simply take more pieces).
func sepThreads() int { return max(1, min(16, runtime.NumCPU())) }

func (p *parRunner) run(n int, body func(index, worker int)) {
	workers := min(p.workers, n)
	if workers <= 1 {
		for i := 0; i < n; i++ {
			body(i, 0)
		}
		return
	}
	var next atomic.Int64
	var wg sync.WaitGroup
	wg.Add(workers)
	for w := 0; w < workers; w++ {
		go func(w int) {
			defer wg.Done()
			for {
				i := int(next.Add(1) - 1)
				if i >= n {
					return
				}
				body(i, w)
			}
		}(w)
	}
	wg.Wait()
}

// mrReader reads interleaved float stereo, counting the samples, and zero-fills after the end.
type mrReader struct {
	src   *bufio.Reader
	ended bool
	total int64
}

func (rd *mrReader) fill(dst [2][]float32, off, count int) error {
	var b [8]byte
	for i := 0; i < count; i++ {
		if rd.ended {
			clear(dst[0][off+i : off+count])
			clear(dst[1][off+i : off+count])
			return nil
		}
		n, err := io.ReadFull(rd.src, b[:])
		if err == io.EOF {
			rd.ended = true
			i--
			continue
		}
		if err == io.ErrUnexpectedEOF {
			_ = n
			return errCutSample
		}
		if err != nil {
			return err
		}
		dst[0][off+i] = math.Float32frombits(binary.LittleEndian.Uint32(b[0:]))
		dst[1][off+i] = math.Float32frombits(binary.LittleEndian.Uint32(b[4:]))
		rd.total++
	}
	return nil
}

// skip reads n samples and drops them (counted in total), stopping early at the end.
func (rd *mrReader) skip(n int64) error {
	if n <= 0 {
		return nil
	}
	got, err := io.CopyN(io.Discard, rd.src, 8*n)
	rd.total += got / 8
	if err == io.EOF {
		if got%8 != 0 {
			return errCutSample
		}
		rd.ended = true
		return nil
	}
	return err
}
