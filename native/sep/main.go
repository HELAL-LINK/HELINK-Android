package main

// helinksep: HELINK's music remover for Android, a program of its own (shipped in the app
// as libhelinksep.so, so that Android lets the app run it). The app runs ffmpeg and this
// program and connects them with pipes:
//
//	helinksep convert MODEL.onnx WEIGHTS.hlw
//	    turns UVR's Kim_Vocal_2 into HELINK's weight file; prints "ok SHA256".
//	helinksep separate --model WEIGHTS.hlw [--threads N] [--dimt 256] [--out FILE|-]
//	                   [--state FILE --tag TAG] [--kernel go]
//	    reads 44.1 kHz stereo float samples (little-endian, interleaved) on its input and
//	    writes the voices in the same format, exactly as many samples: on its output
//	    (--out -), or into FILE. With --state, a checkpoint is saved after every chunk (about
//	    6 seconds of sound), and a run that was stopped continues from the last one: its
//	    input is given again from the start, and what was already done is skipped.
//	    Reports on stderr, one per line: "kernel K threads N dimt D", "start N" (samples
//	    already done), "at N" (a few times a second), "saved N", "done N SECONDS".
//	helinksep selftest
//	    checks the fast loops against the plain ones on this processor; prints "ok K".
//	helinksep version
//
// Exit codes: 0 done, 1 failed, 3 the input ends in the middle of a sample, 4 the
// checkpoint does not fit the input (start again without it), 5 no space left, 6 the weight
// file is damaged (set the remover up again), 7 a fast loop is wrong (use --kernel go).

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"hash/crc32"
	"io"
	"math"
	"math/rand"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

const version = "1.0.0"

