package main

import (
	"net/url"
	"testing"
)

func TestBuildPairURI(t *testing.T) {
	uri := buildPairURI("198.51.100.1:9876", "secret-token", "10.0.0.182", "Pixel 8")
	u, err := url.Parse(uri)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if u.Scheme != "gaur" || u.Host != "pair" {
		t.Fatalf("unexpected URI shape: %s", uri)
	}
	q := u.Query()
	if q.Get("relay") != "198.51.100.1:9876" {
		t.Fatalf("relay = %q", q.Get("relay"))
	}
	if q.Get("token") != "secret-token" {
		t.Fatalf("token = %q", q.Get("token"))
	}
	if q.Get("dns") != "10.0.0.182" {
		t.Fatalf("dns = %q", q.Get("dns"))
	}
	if q.Get("name") != "Pixel 8" {
		t.Fatalf("name = %q", q.Get("name"))
	}
}

func TestGenerateToken(t *testing.T) {
	token, err := generateToken(32)
	if err != nil {
		t.Fatalf("generateToken: %v", err)
	}
	if len(token) != 64 {
		t.Fatalf("token length = %d, want 64 hex chars", len(token))
	}
	other, err := generateToken(32)
	if err != nil {
		t.Fatalf("generateToken: %v", err)
	}
	if token == other {
		t.Fatal("tokens must be unique")
	}
}
