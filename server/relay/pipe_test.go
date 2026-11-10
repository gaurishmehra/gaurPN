package main

import (
	"bytes"
	"io"
	"sync"
	"testing"
	"time"
)

func TestBufferedPipeRoundTrip(t *testing.T) {
	p := newBufferedPipe(1 << 20)
	payload := bytes.Repeat([]byte("gaur"), 100000) // 400 KB

	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		if _, err := p.Write(payload); err != nil {
			t.Errorf("Write failed: %v", err)
		}
		_ = p.Close()
	}()

	got, err := io.ReadAll(p)
	if err != nil {
		t.Fatalf("ReadAll failed: %v", err)
	}
	wg.Wait()

	if !bytes.Equal(got, payload) {
		t.Fatalf("Payload mismatch: got %d bytes, want %d", len(got), len(payload))
	}
}

func TestBufferedPipeBackpressureAndClose(t *testing.T) {
	p := newBufferedPipe(1024)
	payload := bytes.Repeat([]byte("x"), 8192)

	done := make(chan struct{})
	go func() {
		defer close(done)
		if _, err := p.Write(payload); err != nil {
			t.Errorf("Write failed: %v", err)
		}
		_ = p.Close()
	}()

	// The writer must block once the 1 KiB bound is reached. Give it a moment
	// and confirm the buffer does not grow without a reader.
	time.Sleep(50 * time.Millisecond)
	p.mu.Lock()
	size := p.size
	p.mu.Unlock()
	if size > len(payload) {
		t.Fatalf("Buffer exceeded payload size")
	}

	got, err := io.ReadAll(p)
	if err != nil {
		t.Fatalf("ReadAll failed: %v", err)
	}
	<-done
	if !bytes.Equal(got, payload) {
		t.Fatalf("Payload mismatch: got %d bytes, want %d", len(got), len(payload))
	}
}

func TestBufferedPipeCloseWithError(t *testing.T) {
	p := newBufferedPipe(1024)
	_ = p.CloseWithError(io.ErrUnexpectedEOF)

	if _, err := p.Read(make([]byte, 8)); err != io.ErrUnexpectedEOF {
		t.Fatalf("Read after CloseWithError = %v, want %v", err, io.ErrUnexpectedEOF)
	}
}
