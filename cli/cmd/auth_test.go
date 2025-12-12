package cmd

import (
	"testing"
)

func TestAuthCmd_Initialized(t *testing.T) {
	if authCmd == nil {
		t.Fatal("authCmd is nil")
	}

	if authCmd.Use != "auth" {
		t.Errorf("authCmd.Use = %q, want %q", authCmd.Use, "auth")
	}

	if authCmd.Short == "" {
		t.Error("authCmd.Short should not be empty")
	}

	if authCmd.Long == "" {
		t.Error("authCmd.Long should not be empty")
	}
}

func TestStatusCmd_Initialized(t *testing.T) {
	if statusCmd == nil {
		t.Fatal("statusCmd is nil")
	}

	if statusCmd.Use != "status" {
		t.Errorf("statusCmd.Use = %q, want %q", statusCmd.Use, "status")
	}

	if statusCmd.Short == "" {
		t.Error("statusCmd.Short should not be empty")
	}
}

func TestAuthCmd_HasSubcommands(t *testing.T) {
	subcommands := authCmd.Commands()

	if len(subcommands) != 1 {
		t.Errorf("authCmd has %d subcommands, want 1", len(subcommands))
	}

	// Check that status subcommand is present
	subcommandNames := make(map[string]bool)
	for _, cmd := range subcommands {
		subcommandNames[cmd.Use] = true
	}

	if !subcommandNames["status"] {
		t.Error("authCmd missing subcommand 'status'")
	}
}
