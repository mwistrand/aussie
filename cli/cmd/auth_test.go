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

	if len(subcommands) != 3 {
		t.Errorf("authCmd has %d subcommands, want 3", len(subcommands))
	}

	// Check that all subcommands are present
	subcommandNames := make(map[string]bool)
	for _, cmd := range subcommands {
		subcommandNames[cmd.Use] = true
	}

	expectedCommands := []string{"login", "logout", "status"}
	for _, name := range expectedCommands {
		if !subcommandNames[name] {
			t.Errorf("authCmd missing subcommand '%s'", name)
		}
	}
}

func TestLoginCmd_Initialized(t *testing.T) {
	if loginCmd == nil {
		t.Fatal("loginCmd is nil")
	}

	if loginCmd.Use != "login" {
		t.Errorf("loginCmd.Use = %q, want %q", loginCmd.Use, "login")
	}

	if loginCmd.Short == "" {
		t.Error("loginCmd.Short should not be empty")
	}

	// Check that --mode flag exists
	flag := loginCmd.Flags().Lookup("mode")
	if flag == nil {
		t.Error("loginCmd missing --mode flag")
	}
}

func TestLogoutCmd_Initialized(t *testing.T) {
	if logoutCmd == nil {
		t.Fatal("logoutCmd is nil")
	}

	if logoutCmd.Use != "logout" {
		t.Errorf("logoutCmd.Use = %q, want %q", logoutCmd.Use, "logout")
	}

	if logoutCmd.Short == "" {
		t.Error("logoutCmd.Short should not be empty")
	}

	// Check that --server flag exists
	flag := logoutCmd.Flags().Lookup("server")
	if flag == nil {
		t.Error("logoutCmd missing --server flag")
	}
}