var (
	errDamaged  = errors.New("damaged weight file")
	errSelftest = errors.New("the fast loops give wrong results on this processor")
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	var err error
	switch os.Args[1] {
	case "convert":
		err = cmdConvert(os.Args[2:])
	case "separate":
		err = cmdSeparate(os.Args[2:])
	case "selftest":
		err = cmdSelftest()
	case "version":
		fmt.Println("helinksep", version, runtime.GOARCH, mdxKernel, runtime.NumCPU())
	default:
		usage()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(exitCode(err))
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: helinksep convert|separate|selftest|version (see main.go)")
	os.Exit(1)
}

func exitCode(err error) int {
	var errno syscall.Errno
	switch {
	case errors.Is(err, errCutSample):
		return 3
	case errors.Is(err, errResumeTooShort), errors.Is(err, errBadState):
		return 4
	case errors.As(err, &errno) && errno == syscall.ENOSPC, strings.Contains(strings.ToLower(err.Error()), "no space left"):
		return 5
	case errors.Is(err, errDamaged), errors.Is(err, errSepDamaged):
		return 6
	case errors.Is(err, errSelftest):
		return 7
	}
	return 1
}

func cmdConvert(args []string) error {
	if len(args) != 2 {
		return errors.New("convert MODEL.onnx WEIGHTS.hlw")
	}
	sum, err := convertMdxOnnx(args[0], args[1])
	if err != nil {
		return err
	}
	fmt.Println("ok", sum)
	return nil
}

// useGoKernels makes the network use the plain Go loops (no assembly).
func useGoKernels() {
	convTile, rowsTile, mdxKernel = convTileGo, rowsTileGo, "go"
	winoIn, winoOut, winoGemm = nil, nil, nil
}

func cmdSeparate(args []string) error {
	fs := flag.NewFlagSet("separate", flag.ContinueOnError)
	modelPath := fs.String("model", "", "the weight file")
	threads := fs.Int("threads", 0, "threads (0: one per core)")
	dimT := fs.Int("dimt", sepSpec.dimT, "frames per chunk: 256 (as UVR), 128 or 64 with less memory")
	outPath := fs.String("out", "-", "where the voices go (- for the output)")
	statePath := fs.String("state", "", "checkpoint file (with --out FILE)")
	tag := fs.String("tag", "", "what the checkpoint belongs to (the input's name, size…)")
	kernel := fs.String("kernel", "", "go: the plain loops")
	direct := fs.Bool("direct", false, "no Winograd convolutions")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *modelPath == "" {
		return errors.New("--model is needed")
	}
	if *kernel == "go" {
		useGoKernels()
	}
	if *threads <= 0 {
		*threads = sepThreads()
	}
	spec := sepSpec
	spec.direct = *direct
	toFile := *outPath != "-"
	if !toFile {
		*statePath = ""
	}

	// Continue from the checkpoint when it is there and fits.
	var res *mdxResume
	var st *sepState
	if *statePath != "" {
		if s, err := readState(*statePath); err == nil && s.tag == *tag && s.nfft == spec.nfft {
			if fi, err := os.Stat(*outPath); err == nil && fi.Size() >= s.emitted*8 {
				st = s
			}
		}
	}
	if st != nil {
		*dimT = st.dimT
		if st.complete {
			if err := os.Truncate(*outPath, st.emitted*8); err != nil {
				return err
			}
			fmt.Fprintf(os.Stderr, "start %d\ndone %d 0\n", st.emitted, st.emitted)
			return nil
		}
		res = &mdxResume{chunk: st.chunk, emitted: st.emitted, tail: st.tail}
	}
	if *dimT < 64 || *dimT > 512 || *dimT&(*dimT-1) != 0 {
		return fmt.Errorf("--dimt %d: 64, 128, 256 or 512", *dimT)
	}
	spec.dimT = *dimT

	var out *os.File
	if toFile {
		var err error
		if res != nil {
			out, err = os.OpenFile(*outPath, os.O_RDWR, 0o644)
			if err == nil {
				err = out.Truncate(res.emitted * 8)
			}
			if err == nil {
				_, err = out.Seek(res.emitted*8, io.SeekStart)
			}
		} else {
			if *statePath != "" {
				_ = os.Remove(*statePath)
			}
			out, err = os.Create(*outPath)
		}
		if err != nil {
			return err
		}
		defer out.Close()
	}

	// The network, then the model's memory back (33 MB, and the copies made while building).
	model, err := openSepFile(*modelPath)
	if err != nil {
		if os.IsNotExist(err) {
			return err
		}
		return fmt.Errorf("%w: %v", errDamaged, err)
	}
	net, err := loadMdxWeights(model, spec)
	model = nil
	if err != nil {
		return fmt.Errorf("%w: %v", errDamaged, err)
	}
	runtime.GC()
	debug.FreeOSMemory()
	net.plan(*threads)
	r := newMdxRemover(net, spec)
	// What a run allocates from now on is small and short-lived (read and write buffers): it
	// is collected early, instead of letting the heap grow to twice the network's memory.
	debug.SetGCPercent(10)
	start := int64(0)
	if res != nil {
		start = res.emitted
	}
	fmt.Fprintf(os.Stderr, "kernel %s threads %d dimt %d\nstart %d\n", mdxKernel, *threads, spec.dimT, start)

	began := time.Now()
	var last time.Time
	opts := runOpts{resume: res, progress: func(done int64) {
		if now := time.Now(); now.Sub(last) >= 250*time.Millisecond {
			last = now
			fmt.Fprintf(os.Stderr, "at %d\n", done)
		}
	}}
	in := newReadAhead(os.Stdin, 32, 1<<16)
	defer in.stop()
	var dst io.Writer
	var wb *writeBehind
	if toFile {
		dst = out
		if *statePath != "" {
			opts.saved = func(next *mdxResume) error {
				if err := out.Sync(); err != nil {
					return err
				}
				s := &sepState{tag: *tag, dimT: spec.dimT, nfft: spec.nfft, chunk: next.chunk, emitted: next.emitted, tail: next.tail}
				if err := writeState(*statePath, s); err != nil {
					return err
				}
				fmt.Fprintf(os.Stderr, "saved %d\n", next.emitted)
				return nil
			}
		}
	} else {
		wb = newWriteBehind(os.Stdout, 32)
		dst = wb
	}
	n, err := r.runWith(context.Background(), in, dst, opts)
	if wb != nil {
		if cerr := wb.Close(); err == nil {
			err = cerr
		}
	}
	if err != nil {
		return err
	}
	if toFile {
		if err := out.Sync(); err != nil {
			return err
		}
		if *statePath != "" {
			s := &sepState{tag: *tag, dimT: spec.dimT, nfft: spec.nfft, emitted: n, complete: true}
			if err := writeState(*statePath, s); err != nil {
				return err
			}
		}
	}
	fmt.Fprintf(os.Stderr, "done %d %.1f\n", n, time.Since(began).Seconds())
	return nil
}

// ---------------------------------------------------------------------------
// The checkpoint
// ---------------------------------------------------------------------------

// sepState is a checkpoint of a run into a file: the next chunk, the samples written
// before it, the end of the chunk before (to fade from), or that the run is complete.
type sepState struct {
	tag            string
	dimT, nfft     int
	chunk, emitted int64
	complete       bool
	tail           [2][]float64
}

var (
	stateMagic  = []byte("HLKSEPRS")
	errBadState = errors.New("damaged checkpoint")
)

// writeState writes the checkpoint next to its place and moves it there (all or nothing).
func writeState(path string, s *sepState) error {
	var b []byte
	le := binary.LittleEndian
	b = append(b, stateMagic...)
	b = le.AppendUint32(b, 1)
	b = le.AppendUint32(b, uint32(s.dimT))
	b = le.AppendUint32(b, uint32(s.nfft))
	b = le.AppendUint64(b, uint64(s.chunk))
	b = le.AppendUint64(b, uint64(s.emitted))
	if s.complete {
		b = append(b, 1)
	} else {
		b = append(b, 0)
	}
	b = le.AppendUint16(b, uint16(len(s.tag)))
	b = append(b, s.tag...)
	b = le.AppendUint32(b, uint32(len(s.tail[0])))
	for c := 0; c < 2; c++ {
		for _, v := range s.tail[c] {
			b = le.AppendUint64(b, math.Float64bits(v))
		}
	}
	b = le.AppendUint32(b, crc32.ChecksumIEEE(b))
	tmp := path + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	_, err = f.Write(b)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(tmp, path)
	}
	if err != nil {
		_ = os.Remove(tmp)
	}
	return err
}

