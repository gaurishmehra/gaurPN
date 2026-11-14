package main

import (
	"context"
	"mime"
	"runtime"
	"testing"
)

func TestOfferTagPayloadOpenBit(t *testing.T) {
	cases := []struct {
		name     string
		open     bool
		expected string
	}{
		{"open requested", true, "offer:v2:tx-1:bob-device:04abcdef:deadhash:3:65536:1"},
		{"open not requested", false, "offer:v2:tx-1:bob-device:04abcdef:deadhash:3:65536:0"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := offerTagPayload("tx-1", "bob-device", "04abcdef", "deadhash", 3, 65536, tc.open)
			if got != tc.expected {
				t.Errorf("offerTagPayload open=%v = %q, want %q", tc.open, got, tc.expected)
			}
		})
	}
}

func TestOfferTagPayloadSenderReceiverAgreement(t *testing.T) {
	_, peerKey := DeriveSubKeys("test-cluster-token-open-bit")

	for _, open := range []bool{true, false} {
		payload := offerTagPayload("tx-open-bit", "bob-device", "04abcdef", "filehash", 2, 262144, open)
		tag := ComputeAuthTag(peerKey, payload)
		if !VerifyAuthTag(peerKey, payload, tag) {
			t.Errorf("open=%v: sender tag failed receiver verification", open)
		}
		// Flipping the bit must invalidate the tag.
		other := offerTagPayload("tx-open-bit", "bob-device", "04abcdef", "filehash", 2, 262144, !open)
		if VerifyAuthTag(peerKey, other, tag) {
			t.Errorf("open=%v: tag verified against flipped open bit", open)
		}
	}
}

func TestOpenCommandPerGOOS(t *testing.T) {
	cases := []struct {
		goos     string
		wantArgs []string
	}{
		{"linux", []string{"xdg-open", "/tmp/report.pdf"}},
		{"darwin", []string{"open", "/tmp/report.pdf"}},
		{"windows", []string{"cmd", "/c", "start", "", "/tmp/report.pdf"}},
	}

	var want []string
	for _, tc := range cases {
		if tc.goos == runtime.GOOS {
			want = tc.wantArgs
		}
	}
	if want == nil {
		t.Skipf("no open command defined for GOOS %s", runtime.GOOS)
	}

	cmd := openCommand(context.Background(), "/tmp/report.pdf")
	if len(cmd.Args) != len(want) {
		t.Fatalf("openCommand args = %q, want %q", cmd.Args, want)
	}
	for i := range want {
		if cmd.Args[i] != want[i] {
			t.Errorf("openCommand args[%d] = %q, want %q", i, cmd.Args[i], want[i])
		}
	}
}

func TestMimeByExtensionFallback(t *testing.T) {
	cases := []struct {
		ext      string
		expected string
	}{
		{".pdf", "application/pdf"},
		{".png", "image/png"},
		{".txt", "text/plain; charset=utf-8"},
		{".definitelynotknown", ""},
		{".", ""},
	}

	for _, tc := range cases {
		got := mime.TypeByExtension(tc.ext)
		if got != tc.expected {
			t.Errorf("mime.TypeByExtension(%q) = %q, want %q", tc.ext, got, tc.expected)
		}
	}
}
