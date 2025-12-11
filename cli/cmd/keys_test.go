package cmd

import (
	"testing"
)

func TestKeysCmd_Initialized(t *testing.T) {
	if keysCmd == nil {
		t.Fatal("keysCmd is nil")
	}

	if keysCmd.Use != "keys" {
		t.Errorf("keysCmd.Use = %q, want %q", keysCmd.Use, "keys")
	}

	if keysCmd.Short == "" {
		t.Error("keysCmd.Short should not be empty")
	}

	if keysCmd.Long == "" {
		t.Error("keysCmd.Long should not be empty")
	}
}

func TestKeysCreateCmd_Initialized(t *testing.T) {
	if keysCreateCmd == nil {
		t.Fatal("keysCreateCmd is nil")
	}

	if keysCreateCmd.Use != "create" {
		t.Errorf("keysCreateCmd.Use = %q, want %q", keysCreateCmd.Use, "create")
	}

	if keysCreateCmd.Short == "" {
		t.Error("keysCreateCmd.Short should not be empty")
	}

	// Check that name flag exists and is required
	nameFlag := keysCreateCmd.Flags().Lookup("name")
	if nameFlag == nil {
		t.Error("keysCreateCmd should have 'name' flag")
	}
	if nameFlag != nil && nameFlag.Shorthand != "n" {
		t.Errorf("name flag shorthand = %q, want %q", nameFlag.Shorthand, "n")
	}

	// Check that description flag exists
	descFlag := keysCreateCmd.Flags().Lookup("description")
	if descFlag == nil {
		t.Error("keysCreateCmd should have 'description' flag")
	}
	if descFlag != nil && descFlag.Shorthand != "d" {
		t.Errorf("description flag shorthand = %q, want %q", descFlag.Shorthand, "d")
	}

	// Check that ttl flag exists
	ttlFlag := keysCreateCmd.Flags().Lookup("ttl")
	if ttlFlag == nil {
		t.Error("keysCreateCmd should have 'ttl' flag")
	}
	if ttlFlag != nil && ttlFlag.Shorthand != "t" {
		t.Errorf("ttl flag shorthand = %q, want %q", ttlFlag.Shorthand, "t")
	}

	// Check that permissions flag exists
	permissionsFlag := keysCreateCmd.Flags().Lookup("permissions")
	if permissionsFlag == nil {
		t.Error("keysCreateCmd should have 'permissions' flag")
	}
	if permissionsFlag != nil && permissionsFlag.Shorthand != "p" {
		t.Errorf("permissions flag shorthand = %q, want %q", permissionsFlag.Shorthand, "p")
	}
}

func TestKeysListCmd_Initialized(t *testing.T) {
	if keysListCmd == nil {
		t.Fatal("keysListCmd is nil")
	}

	if keysListCmd.Use != "list" {
		t.Errorf("keysListCmd.Use = %q, want %q", keysListCmd.Use, "list")
	}

	if keysListCmd.Short == "" {
		t.Error("keysListCmd.Short should not be empty")
	}
}

func TestKeysRevokeCmd_Initialized(t *testing.T) {
	if keysRevokeCmd == nil {
		t.Fatal("keysRevokeCmd is nil")
	}

	if keysRevokeCmd.Use != "revoke <key-id>" {
		t.Errorf("keysRevokeCmd.Use = %q, want %q", keysRevokeCmd.Use, "revoke <key-id>")
	}

	if keysRevokeCmd.Short == "" {
		t.Error("keysRevokeCmd.Short should not be empty")
	}

	// Check that the command requires exactly 1 argument
	if keysRevokeCmd.Args == nil {
		t.Error("keysRevokeCmd should have Args validator")
	}
}

func TestKeysCmd_HasSubcommands(t *testing.T) {
	subcommands := keysCmd.Commands()

	if len(subcommands) != 3 {
		t.Errorf("keysCmd has %d subcommands, want 3", len(subcommands))
	}

	// Check that all expected subcommands are present
	subcommandNames := make(map[string]bool)
	for _, cmd := range subcommands {
		// Use only the command name without arguments
		name := cmd.Name()
		subcommandNames[name] = true
	}

	expectedSubcommands := []string{"create", "list", "revoke"}
	for _, name := range expectedSubcommands {
		if !subcommandNames[name] {
			t.Errorf("keysCmd missing subcommand %q", name)
		}
	}
}

func TestKeysCreateCmd_DefaultPermissions(t *testing.T) {
	permissionsFlag := keysCreateCmd.Flags().Lookup("permissions")
	if permissionsFlag == nil {
		t.Fatal("keysCreateCmd should have 'permissions' flag")
	}

	// Default value should be "*" (wildcard)
	if permissionsFlag.DefValue != "[*]" {
		t.Errorf("permissions default = %q, want %q", permissionsFlag.DefValue, "[*]")
	}
}

func TestKeysCreateCmd_TtlDefaultZero(t *testing.T) {
	ttlFlag := keysCreateCmd.Flags().Lookup("ttl")
	if ttlFlag == nil {
		t.Fatal("keysCreateCmd should have 'ttl' flag")
	}

	// Default value should be "0" (no expiration)
	if ttlFlag.DefValue != "0" {
		t.Errorf("ttl default = %q, want %q", ttlFlag.DefValue, "0")
	}
}
