package cmd

import (
	"testing"
)

func TestLockoutCmd_Initialized(t *testing.T) {
	if lockoutCmd == nil {
		t.Fatal("lockoutCmd is nil")
	}

	if lockoutCmd.Use != "lockout" {
		t.Errorf("lockoutCmd.Use = %q, want %q", lockoutCmd.Use, "lockout")
	}

	if lockoutCmd.Short == "" {
		t.Error("lockoutCmd.Short should not be empty")
	}

	if lockoutCmd.Long == "" {
		t.Error("lockoutCmd.Long should not be empty")
	}
}

func TestLockoutCmd_HasSubcommands(t *testing.T) {
	subcommands := lockoutCmd.Commands()

	if len(subcommands) != 3 {
		t.Errorf("lockoutCmd has %d subcommands, want 3", len(subcommands))
	}

	// Check that all expected subcommands are present
	subcommandNames := make(map[string]bool)
	for _, cmd := range subcommands {
		subcommandNames[cmd.Name()] = true
	}

	expectedSubcommands := []string{"list", "status", "clear"}
	for _, name := range expectedSubcommands {
		if !subcommandNames[name] {
			t.Errorf("lockoutCmd missing subcommand %q", name)
		}
	}
}

func TestLockoutCmd_IsSubcommandOfAuth(t *testing.T) {
	// Verify lockout is a subcommand of auth
	subcommandNames := make(map[string]bool)
	for _, cmd := range authCmd.Commands() {
		subcommandNames[cmd.Name()] = true
	}

	if !subcommandNames["lockout"] {
		t.Error("lockout should be a subcommand of auth")
	}
}

// List command tests

func TestLockoutListCmd_Initialized(t *testing.T) {
	if lockoutListCmd == nil {
		t.Fatal("lockoutListCmd is nil")
	}

	if lockoutListCmd.Use != "list" {
		t.Errorf("lockoutListCmd.Use = %q, want %q", lockoutListCmd.Use, "list")
	}

	if lockoutListCmd.Short == "" {
		t.Error("lockoutListCmd.Short should not be empty")
	}

	if lockoutListCmd.Long == "" {
		t.Error("lockoutListCmd.Long should not be empty")
	}

	if lockoutListCmd.RunE == nil {
		t.Error("lockoutListCmd.RunE should not be nil")
	}
}

func TestLockoutListCmd_HasFormatFlag(t *testing.T) {
	flag := lockoutListCmd.Flags().Lookup("format")
	if flag == nil {
		t.Fatal("lockoutListCmd should have 'format' flag")
	}

	if flag.DefValue != "table" {
		t.Errorf("format flag default = %q, want %q", flag.DefValue, "table")
	}
}

func TestLockoutListCmd_HasLimitFlag(t *testing.T) {
	flag := lockoutListCmd.Flags().Lookup("limit")
	if flag == nil {
		t.Fatal("lockoutListCmd should have 'limit' flag")
	}

	if flag.DefValue != "100" {
		t.Errorf("limit flag default = %q, want %q", flag.DefValue, "100")
	}
}

// Status command tests

func TestLockoutStatusCmd_Initialized(t *testing.T) {
	if lockoutStatusCmd == nil {
		t.Fatal("lockoutStatusCmd is nil")
	}

	if lockoutStatusCmd.Use != "status" {
		t.Errorf("lockoutStatusCmd.Use = %q, want %q", lockoutStatusCmd.Use, "status")
	}

	if lockoutStatusCmd.Short == "" {
		t.Error("lockoutStatusCmd.Short should not be empty")
	}

	if lockoutStatusCmd.Long == "" {
		t.Error("lockoutStatusCmd.Long should not be empty")
	}

	if lockoutStatusCmd.RunE == nil {
		t.Error("lockoutStatusCmd.RunE should not be nil")
	}
}

