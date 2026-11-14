package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
)

func TestCryptoECDHAndEncryption(t *testing.T) {
	aliceKP, err := GenerateKeyPair()
	if err != nil {
		t.Fatalf("Alice keypair failed: %v", err)
	}
	bobKP, err := GenerateKeyPair()
	if err != nil {
		t.Fatalf("Bob keypair failed: %v", err)
	}

	aliceSecret, err := DeriveSharedSecret(aliceKP.Private, bobKP.Public)
	if err != nil {
		t.Fatalf("Alice shared secret derivation failed: %v", err)
	}
	bobSecret, err := DeriveSharedSecret(bobKP.Private, aliceKP.Public)
	if err != nil {
		t.Fatalf("Bob shared secret derivation failed: %v", err)
	}

	if !bytes.Equal(aliceSecret, bobSecret) {
		t.Fatalf("Shared secrets mismatch between Alice and Bob")
	}

	plaintext := []byte("Testing in-tunnel payload encryption across peers")
	nonce := make([]byte, 12)
	nonce[11] = 1

	ciphertext, err := EncryptChunk(aliceSecret, nonce, plaintext)
	if err != nil {
		t.Fatalf("EncryptChunk failed: %v", err)
	}

	decrypted, err := DecryptChunk(bobSecret, nonce, ciphertext)
	if err != nil {
		t.Fatalf("DecryptChunk failed: %v", err)
	}

	if !bytes.Equal(plaintext, decrypted) {
		t.Fatalf("Decrypted plaintext mismatch: got %q, want %q", decrypted, plaintext)
	}
}

func TestSubKeyDerivationAndAuthTag(t *testing.T) {
	masterToken := "test-cluster-token-xyz-12345"
	relayToken, peerKey := DeriveSubKeys(masterToken)

	expectedRelay := "bbd0b8b332a2ba8131e9c750a97a112da3d200a4bc0887f8457ec4507c438e01"
	if relayToken != expectedRelay {
		t.Errorf("RelayToken = %s, want %s", relayToken, expectedRelay)
	}
	if len(peerKey) != 32 {
		t.Errorf("Expected 32-byte peer key, got %d", len(peerKey))
	}

	msg := "offer:tx-1001:bob-device:04abcdef"
	tag := ComputeAuthTag(peerKey, msg)
	expectedTag := "b043ff3d60b1633570b44528ec8f77ebe2c9f2b96d7e4b4088c5a5ffca4eaf6a"
	if tag != expectedTag {
		t.Errorf("AuthTag = %s, want %s", tag, expectedTag)
	}

	if !VerifyAuthTag(peerKey, msg, tag) {
		t.Errorf("Valid tag failed to verify")
	}

	if VerifyAuthTag(peerKey, msg+"tampered", tag) {
		t.Errorf("Tampered message verified unexpectedly")
	}

	_, otherPeerKey := DeriveSubKeys("different-cluster-token")
	if VerifyAuthTag(otherPeerKey, msg, tag) {
		t.Errorf("Different key verified tag unexpectedly")
	}
}

func TestSafetyNumberDeterminism(t *testing.T) {
	alicePub := bytes.Repeat([]byte{1}, 65)
	bobPub := bytes.Repeat([]byte{2}, 65)
	transferID := "tx-sample-transfer-123"

	sn1 := ComputeSafetyNumber(alicePub, bobPub, transferID)
	sn2 := ComputeSafetyNumber(bobPub, alicePub, transferID)

	expectedSN := "63877 21084 56392 35848 28620 54908"
	if sn1 != expectedSN {
		t.Fatalf("Safety number = %s, want %s", sn1, expectedSN)
	}
	if sn1 != sn2 {
		t.Fatalf("Safety number not commutative: sn1=%s, sn2=%s", sn1, sn2)
	}

	blocks := strings.Split(sn1, " ")
	if len(blocks) != 6 {
		t.Fatalf("Expected 6 blocks in safety number, got %d", len(blocks))
	}
	for _, b := range blocks {
		if len(b) != 5 {
			t.Errorf("Expected 5 digits per block, got %d (%s)", len(b), b)
		}
	}
}

