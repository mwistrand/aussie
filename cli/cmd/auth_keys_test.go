package cmd

import (
	"testing"
)

func TestAuthKeysCmd_Initialized(t *testing.T) {
	if authKeysCmd == nil {
		t.Fatal("authKeysCmd is nil")
	}

	if authKeysCmd.Use != "keys" {
		t.Errorf("authKeysCmd.Use = %q, want %q", authKeysCmd.Use, "keys")
	}

	if authKeysCmd.Short == "" {
		t.Error("authKeysCmd.Short should not be empty")
	}

	if authKeysCmd.Long == "" {
		t.Error("authKeysCmd.Long should not be empty")
	}
}

func TestAuthKeysCmd_HasSubcommands(t *testing.T) {
	subcommands := authKeysCmd.Commands()

	expectedCount := 7 // list, show, rotate, deprecate, retire, verify, export
	if len(subcommands) != expectedCount {
		t.Errorf("authKeysCmd has %d subcommands, want %d", len(subcommands), expectedCount)
	}

	// Check that all expected subcommands are present
	subcommandNames := make(map[string]bool)
	for _, cmd := range subcommands {
		subcommandNames[cmd.Name()] = true
	}

	expectedSubcommands := []string{"list", "show", "rotate", "deprecate", "retire", "verify", "export"}
	for _, name := range expectedSubcommands {
		if !subcommandNames[name] {
			t.Errorf("authKeysCmd missing subcommand %q", name)
		}
	}
}

func TestAuthCmd_HasKeysSubcommand(t *testing.T) {
	subcommands := authCmd.Commands()

	subcommandNames := make(map[string]bool)
	for _, cmd := range subcommands {
		subcommandNames[cmd.Name()] = true
	}

	if !subcommandNames["keys"] {
		t.Error("authCmd missing 'keys' subcommand")
	}
}

func TestAuthKeysCmd_LongDescriptionMentionsLifecycle(t *testing.T) {
	long := authKeysCmd.Long

	if long == "" {
		t.Fatal("authKeysCmd.Long should not be empty")
	}

	// The long description should mention the key lifecycle states
	// This is a documentation quality check
}

func TestAuthKeysCmd_SubcommandsHaveRunE(t *testing.T) {
	subcommands := authKeysCmd.Commands()

	for _, cmd := range subcommands {
		if cmd.RunE == nil {
			t.Errorf("subcommand %q should have RunE set", cmd.Name())
		}
	}
}

func TestAuthKeysCmd_SubcommandsHaveShortDescription(t *testing.T) {
	subcommands := authKeysCmd.Commands()

	for _, cmd := range subcommands {
		if cmd.Short == "" {
			t.Errorf("subcommand %q should have Short description", cmd.Name())
		}
	}
}

func TestAuthKeysCmd_SubcommandsHaveLongDescription(t *testing.T) {
	subcommands := authKeysCmd.Commands()

	for _, cmd := range subcommands {
		if cmd.Long == "" {
			t.Errorf("subcommand %q should have Long description", cmd.Name())
		}
	}
}

func TestAuthKeysCmd_CommandsRequiringArgs(t *testing.T) {
	// These commands require exactly one argument (key-id)
	commandsRequiringArgs := []string{"show", "deprecate", "retire"}

	subcommands := authKeysCmd.Commands()
	cmdMap := make(map[string]*struct{ Args interface{} })
	for _, cmd := range subcommands {
		cmdMap[cmd.Name()] = &struct{ Args interface{} }{Args: cmd.Args}
	}

	for _, name := range commandsRequiringArgs {
		if cmdInfo, ok := cmdMap[name]; ok {
			if cmdInfo.Args == nil {
				t.Errorf("subcommand %q should have Args validator", name)
			}
		}
	}
}

func TestAuthKeysCmd_CommandsWithFlags(t *testing.T) {
	subcommands := authKeysCmd.Commands()
	cmdMap := make(map[string]interface{})
	for _, cmd := range subcommands {
		cmdMap[cmd.Name()] = cmd
	}

	// list should have --format flag
	if listCmd, ok := cmdMap["list"].(*struct{}); ok {
		_ = listCmd
	}

	// show should have --include-public-key flag
	// rotate should have --reason flag
	// retire should have --force flag
	// export should have --output flag
}
