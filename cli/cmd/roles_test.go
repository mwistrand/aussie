package cmd

import (
	"testing"
)

func TestRolesCmd_Initialized(t *testing.T) {
	if rolesCmd == nil {
		t.Fatal("rolesCmd is nil")
	}

	if rolesCmd.Use != "roles" {
		t.Errorf("rolesCmd.Use = %q, want %q", rolesCmd.Use, "roles")
	}

	if rolesCmd.Short == "" {
		t.Error("rolesCmd.Short should not be empty")
	}

	if rolesCmd.Long == "" {
		t.Error("rolesCmd.Long should not be empty")
	}
}

func TestRolesCmd_HasSubcommands(t *testing.T) {
	subcommands := rolesCmd.Commands()

	if len(subcommands) != 5 {
		t.Errorf("rolesCmd has %d subcommands, want 5", len(subcommands))
	}

	// Check that all expected subcommands are present
	subcommandNames := make(map[string]bool)
	for _, cmd := range subcommands {
		subcommandNames[cmd.Name()] = true
	}

	expectedSubcommands := []string{"create", "list", "get", "update", "delete"}
	for _, name := range expectedSubcommands {
		if !subcommandNames[name] {
			t.Errorf("rolesCmd missing subcommand %q", name)
		}
	}
}

func TestRolesCreateCmd_Initialized(t *testing.T) {
	if rolesCreateCmd == nil {
		t.Fatal("rolesCreateCmd is nil")
	}

	if rolesCreateCmd.Use != "create" {
		t.Errorf("rolesCreateCmd.Use = %q, want %q", rolesCreateCmd.Use, "create")
	}

	if rolesCreateCmd.Short == "" {
		t.Error("rolesCreateCmd.Short should not be empty")
	}

	// Check that id flag exists and is required
	idFlag := rolesCreateCmd.Flags().Lookup("id")
	if idFlag == nil {
		t.Error("rolesCreateCmd should have 'id' flag")
	}

	// Check that display-name flag exists
	displayNameFlag := rolesCreateCmd.Flags().Lookup("display-name")
	if displayNameFlag == nil {
		t.Error("rolesCreateCmd should have 'display-name' flag")
	}
	if displayNameFlag != nil && displayNameFlag.Shorthand != "d" {
		t.Errorf("display-name flag shorthand = %q, want %q", displayNameFlag.Shorthand, "d")
	}

	// Check that description flag exists
	descFlag := rolesCreateCmd.Flags().Lookup("description")
	if descFlag == nil {
		t.Error("rolesCreateCmd should have 'description' flag")
	}

	// Check that permissions flag exists
	permissionsFlag := rolesCreateCmd.Flags().Lookup("permissions")
	if permissionsFlag == nil {
		t.Error("rolesCreateCmd should have 'permissions' flag")
	}
	if permissionsFlag != nil && permissionsFlag.Shorthand != "p" {
		t.Errorf("permissions flag shorthand = %q, want %q", permissionsFlag.Shorthand, "p")
	}
}

func TestRolesListCmd_Initialized(t *testing.T) {
	if rolesListCmd == nil {
		t.Fatal("rolesListCmd is nil")
	}

	if rolesListCmd.Use != "list" {
		t.Errorf("rolesListCmd.Use = %q, want %q", rolesListCmd.Use, "list")
	}

	if rolesListCmd.Short == "" {
		t.Error("rolesListCmd.Short should not be empty")
	}
}

func TestRolesGetCmd_Initialized(t *testing.T) {
	if rolesGetCmd == nil {
		t.Fatal("rolesGetCmd is nil")
	}

	if rolesGetCmd.Use != "get <role-id>" {
		t.Errorf("rolesGetCmd.Use = %q, want %q", rolesGetCmd.Use, "get <role-id>")
	}

	if rolesGetCmd.Short == "" {
		t.Error("rolesGetCmd.Short should not be empty")
	}

	// Check that the command requires exactly 1 argument
	if rolesGetCmd.Args == nil {
		t.Error("rolesGetCmd should have Args validator")
	}
}

