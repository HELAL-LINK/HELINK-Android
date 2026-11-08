//go:build !amd64

package main

// winoFence: only the x86 transforms write past the caches (and need a fence after).
func winoFence() {}
