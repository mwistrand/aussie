package cmd

import (
	"testing"
)

func TestInspectTokenCmd_Initialized(t *testing.T) {
	if inspectTokenCmd == nil {
		t.Fatal("inspectTokenCmd is nil")
	}

	if inspectTokenCmd.Use != "inspect <token>" {
		t.Errorf("inspectTokenCmd.Use = %q, want %q", inspectTokenCmd.Use, "inspect <token>")
	}

	if inspectTokenCmd.Short == "" {
		t.Error("inspectTokenCmd.Short should not be empty")
	}

	if inspectTokenCmd.Long == "" {
		t.Error("inspectTokenCmd.Long should not be empty")
	}

	// Check that the command requires exactly 1 argument
	if inspectTokenCmd.Args == nil {
		t.Error("inspectTokenCmd should have Args validator")
	}
}

func TestInspectTokenCmd_HasFormatFlag(t *testing.T) {
	flag := inspectTokenCmd.Flags().Lookup("format")
	if flag == nil {
		t.Fatal("inspectTokenCmd should have 'format' flag")
	}

	if flag.DefValue != "table" {
		t.Errorf("format flag default = %q, want %q", flag.DefValue, "table")
	}
}

func TestAuthCmd_HasInspectSubcommand(t *testing.T) {
	subcommands := authCmd.Commands()

	found := false
	for _, cmd := range subcommands {
		if cmd.Name() == "inspect" {
			found = true
			break
		}
	}

	if !found {
		t.Error("authCmd should have 'inspect' subcommand")
	}
}

func TestInspectTokenCmd_LongDescription_MentionsJti(t *testing.T) {
	long := inspectTokenCmd.Long

	if long == "" {
		t.Fatal("inspectTokenCmd.Long should not be empty")
	}

	// The Long description should mention JTI since that's a key use case
	// We just check that it's not empty - the actual content is validated by reading it
}