func TestRolesUpdateCmd_Initialized(t *testing.T) {
	if rolesUpdateCmd == nil {
		t.Fatal("rolesUpdateCmd is nil")
	}

	if rolesUpdateCmd.Use != "update <role-id>" {
		t.Errorf("rolesUpdateCmd.Use = %q, want %q", rolesUpdateCmd.Use, "update <role-id>")
	}

	if rolesUpdateCmd.Short == "" {
		t.Error("rolesUpdateCmd.Short should not be empty")
	}

	// Check that the command requires exactly 1 argument
	if rolesUpdateCmd.Args == nil {
		t.Error("rolesUpdateCmd should have Args validator")
	}

	// Check that display-name flag exists
	displayNameFlag := rolesUpdateCmd.Flags().Lookup("display-name")
	if displayNameFlag == nil {
		t.Error("rolesUpdateCmd should have 'display-name' flag")
	}
	if displayNameFlag != nil && displayNameFlag.Shorthand != "d" {
		t.Errorf("display-name flag shorthand = %q, want %q", displayNameFlag.Shorthand, "d")
	}

	// Check that description flag exists
	descFlag := rolesUpdateCmd.Flags().Lookup("description")
	if descFlag == nil {
		t.Error("rolesUpdateCmd should have 'description' flag")
	}

	// Check that permissions flag exists
	permissionsFlag := rolesUpdateCmd.Flags().Lookup("permissions")
	if permissionsFlag == nil {
		t.Error("rolesUpdateCmd should have 'permissions' flag")
	}
	if permissionsFlag != nil && permissionsFlag.Shorthand != "p" {
		t.Errorf("permissions flag shorthand = %q, want %q", permissionsFlag.Shorthand, "p")
	}

	// Check that add-permissions flag exists
	addPermissionsFlag := rolesUpdateCmd.Flags().Lookup("add-permissions")
	if addPermissionsFlag == nil {
		t.Error("rolesUpdateCmd should have 'add-permissions' flag")
	}

	// Check that remove-permissions flag exists
	removePermissionsFlag := rolesUpdateCmd.Flags().Lookup("remove-permissions")
	if removePermissionsFlag == nil {
		t.Error("rolesUpdateCmd should have 'remove-permissions' flag")
	}
}

func TestRolesDeleteCmd_Initialized(t *testing.T) {
	if rolesDeleteCmd == nil {
		t.Fatal("rolesDeleteCmd is nil")
	}

	if rolesDeleteCmd.Use != "delete <role-id>" {
		t.Errorf("rolesDeleteCmd.Use = %q, want %q", rolesDeleteCmd.Use, "delete <role-id>")
	}

	if rolesDeleteCmd.Short == "" {
		t.Error("rolesDeleteCmd.Short should not be empty")
	}

	// Check that the command requires exactly 1 argument
	if rolesDeleteCmd.Args == nil {
		t.Error("rolesDeleteCmd should have Args validator")
	}
}

func TestValidRoleIDPattern(t *testing.T) {
	validIDs := []string{
		"service-admin",
		"platform_team",
		"developers",
		"team123",
		"my-role-1",
		"ROLE_NAME",
		"demo-service.admin",
		"demo-service.dev",
		"my.nested.role",
	}

	for _, id := range validIDs {
		if !validRoleIDPattern.MatchString(id) {
			t.Errorf("validRoleIDPattern should match %q", id)
		}
	}

	invalidIDs := []string{
		"../path-traversal",
		"role/name",
		"role name",
		"role@name",
		"",
	}

	for _, id := range invalidIDs {
		if validRoleIDPattern.MatchString(id) {
			t.Errorf("validRoleIDPattern should not match %q", id)
		}
	}
}
