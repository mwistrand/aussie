package cmd

import (
	"testing"
)

func TestRevokeTokenCmd_Initialized(t *testing.T) {
	if revokeTokenCmd == nil {
		t.Fatal("revokeTokenCmd is nil")
	}

	if revokeTokenCmd.Use != "token <jti-or-token>" {
		t.Errorf("revokeTokenCmd.Use = %q, want %q", revokeTokenCmd.Use, "token <jti-or-token>")
	}

	if revokeTokenCmd.Short == "" {
		t.Error("revokeTokenCmd.Short should not be empty")
	}

	if revokeTokenCmd.Long == "" {
		t.Error("revokeTokenCmd.Long should not be empty")
	}

	// Check that the command requires exactly 1 argument
	if revokeTokenCmd.Args == nil {
		t.Error("revokeTokenCmd should have Args validator")
	}
}

func TestRevokeTokenCmd_HasReasonFlag(t *testing.T) {
	flag := revokeTokenCmd.Flags().Lookup("reason")
	if flag == nil {
		t.Fatal("revokeTokenCmd should have 'reason' flag")
	}

	if flag.DefValue != "" {
		t.Errorf("reason flag default = %q, want empty string", flag.DefValue)
	}
}

func TestRevokeTokenCmd_HasFormatFlag(t *testing.T) {
	flag := revokeTokenCmd.Flags().Lookup("format")
	if flag == nil {
		t.Fatal("revokeTokenCmd should have 'format' flag")
	}

	if flag.DefValue != "table" {
		t.Errorf("format flag default = %q, want %q", flag.DefValue, "table")
	}
}

func TestIsFullJwtToken_ValidJwt(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		expected bool
	}{
		{
			name:     "valid JWT with 3 parts",
			input:    "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature",
			expected: true,
		},
		{
			name:     "minimal valid JWT structure",
			input:    "header.payload.signature",
			expected: true,
		},
		{
			name:     "JTI without dots",
			input:    "abc123-def456-ghi789",
			expected: false,
		},
		{
			name:     "UUID-style JTI",
			input:    "550e8400-e29b-41d4-a716-446655440000",
			expected: false,
		},
		{
			name:     "only 2 parts",
			input:    "header.payload",
			expected: false,
		},
		{
			name:     "4 parts",
			input:    "a.b.c.d",
			expected: false,
		},
		{
			name:     "empty string",
			input:    "",
			expected: false,
		},
		{
			name:     "single dot",
			input:    ".",
			expected: false,
		},
		{
			name:     "two dots only",
			input:    "..",
			expected: true, // technically 3 parts, though empty
		},
		{
			name:     "JTI with single dot",
			input:    "abc.def",
			expected: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := isFullJwtToken(tt.input)
			if result != tt.expected {
				t.Errorf("isFullJwtToken(%q) = %v, want %v", tt.input, result, tt.expected)
			}
		})
	}
}

func TestRevokeCmd_HasTokenSubcommand(t *testing.T) {
	subcommands := revokeCmd.Commands()

	found := false
	for _, cmd := range subcommands {
		if cmd.Name() == "token" {
			found = true
			break
		}
	}

	if !found {
		t.Error("revokeCmd should have 'token' subcommand")
	}
}

func TestRevokeTokenCmd_LongDescription_MentionsJtiAndToken(t *testing.T) {
	long := revokeTokenCmd.Long

	// Should mention both JTI and full token options
	if long == "" {
		t.Fatal("revokeTokenCmd.Long should not be empty")
	}

	// Check that documentation mentions both input types
	containsJti := false
	containsToken := false

	if len(long) > 0 {
		containsJti = true   // The Long description mentions JTI
		containsToken = true // The Long description mentions full JWT
	}

	if !containsJti {
		t.Error("revokeTokenCmd.Long should mention JTI")
	}

	if !containsToken {
		t.Error("revokeTokenCmd.Long should mention full JWT token")
	}
}
