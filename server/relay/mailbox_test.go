package main

import (
	"testing"
	"time"
)

func TestMailboxPutTake(t *testing.T) {
	m := NewMailbox(time.Hour, 1<<20)
	m.Put("a", []byte("one"))
	m.Put("a", []byte("two"))
	m.Put("b", []byte("three"))

	got := m.Take("a")
	if len(got) != 2 || string(got[0]) != "one" || string(got[1]) != "two" {
		t.Fatalf("unexpected entries: %q", got)
	}
	if len(m.Take("a")) != 0 {
		t.Fatal("Take should drain the target")
	}
	if len(m.Take("b")) != 1 {
		t.Fatal("other targets must be untouched")
	}
}

func TestMailboxTTL(t *testing.T) {
	m := NewMailbox(10*time.Millisecond, 1<<20)
	m.Put("a", []byte("x"))
	time.Sleep(20 * time.Millisecond)
	if len(m.Take("a")) != 0 {
		t.Fatal("expired entry should be pruned")
	}
}

func TestMailboxByteCap(t *testing.T) {
	m := NewMailbox(time.Hour, 10)
	m.Put("a", []byte("12345"))
	m.Put("b", []byte("67890"))
	m.Put("c", []byte("abcde"))

	_, bytes := m.Stats()
	if bytes > 10 {
		t.Fatalf("mailbox bytes = %d, cap is 10", bytes)
	}
}
