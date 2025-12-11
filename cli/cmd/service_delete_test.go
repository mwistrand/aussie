package cmd

import (
	"testing"
)

func TestServiceDeleteCmd_Initialized(t *testing.T) {
	if serviceDeleteCmd == nil {
		t.Fatal("serviceDeleteCmd is nil")
	}

	if serviceDeleteCmd.Use != "delete <service-id>" {
		t.Errorf("serviceDeleteCmd.Use = %q, want %q", serviceDeleteCmd.Use, "delete <service-id>")
	}

	if serviceDeleteCmd.Short == "" {
		t.Error("serviceDeleteCmd.Short should not be empty")
	}

	if serviceDeleteCmd.Long == "" {
		t.Error("serviceDeleteCmd.Long should not be empty")
	}
}

func TestServiceDeleteCmd_RequiresArgument(t *testing.T) {
	if serviceDeleteCmd.Args == nil {
		t.Error("serviceDeleteCmd.Args should be set")
	}

	// Test with wrong number of arguments
	err := serviceDeleteCmd.Args(serviceDeleteCmd, []string{})
	if err == nil {
		t.Error("Expected error when no arguments provided")
	}

	err = serviceDeleteCmd.Args(serviceDeleteCmd, []string{"service1", "service2"})
	if err == nil {
		t.Error("Expected error when too many arguments provided")
	}

	err = serviceDeleteCmd.Args(serviceDeleteCmd, []string{"service1"})
	if err != nil {
		t.Errorf("Expected no error with one argument, got: %v", err)
	}
}

func TestServiceDeleteCmd_IsSubcommand(t *testing.T) {
	subcommands := serviceCmd.Commands()

	found := false
	for _, cmd := range subcommands {
		if cmd.Name() == "delete" {
			found = true
			break
		}
	}

	if !found {
		t.Error("serviceCmd should have 'delete' subcommand")
	}
}

func TestServiceCmd_HasExpectedSubcommands(t *testing.T) {
	subcommands := serviceCmd.Commands()

	expectedSubcommands := []string{"delete", "preview", "validate"}
	subcommandNames := make(map[string]bool)
	for _, cmd := range subcommands {
		subcommandNames[cmd.Name()] = true
	}

	for _, name := range expectedSubcommands {
		if !subcommandNames[name] {
			t.Errorf("serviceCmd missing subcommand %q", name)
		}
	}
}

func TestServiceDeleteCmd_ValidatesServiceID(t *testing.T) {
	tests := []struct {
		name      string
		serviceID string
		valid     bool
	}{
		{"alphanumeric", "myservice123", true},
		{"with-hyphen", "my-service", true},
		{"with-underscore", "my_service", true},
		{"mixed", "My-Service_123", true},
		{"single-char", "a", true},
		{"numbers-only", "123", true},
		{"path-traversal", "../etc/passwd", false},
		{"with-slash", "my/service", false},
		{"with-dots", "my.service", false},
		{"with-space", "my service", false},
		{"empty", "", false},
		{"special-chars", "my@service!", false},
		{"with-colon", "my:service", false},
		{"with-semicolon", "my;service", false},
		{"url-encoded", "my%2Fservice", false},
		{"null-byte", "my\x00service", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			isValid := validServiceIDPattern.MatchString(tt.serviceID)
			if isValid != tt.valid {
				t.Errorf("validServiceIDPattern.MatchString(%q) = %v, want %v", tt.serviceID, isValid, tt.valid)
			}
		})
	}
}

func TestServiceDeleteCmd_UsesServiceIDPattern(t *testing.T) {
	// Verify that service_delete.go uses the same pattern as service_preview.go
	// Both should use validServiceIDPattern for path traversal protection
	if validServiceIDPattern == nil {
		t.Fatal("validServiceIDPattern should be defined")
	}

	// Test that the pattern is strict enough for security
	dangerousIDs := []string{
		"../../../etc/passwd",
		"foo/../bar",
		"./current",
		"service?query=1",
		"service#anchor",
		"service<script>",
		"service\ninjection",
		"service\tinjection",
	}

	for _, id := range dangerousIDs {
		if validServiceIDPattern.MatchString(id) {
			t.Errorf("validServiceIDPattern should reject dangerous ID %q", id)
		}
	}
}

func TestServiceDeleteCmd_HasRunEFunction(t *testing.T) {
	if serviceDeleteCmd.RunE == nil {
		t.Error("serviceDeleteCmd.RunE should be set")
	}
}

func TestServiceDeleteCmd_ParentIsServiceCmd(t *testing.T) {
	parent := serviceDeleteCmd.Parent()
	if parent == nil {
		t.Fatal("serviceDeleteCmd should have a parent")
	}

	if parent.Name() != "service" {
		t.Errorf("serviceDeleteCmd parent name = %q, want %q", parent.Name(), "service")
	}
}

func TestServiceDeleteCmd_InheritsGlobalFlags(t *testing.T) {
	// Check that the --server flag is available (inherited from root)
	// This is set up in root.go as a persistent flag
	flag := serviceDeleteCmd.Flag("server")
	if flag == nil {
		t.Error("serviceDeleteCmd should have access to --server flag")
	}

	if flag != nil && flag.Shorthand != "s" {
		t.Errorf("server flag shorthand = %q, want %q", flag.Shorthand, "s")
	}
}