func TestSanitizeFileNameAndPathCheck(t *testing.T) {
	cases := []struct {
		input    string
		expected string
	}{
		{"../../etc/passwd", "passwd"},
		{"/etc/shadow", "shadow"},
		{"..\\..\\windows\\system32", "system32"},
		{"nul\x00lbyte.exe", "nul_lbyte.exe"},
		{"...hidden", "hidden"},
	}

	for _, tc := range cases {
		got := SanitizeFileName(tc.input)
		if got != tc.expected {
			t.Errorf("SanitizeFileName(%q) = %q, want %q", tc.input, got, tc.expected)
		}
	}

	blank := SanitizeFileName("   ")
	if !strings.HasPrefix(blank, "download_") {
		t.Errorf("Expected download_ prefix for blank input, got %q", blank)
	}

	baseDir := filepath.Join(os.TempDir(), "gaurpn_inbox_test")
	safeFile := filepath.Join(baseDir, "file.txt")
	escapeFile := filepath.Join(baseDir, "../../etc/passwd")

	if !IsPathInsideDirectory(safeFile, baseDir) {
		t.Errorf("Expected safeFile to be inside baseDir")
	}
	if IsPathInsideDirectory(escapeFile, baseDir) {
		t.Errorf("Expected escapeFile to NOT be inside baseDir")
	}
}

func TestResolveUniqueFile(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "gaurpn_collision_test_*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	fileA := filepath.Join(tempDir, "document.pdf")
	if err := os.WriteFile(fileA, []byte("first content"), 0o644); err != nil {
		t.Fatalf("Failed to write initial file: %v", err)
	}

	unique1 := ResolveUniqueFile(tempDir, "document.pdf")
	expected1 := filepath.Join(tempDir, "document (1).pdf")
	if unique1 != expected1 {
		t.Errorf("ResolveUniqueFile 1st = %s, want %s", unique1, expected1)
	}

	if err := os.WriteFile(unique1, []byte("second content"), 0o644); err != nil {
		t.Fatalf("Failed to write 2nd file: %v", err)
	}

	unique2 := ResolveUniqueFile(tempDir, "document.pdf")
	expected2 := filepath.Join(tempDir, "document (2).pdf")
	if unique2 != expected2 {
		t.Errorf("ResolveUniqueFile 2nd = %s, want %s", unique2, expected2)
	}
}

// --- MED-8: link-based unique commit ---

// commitMustNotExist asserts a path is gone (no .part or stale name left
// behind by a commit).
func commitMustNotExist(t *testing.T, path string) {
	t.Helper()
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("%s must not exist after the commit, stat err: %v", path, err)
	}
}

// commitMustHaveContent asserts a committed file holds exactly the staged
// bytes — nothing was overwritten or lost.
func commitMustHaveContent(t *testing.T, path, want string) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	if string(data) != want {
		t.Fatalf("%s content = %q, want %q", path, data, want)
	}
}

func TestCommitUniqueFileUsesFreeBaseName(t *testing.T) {
	dir := t.TempDir()
	part := filepath.Join(dir, ".staging.part")
	if err := os.WriteFile(part, []byte("payload"), 0o600); err != nil {
		t.Fatal(err)
	}

	got, err := CommitUniqueFile(part, dir, "report.pdf")
	if err != nil {
		t.Fatalf("CommitUniqueFile: %v", err)
	}
	if want := filepath.Join(dir, "report.pdf"); got != want {
		t.Fatalf("committed to %s, want %s", got, want)
	}
	commitMustHaveContent(t, got, "payload")
	commitMustNotExist(t, part)
}