func TestLockoutStatusCmd_HasFormatFlag(t *testing.T) {
	flag := lockoutStatusCmd.Flags().Lookup("format")
	if flag == nil {
		t.Fatal("lockoutStatusCmd should have 'format' flag")
	}

	if flag.DefValue != "table" {
		t.Errorf("format flag default = %q, want %q", flag.DefValue, "table")
	}
}

func TestLockoutStatusCmd_HasIpFlag(t *testing.T) {
	flag := lockoutStatusCmd.Flags().Lookup("ip")
	if flag == nil {
		t.Fatal("lockoutStatusCmd should have 'ip' flag")
	}

	if flag.DefValue != "" {
		t.Errorf("ip flag default = %q, want empty string", flag.DefValue)
	}
}

func TestLockoutStatusCmd_HasUserFlag(t *testing.T) {
	flag := lockoutStatusCmd.Flags().Lookup("user")
	if flag == nil {
		t.Fatal("lockoutStatusCmd should have 'user' flag")
	}

	if flag.DefValue != "" {
		t.Errorf("user flag default = %q, want empty string", flag.DefValue)
	}
}

func TestLockoutStatusCmd_HasApikeyFlag(t *testing.T) {
	flag := lockoutStatusCmd.Flags().Lookup("apikey")
	if flag == nil {
		t.Fatal("lockoutStatusCmd should have 'apikey' flag")
	}

	if flag.DefValue != "" {
		t.Errorf("apikey flag default = %q, want empty string", flag.DefValue)
	}
}

// Clear command tests

func TestLockoutClearCmd_Initialized(t *testing.T) {
	if lockoutClearCmd == nil {
		t.Fatal("lockoutClearCmd is nil")
	}

	if lockoutClearCmd.Use != "clear" {
		t.Errorf("lockoutClearCmd.Use = %q, want %q", lockoutClearCmd.Use, "clear")
	}

	if lockoutClearCmd.Short == "" {
		t.Error("lockoutClearCmd.Short should not be empty")
	}

	if lockoutClearCmd.Long == "" {
		t.Error("lockoutClearCmd.Long should not be empty")
	}

	if lockoutClearCmd.RunE == nil {
		t.Error("lockoutClearCmd.RunE should not be nil")
	}
}

func TestLockoutClearCmd_HasIpFlag(t *testing.T) {
	flag := lockoutClearCmd.Flags().Lookup("ip")
	if flag == nil {
		t.Fatal("lockoutClearCmd should have 'ip' flag")
	}

	if flag.DefValue != "" {
		t.Errorf("ip flag default = %q, want empty string", flag.DefValue)
	}
}

func TestLockoutClearCmd_HasUserFlag(t *testing.T) {
	flag := lockoutClearCmd.Flags().Lookup("user")
	if flag == nil {
		t.Fatal("lockoutClearCmd should have 'user' flag")
	}

	if flag.DefValue != "" {
		t.Errorf("user flag default = %q, want empty string", flag.DefValue)
	}
}

func TestLockoutClearCmd_HasApikeyFlag(t *testing.T) {
	flag := lockoutClearCmd.Flags().Lookup("apikey")
	if flag == nil {
		t.Fatal("lockoutClearCmd should have 'apikey' flag")
	}

	if flag.DefValue != "" {
		t.Errorf("apikey flag default = %q, want empty string", flag.DefValue)
	}
}

func TestLockoutClearCmd_HasReasonFlag(t *testing.T) {
	flag := lockoutClearCmd.Flags().Lookup("reason")
	if flag == nil {
		t.Fatal("lockoutClearCmd should have 'reason' flag")
	}

	if flag.DefValue != "" {
		t.Errorf("reason flag default = %q, want empty string", flag.DefValue)
	}
}

func TestLockoutClearCmd_HasAllFlag(t *testing.T) {
	flag := lockoutClearCmd.Flags().Lookup("all")
	if flag == nil {
		t.Fatal("lockoutClearCmd should have 'all' flag")
	}

	if flag.DefValue != "false" {
		t.Errorf("all flag default = %q, want %q", flag.DefValue, "false")
	}
}

