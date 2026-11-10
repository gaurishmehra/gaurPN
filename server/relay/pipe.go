package main

import (
	"io"
	"sync"
	"sync/atomic"
	"time"
)

// bufferedPipe is a bounded in-memory pipe that decouples the sender and
// receiver sides of a relay transfer. Unlike io.Pipe, a Write returns once the
// chunk is buffered (up to maxSize), so the sender can keep the network socket
// full while the receiver drains at its own pace. The buffer is bounded, so a
// slow receiver applies backpressure instead of growing memory without limit.
type bufferedPipe struct {
	mu      sync.Mutex
	cond    *sync.Cond
	chunks  [][]byte
	size    int
	maxSize int
	closed  bool
	err     error
	last    atomic.Int64
}

func newBufferedPipe(maxSize int) *bufferedPipe {
	if maxSize <= 0 {
		maxSize = 1 << 20
	}
	p := &bufferedPipe{maxSize: maxSize}
	p.cond = sync.NewCond(&p.mu)
	p.touch()
	return p
}

func (p *bufferedPipe) touch() {
	p.last.Store(time.Now().UnixNano())
}

// LastActivity reports when the pipe last moved bytes in either direction.
// The relay uses it to evict genuinely idle sessions instead of killing
// long-running transfers on a fixed creation-time deadline.
func (p *bufferedPipe) LastActivity() time.Time {
	return time.Unix(0, p.last.Load())
}

func (p *bufferedPipe) Write(b []byte) (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for p.size >= p.maxSize && !p.closed {
		p.cond.Wait()
	}
	if p.closed {
		if p.err != nil {
			return 0, p.err
		}
		return 0, io.ErrClosedPipe
	}
	if len(b) == 0 {
		return 0, nil
	}
	// Copy because callers such as io.CopyBuffer reuse their buffer.
	chunk := make([]byte, len(b))
	copy(chunk, b)
	p.chunks = append(p.chunks, chunk)
	p.size += len(chunk)
	p.touch()
	p.cond.Broadcast()
	return len(b), nil
}

func (p *bufferedPipe) Read(b []byte) (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for len(p.chunks) == 0 && !p.closed {
		p.cond.Wait()
	}
	if len(p.chunks) == 0 {
		if p.err != nil {
			return 0, p.err
		}
		return 0, io.EOF
	}
	if len(b) == 0 {
		return 0, nil
	}
	chunk := p.chunks[0]
	n := copy(b, chunk)
	if n < len(chunk) {
		p.chunks[0] = chunk[n:]
	} else {
		p.chunks = p.chunks[1:]
	}
	p.size -= n
	p.touch()
	p.cond.Broadcast()
	return n, nil
}

func (p *bufferedPipe) CloseWithError(err error) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return nil
	}
	p.closed = true
	p.err = err
	p.cond.Broadcast()
	return nil
}

func (p *bufferedPipe) Close() error {
	return p.CloseWithError(nil)
}
