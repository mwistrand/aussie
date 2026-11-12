package api

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestServiceRegistrationMatchesGoldenFixture(t *testing.T) {
	_, sourceFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	fixturePath := filepath.Join(filepath.Dir(sourceFile), "../../../contract-fixtures/control-plane/service-registration-request.json")
	fixture, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}

	var registration ServiceRegistration
	if err := json.Unmarshal(fixture, &registration); err != nil {
		t.Fatalf("decode fixture: %v", err)
	}
	encoded, err := json.Marshal(registration)
	if err != nil {
		t.Fatalf("encode registration: %v", err)
	}

	var want, got any
	if err := json.Unmarshal(fixture, &want); err != nil {
		t.Fatalf("decode expected fixture: %v", err)
	}
	if err := json.Unmarshal(encoded, &got); err != nil {
		t.Fatalf("decode encoded registration: %v", err)
	}
	if !bytes.Equal(mustJSON(t, want), mustJSON(t, got)) {
		t.Fatalf("encoded registration does not match fixture:\n%s", encoded)
	}
}

func mustJSON(t *testing.T, value any) []byte {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal JSON: %v", err)
	}
	return data
}
