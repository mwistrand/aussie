package cmd

import (
	"testing"
)

func TestGroupsCmd_Initialized(t *testing.T) {
	if groupsCmd == nil {
		t.Fatal("groupsCmd is nil")
	}

	if groupsCmd.Use != "groups" {
		t.Errorf("groupsCmd.Use = %q, want %q", groupsCmd.Use, "groups")
	}

	if groupsCmd.Short == "" {
		t.Error("groupsCmd.Short should not be empty")
	}

	if groupsCmd.Long == "" {
		t.Error("groupsCmd.Long should not be empty")
	}
}

func TestGroupsCmd_HasSubcommands(t *testing.T) {
	subcommands := groupsCmd.Commands()

	if len(subcommands) != 5 {
		t.Errorf("groupsCmd has %d subcommands, want 5", len(subcommands))
	}

	// Check that all expected subcommands are present
	subcommandNames := make(map[string]bool)
	for _, cmd := range subcommands {
		subcommandNames[cmd.Name()] = true
	}

	expectedSubcommands := []string{"create", "list", "get", "update", "delete"}
	for _, name := range expectedSubcommands {
		if !subcommandNames[name] {
			t.Errorf("groupsCmd missing subcommand %q", name)
		}
	}
}

func TestGroupsCreateCmd_Initialized(t *testing.T) {
	if groupsCreateCmd == nil {
		t.Fatal("groupsCreateCmd is nil")
	}

	if groupsCreateCmd.Use != "create" {
		t.Errorf("groupsCreateCmd.Use = %q, want %q", groupsCreateCmd.Use, "create")
	}

	if groupsCreateCmd.Short == "" {
		t.Error("groupsCreateCmd.Short should not be empty")
	}

	// Check that id flag exists and is required
	idFlag := groupsCreateCmd.Flags().Lookup("id")
	if idFlag == nil {
		t.Error("groupsCreateCmd should have 'id' flag")
	}

	// Check that display-name flag exists
	displayNameFlag := groupsCreateCmd.Flags().Lookup("display-name")
	if displayNameFlag == nil {
		t.Error("groupsCreateCmd should have 'display-name' flag")
	}
	if displayNameFlag != nil && displayNameFlag.Shorthand != "d" {
		t.Errorf("display-name flag shorthand = %q, want %q", displayNameFlag.Shorthand, "d")
	}

	// Check that description flag exists
	descFlag := groupsCreateCmd.Flags().Lookup("description")
	if descFlag == nil {
		t.Error("groupsCreateCmd should have 'description' flag")
	}

	// Check that permissions flag exists
	permissionsFlag := groupsCreateCmd.Flags().Lookup("permissions")
	if permissionsFlag == nil {
		t.Error("groupsCreateCmd should have 'permissions' flag")
	}
	if permissionsFlag != nil && permissionsFlag.Shorthand != "p" {
		t.Errorf("permissions flag shorthand = %q, want %q", permissionsFlag.Shorthand, "p")
	}
}

func TestGroupsListCmd_Initialized(t *testing.T) {
	if groupsListCmd == nil {
		t.Fatal("groupsListCmd is nil")
	}

	if groupsListCmd.Use != "list" {
		t.Errorf("groupsListCmd.Use = %q, want %q", groupsListCmd.Use, "list")
	}

	if groupsListCmd.Short == "" {
		t.Error("groupsListCmd.Short should not be empty")
	}
}

func TestGroupsGetCmd_Initialized(t *testing.T) {
	if groupsGetCmd == nil {
		t.Fatal("groupsGetCmd is nil")
	}

	if groupsGetCmd.Use != "get <group-id>" {
		t.Errorf("groupsGetCmd.Use = %q, want %q", groupsGetCmd.Use, "get <group-id>")
	}

	if groupsGetCmd.Short == "" {
		t.Error("groupsGetCmd.Short should not be empty")
	}

	// Check that the command requires exactly 1 argument
	if groupsGetCmd.Args == nil {
		t.Error("groupsGetCmd should have Args validator")
	}
}

func TestGroupsUpdateCmd_Initialized(t *testing.T) {
	if groupsUpdateCmd == nil {
		t.Fatal("groupsUpdateCmd is nil")
	}

	if groupsUpdateCmd.Use != "update <group-id>" {
		t.Errorf("groupsUpdateCmd.Use = %q, want %q", groupsUpdateCmd.Use, "update <group-id>")
	}

	if groupsUpdateCmd.Short == "" {
		t.Error("groupsUpdateCmd.Short should not be empty")
	}

	// Check that the command requires exactly 1 argument
	if groupsUpdateCmd.Args == nil {
		t.Error("groupsUpdateCmd should have Args validator")
	}

	// Check that display-name flag exists
	displayNameFlag := groupsUpdateCmd.Flags().Lookup("display-name")
	if displayNameFlag == nil {
		t.Error("groupsUpdateCmd should have 'display-name' flag")
	}
	if displayNameFlag != nil && displayNameFlag.Shorthand != "d" {
		t.Errorf("display-name flag shorthand = %q, want %q", displayNameFlag.Shorthand, "d")
	}

	// Check that description flag exists
	descFlag := groupsUpdateCmd.Flags().Lookup("description")
	if descFlag == nil {
		t.Error("groupsUpdateCmd should have 'description' flag")
	}

	// Check that permissions flag exists
	permissionsFlag := groupsUpdateCmd.Flags().Lookup("permissions")
	if permissionsFlag == nil {
		t.Error("groupsUpdateCmd should have 'permissions' flag")
	}
	if permissionsFlag != nil && permissionsFlag.Shorthand != "p" {
		t.Errorf("permissions flag shorthand = %q, want %q", permissionsFlag.Shorthand, "p")
	}
}

func TestGroupsDeleteCmd_Initialized(t *testing.T) {
	if groupsDeleteCmd == nil {
		t.Fatal("groupsDeleteCmd is nil")
	}

	if groupsDeleteCmd.Use != "delete <group-id>" {
		t.Errorf("groupsDeleteCmd.Use = %q, want %q", groupsDeleteCmd.Use, "delete <group-id>")
	}

	if groupsDeleteCmd.Short == "" {
		t.Error("groupsDeleteCmd.Short should not be empty")
	}

	// Check that the command requires exactly 1 argument
	if groupsDeleteCmd.Args == nil {
		t.Error("groupsDeleteCmd should have Args validator")
	}
}

func TestValidGroupIDPattern(t *testing.T) {
	validIDs := []string{
		"service-admin",
		"platform_team",
		"developers",
		"team123",
		"my-group-1",
		"GROUP_NAME",
		"demo-service.admin",
		"demo-service.dev",
		"my.nested.group",
	}

	for _, id := range validIDs {
		if !validGroupIDPattern.MatchString(id) {
			t.Errorf("validGroupIDPattern should match %q", id)
		}
	}

	invalidIDs := []string{
		"../path-traversal",
		"group/name",
		"group name",
		"group@name",
		"",
	}

	for _, id := range invalidIDs {
		if validGroupIDPattern.MatchString(id) {
			t.Errorf("validGroupIDPattern should not match %q", id)
		}
	}
}