// TestCommitUniqueFileSkipsTakenNames pins the EEXIST probing: a commit must
// land on the first free numbered suffix and never touch the taken names.
func TestCommitUniqueFileSkipsTakenNames(t *testing.T) {
	dir := t.TempDir()
	taken := []string{"report.pdf", "report (1).pdf", "report (2).pdf"}
	for _, name := range taken {
		if err := os.WriteFile(filepath.Join(dir, name), []byte("old"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	part := filepath.Join(dir, ".staging.part")
	if err := os.WriteFile(part, []byte("new"), 0o600); err != nil {
		t.Fatal(err)
	}

	got, err := CommitUniqueFile(part, dir, "report.pdf")
	if err != nil {
		t.Fatalf("CommitUniqueFile: %v", err)
	}
	if want := filepath.Join(dir, "report (3).pdf"); got != want {
		t.Fatalf("committed to %s, want %s", got, want)
	}
	commitMustHaveContent(t, got, "new")
	for _, name := range taken {
		commitMustHaveContent(t, filepath.Join(dir, name), "old")
	}
	commitMustNotExist(t, part)
}

// TestCommitUniqueFileConcurrentSameName pins the TOCTOU fix: two
// completions of the same file name must each claim a distinct final name,
// neither may be lost or overwritten, and no .part may linger. The old
// stat-then-rename commit let the second rename silently replace the first.
func TestCommitUniqueFileConcurrentSameName(t *testing.T) {
	for iter := 0; iter < 50; iter++ {
		dir := t.TempDir()
		parts := []string{filepath.Join(dir, ".a.part"), filepath.Join(dir, ".b.part")}
		contents := []string{"AAAA", "BBBB"}
		for i, p := range parts {
			if err := os.WriteFile(p, []byte(contents[i]), 0o600); err != nil {
				t.Fatal(err)
			}
		}

		start := make(chan struct{})
		results := make([]string, len(parts))
		errs := make([]error, len(parts))
		var wg sync.WaitGroup
		for i := range parts {
			i := i
			wg.Add(1)
			go func() {
				defer wg.Done()
				<-start
				results[i], errs[i] = CommitUniqueFile(parts[i], dir, "report.pdf")
			}()
		}
		close(start)
		wg.Wait()

		for i := range parts {
			if errs[i] != nil {
				t.Fatalf("iteration %d: completion %d failed: %v", iter, i, errs[i])
			}
		}
		if results[0] == results[1] {
			t.Fatalf("iteration %d: both completions landed on %s", iter, results[0])
		}
		seen := map[string]bool{}
		for _, r := range results {
			data, err := os.ReadFile(r)
			if err != nil {
				t.Fatalf("iteration %d: committed file missing: %v", iter, err)
			}
			seen[string(data)] = true
		}
		if !seen["AAAA"] || !seen["BBBB"] {
			t.Fatalf("iteration %d: a completion was overwritten (results: %v)", iter, results)
		}
		for _, p := range parts {
			commitMustNotExist(t, p)
		}
	}
}

// TestCommitUniqueFileTightensLegacyPartPermissions pins MED-7's final-file
// rule: a .part staged 0644 by an older build must commit as 0600.
func TestCommitUniqueFileTightensLegacyPartPermissions(t *testing.T) {
	dir := t.TempDir()
	part := filepath.Join(dir, ".legacy.part")
	if err := os.WriteFile(part, []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(part, 0o644); err != nil {
		t.Fatal(err)
	}

	got, err := CommitUniqueFile(part, dir, "legacy.bin")
	if err != nil {
		t.Fatalf("CommitUniqueFile: %v", err)
	}
	info, err := os.Stat(got)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("committed file mode = %04o, want 0600", perm)
	}
	commitMustNotExist(t, part)
}

// TestCommitUniqueFileFallsBackWhenLinksUnsupported simulates a filesystem
// that refuses hard links (FAT, some network mounts): the commit must fall
// back to stat-then-rename instead of failing.
func TestCommitUniqueFileFallsBackWhenLinksUnsupported(t *testing.T) {
	orig := osLink
	defer func() { osLink = orig }()
	osLink = func(oldpath, newpath string) error {
		return &os.LinkError{Op: "link", Old: oldpath, New: newpath, Err: syscall.EPERM}
	}

	dir := t.TempDir()
	part := filepath.Join(dir, ".staging.part")
	if err := os.WriteFile(part, []byte("payload"), 0o600); err != nil {
		t.Fatal(err)
	}

	got, err := CommitUniqueFile(part, dir, "report.pdf")
	if err != nil {
		t.Fatalf("CommitUniqueFile with unsupported links: %v", err)
	}
	if want := filepath.Join(dir, "report.pdf"); got != want {
		t.Fatalf("committed to %s, want %s", got, want)
	}
	commitMustHaveContent(t, got, "payload")
	commitMustNotExist(t, part)
}

func TestDeriveTextKeyDomainSeparation(t *testing.T) {
	peer := []byte("peer-key-material")
	textKey := DeriveTextKey(peer)
	clipboardKey := DeriveClipboardKey(peer)

	if len(textKey) != 32 {
		t.Fatalf("text key length = %d, want 32", len(textKey))
	}
	if bytes.Equal(textKey, clipboardKey) {
		t.Fatal("text and clipboard keys must be domain-separated")
	}
	if !bytes.Equal(textKey, DeriveTextKey(peer)) {
		t.Fatal("text key derivation must be deterministic")
	}
}