func TestLockoutClearCmd_HasForceFlag(t *testing.T) {
	flag := lockoutClearCmd.Flags().Lookup("force")
	if flag == nil {
		t.Fatal("lockoutClearCmd should have 'force' flag")
	}

	if flag.DefValue != "false" {
		t.Errorf("force flag default = %q, want %q", flag.DefValue, "false")
	}
}

// Validation tests

func TestRunLockoutStatus_RequiresIdentifier(t *testing.T) {
	// Reset flags to default state
	lockoutStatusIP = ""
	lockoutStatusUser = ""
	lockoutStatusApiKey = ""

	err := runLockoutStatus(lockoutStatusCmd, []string{})
	if err == nil {
		t.Fatal("runLockoutStatus should return error when no identifier provided")
	}

	expectedMsg := "must specify one of --ip, --user, or --apikey"
	if err.Error() != expectedMsg {
		t.Errorf("error message = %q, want %q", err.Error(), expectedMsg)
	}
}

func TestRunLockoutStatus_RejectsMultipleIdentifiers(t *testing.T) {
	// Set multiple identifiers
	lockoutStatusIP = "192.168.1.1"
	lockoutStatusUser = "test@example.com"
	lockoutStatusApiKey = ""

	err := runLockoutStatus(lockoutStatusCmd, []string{})

	// Reset flags
	lockoutStatusIP = ""
	lockoutStatusUser = ""

	if err == nil {
		t.Fatal("runLockoutStatus should return error when multiple identifiers provided")
	}

	expectedMsg := "must specify only one of --ip, --user, or --apikey"
	if err.Error() != expectedMsg {
		t.Errorf("error message = %q, want %q", err.Error(), expectedMsg)
	}
}

func TestRunLockoutClear_RequiresIdentifierOrAll(t *testing.T) {
	// Reset flags to default state
	lockoutClearIP = ""
	lockoutClearUser = ""
	lockoutClearApiKey = ""
	lockoutClearAll = false

	err := runLockoutClear(lockoutClearCmd, []string{})
	if err == nil {
		t.Fatal("runLockoutClear should return error when no identifier provided")
	}

	expectedMsg := "must specify one of --ip, --user, --apikey, or --all"
	if err.Error() != expectedMsg {
		t.Errorf("error message = %q, want %q", err.Error(), expectedMsg)
	}
}

func TestRunLockoutClear_RejectsMultipleIdentifiers(t *testing.T) {
	// Set multiple identifiers
	lockoutClearIP = "192.168.1.1"
	lockoutClearUser = "test@example.com"
	lockoutClearApiKey = ""
	lockoutClearAll = false

	err := runLockoutClear(lockoutClearCmd, []string{})

	// Reset flags
	lockoutClearIP = ""
	lockoutClearUser = ""

	if err == nil {
		t.Fatal("runLockoutClear should return error when multiple identifiers provided")
	}

	expectedMsg := "must specify only one of --ip, --user, or --apikey"
	if err.Error() != expectedMsg {
		t.Errorf("error message = %q, want %q", err.Error(), expectedMsg)
	}
}

func TestRunLockoutClearAll_RequiresForce(t *testing.T) {
	// Set --all without --force
	lockoutClearIP = ""
	lockoutClearUser = ""
	lockoutClearApiKey = ""
	lockoutClearAll = true
	lockoutClearForce = false

	err := runLockoutClear(lockoutClearCmd, []string{})

	// Reset flags
	lockoutClearAll = false

	if err == nil {
		t.Fatal("runLockoutClear with --all should require --force")
	}

	expectedMsg := "clearing all lockouts requires --force flag"
	if err.Error() != expectedMsg {
		t.Errorf("error message = %q, want %q", err.Error(), expectedMsg)
	}
}
