package main

import (
	"bytes"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// countingListener counts accepted connections and optionally delays each
// NEW connection, simulating the cost of establishing a TCP connection
// through the Shadowsocks tunnel to the relay (real-world RTT).
type countingListener struct {
	net.Listener
	conns     *atomic.Int64
	connDelay time.Duration
}

func (l *countingListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	l.conns.Add(1)
	if l.connDelay > 0 {
		time.Sleep(l.connDelay)
	}
	return c, nil
}

func runPoolTest(t *testing.T, connDelay time.Duration, maxIdlePerHost int, workers, requestsPerWorker int) (elapsed time.Duration, conns int64) {
	conns = 0
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	var connCounter atomic.Int64
	mux := http.NewServeMux()
	// Model the real relay: read the full chunk body so the connection
	// stays keep-alive-eligible (this is what /chunk does on the VPS).
	mux.HandleFunc("/chunk", func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		w.WriteHeader(http.StatusOK)
	})
	srv := &http.Server{Handler: mux}
	go srv.Serve(&countingListener{Listener: ln, conns: &connCounter, connDelay: connDelay})
	defer srv.Close()

	tr := &http.Transport{MaxIdleConnsPerHost: maxIdlePerHost}
	client := &http.Client{Transport: tr, Timeout: 30 * time.Second}
	url := fmt.Sprintf("http://%s/chunk", ln.Addr())

	payload := bytes.Repeat([]byte("x"), 1<<20) // 1 MiB, like a real chunk

	start := time.Now()
	var wg sync.WaitGroup
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < requestsPerWorker; i++ {
				req, err := http.NewRequest(http.MethodPut, url, bytes.NewReader(payload))
				if err != nil {
					t.Error(err)
					return
				}
				resp, err := client.Do(req)
				if err != nil {
					t.Error(err)
					return
				}
				resp.Body.Close()
			}
		}()
	}
	wg.Wait()
	elapsed = time.Since(start)
	// Let the pool drain so we count all connections actually opened.
	time.Sleep(50 * time.Millisecond)
	tr.CloseIdleConnections()
	return elapsed, connCounter.Load()
}

func TestConnectionPoolClaim(t *testing.T) {
	const workers = 4        // ChunkConcurrency
	const reqsPerWorker = 25 // 100 chunks total, like a 100 MiB file

	for _, delay := range []time.Duration{0, 20 * time.Millisecond, 80 * time.Millisecond} {
		tDef, cDef := runPoolTest(t, delay, 2, workers, reqsPerWorker)  // effective default
		tFix, cFix := runPoolTest(t, delay, 16, workers, reqsPerWorker) // proposed fix
		t.Logf("conn-delay=%-14v default(MaxIdlePerHost=2): %8v, %3d conns | fixed(=16): %8v, %2d conns",
			delay, tDef.Round(time.Millisecond), cDef, tFix.Round(time.Millisecond), cFix)
	}
}