func readState(path string) (s *sepState, err error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	defer func() {
		if recover() != nil {
			s, err = nil, errBadState
		}
	}()
	le := binary.LittleEndian
	if len(b) < 8+4+4+4+8+8+1+2+4+4 || string(b[:8]) != string(stateMagic) ||
		crc32.ChecksumIEEE(b[:len(b)-4]) != le.Uint32(b[len(b)-4:]) || le.Uint32(b[8:]) != 1 {
		return nil, errBadState
	}
	s = &sepState{dimT: int(le.Uint32(b[12:])), nfft: int(le.Uint32(b[16:])),
		chunk: int64(le.Uint64(b[20:])), emitted: int64(le.Uint64(b[28:])), complete: b[36] == 1}
	p := 37
	n := int(le.Uint16(b[p:]))
	p += 2
	s.tag = string(b[p : p+n])
	p += n
	ov := int(le.Uint32(b[p:]))
	p += 4
	if p+16*ov+4 != len(b) {
		return nil, errBadState
	}
	for c := 0; c < 2; c++ {
		s.tail[c] = make([]float64, ov)
		for i := range s.tail[c] {
			s.tail[c][i] = math.Float64frombits(le.Uint64(b[p:]))
			p += 8
		}
	}
	return s, nil
}

// ---------------------------------------------------------------------------
// Reading ahead, writing behind
// ---------------------------------------------------------------------------

// readAhead reads a few MB ahead of the music remover, so ffmpeg decodes the next part
// while the remover works on this one.
type readAhead struct {
	ch   chan []byte
	cur  []byte
	err  error
	quit chan struct{}
	done chan struct{}
	once sync.Once
}

