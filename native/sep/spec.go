package main

// The voice network HELINK uses: Kim_Vocal_2 (MDX-Net by KUIELab, trained by
// KimberleyJSN), as Ultimate Vocal Remover publishes it, turned into HELINK's weight file
// by "convert" (mdxconv.go). The same file and settings as HELINK for Windows.

const (
	kimOnnxFile    = "Kim_Vocal_2.onnx"
	kimOnnxSize    = 66759214
	kimOnnxSHA256  = "ce74ef3b6a6024ce44211a07be9cf8bc6d87728cc852a68ab34eb8e58cde9c8b"
	kimWeightsSize = 33354944
	kimWeightsSHA  = "d0b06e512a683a749185fc178c70a55d6c0ceb567761345e2f8042c82fd91dc2"
)

// sepSpec is UVR's way of running Kim_Vocal_2: n_fft 7680, dim_t 2^8 (phones short of
// memory use 2^7: half the memory, about 7% more work, see main.go).
var sepSpec = mdxSpec{nfft: 7680, dimT: 256}