func newReadAhead(r io.Reader, chunks, size int) *readAhead {
	ra := &readAhead{ch: make(chan []byte, chunks), quit: make(chan struct{}), done: make(chan struct{})}
	go func() {
		defer close(ra.done)
		defer close(ra.ch)
		for {
			// Whole chunks (a pipe gives a few KB at a time).
			b := make([]byte, size)
			n, err := io.ReadFull(r, b)
			if n > 0 {
				select {
				case ra.ch <- b[:n]:
				case <-ra.quit:
					return
				}
			}
			if err != nil {
				if err != io.EOF && err != io.ErrUnexpectedEOF {
					ra.err = err
				}
				return
			}
		}
	}()
	return ra
}

func (ra *readAhead) Read(p []byte) (int, error) {
	for len(ra.cur) == 0 {
		b, ok := <-ra.ch
		if !ok {
			<-ra.done
			if ra.err != nil {
				return 0, ra.err
			}
			return 0, io.EOF
		}
		ra.cur = b
	}
	n := copy(p, ra.cur)
	ra.cur = ra.cur[n:]
	return n, nil
}

// stop ends reading ahead (the rest of the input is not wanted). It does not wait: the
// reader may be blocked on the input, which ends with the program.
func (ra *readAhead) stop() {
	ra.once.Do(func() { close(ra.quit) })
}

// writeBehind lets ffmpeg encode the voices while the remover works on the next part.
type writeBehind struct {
	ch   chan []byte
	done chan struct{}
	w    io.Writer
	mu   sync.Mutex
	err  error
}

func newWriteBehind(w io.Writer, chunks int) *writeBehind {
	wb := &writeBehind{ch: make(chan []byte, chunks), done: make(chan struct{}), w: w}
	go func() {
		defer close(wb.done)
		bw := bufio.NewWriterSize(w, 1<<16)
		for b := range wb.ch {
			if wb.failed() != nil {
				continue // the reader stopped: let the remover find out, and drop the rest
			}
			if _, err := bw.Write(b); err == nil && len(wb.ch) == 0 {
				err = bw.Flush()
				wb.setErr(err)
			} else {
				wb.setErr(err)
			}
		}
		if wb.failed() == nil {
			wb.setErr(bw.Flush())
		}
	}()
	return wb
}

func (wb *writeBehind) setErr(err error) {
	if err == nil {
		return
	}
	wb.mu.Lock()
	if wb.err == nil {
		wb.err = err
	}
	wb.mu.Unlock()
}

func (wb *writeBehind) failed() error {
	wb.mu.Lock()
	defer wb.mu.Unlock()
	return wb.err
}

func (wb *writeBehind) Write(p []byte) (int, error) {
	if err := wb.failed(); err != nil {
		return 0, err
	}
	wb.ch <- append([]byte(nil), p...)
	return len(p), nil
}

// Close waits for what is left to be written.
func (wb *writeBehind) Close() error {
	close(wb.ch)
	<-wb.done
	return wb.failed()
}

// ---------------------------------------------------------------------------
// Self-test
// ---------------------------------------------------------------------------

// cmdSelftest compares the fast loops in use with the plain ones on random numbers, for
// every way of finishing a tile, and the Winograd steps with their plain versions.
func cmdSelftest() error {
	if err := checkKernels(); err != nil {
		return fmt.Errorf("%w: %v", errSelftest, err)
	}
	fmt.Println("ok", mdxKernel)
	return nil
}

func checkKernels() error {
	rnd := rand.New(rand.NewSource(7))
	fill := func(n int) []float32 {
		s := make([]float32, n)
		for i := range s {
			s[i] = float32(rnd.NormFloat64())
		}
		return s
	}
	near := func(a, b, tol float32) bool {
		return math.Abs(float64(a-b)) <= float64(tol)*(1+math.Abs(float64(a)))
	}
	addr := func(s []float32, i int) unsafe.Pointer { return unsafe.Pointer(&s[i]) }
	for _, k := range []int{1, 2, 3, 5, 17, 432} {
		panel := fill(6 * k)
		src := fill(64 * k)
		offs := make([]int, k)
		for p := range offs {
			offs[p] = rnd.Intn(48*k) * 4
		}
		bpanel := fill(16 * k)
		rows := fill(6*k + 40)
		var rp [6]unsafe.Pointer
		for i := range rp {
			rp[i] = addr(rows, rnd.Intn(5*k+30))
		}
		for flags := uintptr(0); flags < 8; flags++ {
			if flags&epMul != 0 && flags&epAdd != 0 {
				continue
			}
			d := fill(6 * 16)
			ep1, ep2 := new(tileEp), new(tileEp)
			o1, o2 := make([]float32, 96), make([]float32, 96)
			for i := 0; i < 6; i++ {
				s, b := float32(rnd.NormFloat64()), float32(rnd.NormFloat64())
				ep1.scale[i], ep1.bias[i], ep2.scale[i], ep2.bias[i] = s, b, s, b
				ep1.orow[i], ep1.drow[i] = addr(o1, 16*i), addr(d, 16*(5-i))
				ep2.orow[i], ep2.drow[i] = addr(o2, 16*i), addr(d, 16*(5-i))
			}
			ep1.flags, ep2.flags = flags, flags
			convTileGo(k, &panel[0], &src[0], &offs[0], ep1)
			convTile(k, &panel[0], &src[0], &offs[0], ep2)
			for i := range o1 {
				if !near(o1[i], o2[i], 1e-4) {
					return fmt.Errorf("convTile k=%d flags=%d: [%d] %g vs %g", k, flags, i, o1[i], o2[i])
				}
			}
			rowsTileGo(k, &rp, &bpanel[0], ep1)
			rowsTile(k, &rp, &bpanel[0], ep2)
			for i := range o1 {
				if !near(o1[i], o2[i], 1e-4) {
					return fmt.Errorf("rowsTile k=%d flags=%d: [%d] %g vs %g", k, flags, i, o1[i], o2[i])
				}
			}
		}
	}
	if winoIn == nil {
		return nil
	}
	rs := 4 * 64
	src := fill(6 * 64)
	xs := 4 * 24
	a, b := make([]float32, 36*24), make([]float32, 36*24)
	tmp := make([]float32, 40*8)
	winoInGo(&src[0], rs, &a[0], xs, &tmp[0], &src[0])
	winoIn(&src[0], rs, &b[0], xs, &tmp[0], &src[0])
	for x := 0; x < 36; x++ {
		for m := 0; m < 8; m++ {
			if i := x*24 + m; !near(a[i], b[i], 1e-4) {
				return fmt.Errorf("winoIn: V[%d] tile %d: %g vs %g", x, m, a[i], b[i])
			}
		}
	}
	bias := float32(0.3)
	o1, o2 := make([]float32, 4*48), mdxAlloc(4*48)
	winoOutGo(&a[0], xs, &bias, &o1[0], 4*48, &tmp[0])
	winoOut(&a[0], xs, &bias, &o2[0], 4*48, &tmp[0])
	winoFence()
	for r := 0; r < 4; r++ {
		for c := 0; c < 32; c++ {
			if i := r*48 + c; !near(o1[i], o2[i], 1e-3) {
				return fmt.Errorf("winoOut: row %d col %d: %g vs %g", r, c, o1[i], o2[i])
			}
		}
	}
	// The products: 36 × (6 channels × 32 tiles) over 10 input channels.
	cin, nb := 10, 32
	cs := 36 * nb
	u := fill(36 * 2 * cin * 6)
	v := fill(cin * cs)
	m1, m2 := make([]float32, 6*cs), make([]float32, 6*cs)
	winoGemm(cin, &u[0], 4*2*cin*6, &v[0], 4*cs, &m2[0], 4*cs, nb)
	for x := 0; x < 36; x++ {
		for co := 0; co < 6; co++ {
			for j := 0; j < nb; j++ {
				var s float32
				for ci := 0; ci < cin; ci++ {
					s += u[x*2*cin*6+ci*6+co] * v[ci*cs+x*nb+j]
				}
				m1[co*cs+x*nb+j] = s
			}
		}
	}
	for i := range m1 {
		if !near(m1[i], m2[i], 1e-4) {
			return fmt.Errorf("winoGemm: [%d] %g vs %g", i, m1[i], m2[i])
		}
	}
	return nil
}
