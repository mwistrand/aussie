package cmd

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestServicePermissionsCmd_Initialized(t *testing.T) {
	if servicePermissionsCmd == nil {
		t.Fatal("servicePermissionsCmd is nil")
	}

	if servicePermissionsCmd.Use != "permissions" {
		t.Errorf("servicePermissionsCmd.Use = %q, want %q", servicePermissionsCmd.Use, "permissions")
	}

	if servicePermissionsCmd.Short == "" {
		t.Error("servicePermissionsCmd.Short should not be empty")
	}
}

func TestServicePermissionsGetCmd_Initialized(t *testing.T) {
	if servicePermissionsGetCmd == nil {
		t.Fatal("servicePermissionsGetCmd is nil")
	}

	if servicePermissionsGetCmd.Use != "get <service-id>" {
		t.Errorf("servicePermissionsGetCmd.Use = %q, want %q", servicePermissionsGetCmd.Use, "get <service-id>")
	}
}

func TestServicePermissionsSetCmd_Initialized(t *testing.T) {
	if servicePermissionsSetCmd == nil {
		t.Fatal("servicePermissionsSetCmd is nil")
	}

	if servicePermissionsSetCmd.Use != "set <service-id>" {
		t.Errorf("servicePermissionsSetCmd.Use = %q, want %q", servicePermissionsSetCmd.Use, "set <service-id>")
	}

	// Verify required flags
	fileFlag := servicePermissionsSetCmd.Flags().Lookup("file")
	if fileFlag == nil {
		t.Error("servicePermissionsSetCmd should have 'file' flag")
	}

	versionFlag := servicePermissionsSetCmd.Flags().Lookup("version")
	if versionFlag == nil {
		t.Error("servicePermissionsSetCmd should have 'version' flag")
	}
}

func TestServicePermissionsGrantCmd_Initialized(t *testing.T) {
	if servicePermissionsGrantCmd == nil {
		t.Fatal("servicePermissionsGrantCmd is nil")
	}

	if servicePermissionsGrantCmd.Use != "grant <service-id>" {
		t.Errorf("servicePermissionsGrantCmd.Use = %q, want %q", servicePermissionsGrantCmd.Use, "grant <service-id>")
	}

	// Verify required flags
	opFlag := servicePermissionsGrantCmd.Flags().Lookup("operation")
	if opFlag == nil {
		t.Error("servicePermissionsGrantCmd should have 'operation' flag")
	}

	permissionFlag := servicePermissionsGrantCmd.Flags().Lookup("permission")
	if permissionFlag == nil {
		t.Error("servicePermissionsGrantCmd should have 'permission' flag")
	}
}

func TestServicePermissionsRevokeCmd_Initialized(t *testing.T) {
	if servicePermissionsRevokeCmd == nil {
		t.Fatal("servicePermissionsRevokeCmd is nil")
	}

	if servicePermissionsRevokeCmd.Use != "revoke <service-id>" {
		t.Errorf("servicePermissionsRevokeCmd.Use = %q, want %q", servicePermissionsRevokeCmd.Use, "revoke <service-id>")
	}

	// Verify required flags
	opFlag := servicePermissionsRevokeCmd.Flags().Lookup("operation")
	if opFlag == nil {
		t.Error("servicePermissionsRevokeCmd should have 'operation' flag")
	}

	permissionFlag := servicePermissionsRevokeCmd.Flags().Lookup("permission")
	if permissionFlag == nil {
		t.Error("servicePermissionsRevokeCmd should have 'permission' flag")
	}
}

func TestPermissionPolicyResponse_JSONRoundTrip(t *testing.T) {
	response := PermissionPolicyResponse{
		PermissionPolicy: &ServicePermissionPolicy{
			Permissions: map[string]OperationPermission{
				"service.config.read": {
					AnyOfPermissions: []string{"admin", "reader"},
				},
			},
		},
		Version: 5,
	}

	data, err := json.Marshal(response)
	if err != nil {
		t.Fatalf("Marshal error: %v", err)
	}

	var unmarshaled PermissionPolicyResponse
	if err := json.Unmarshal(data, &unmarshaled); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}

	if unmarshaled.Version != 5 {
		t.Errorf("Version = %d, want 5", unmarshaled.Version)
	}

	if unmarshaled.PermissionPolicy == nil {
		t.Fatal("PermissionPolicy should not be nil")
	}

	if len(unmarshaled.PermissionPolicy.Permissions) != 1 {
		t.Errorf("Permissions length = %d, want 1", len(unmarshaled.PermissionPolicy.Permissions))
	}

	perm, ok := unmarshaled.PermissionPolicy.Permissions["service.config.read"]
	if !ok {
		t.Error("Missing 'service.config.read' permission")
	} else if len(perm.AnyOfPermissions) != 2 {
		t.Errorf("AnyOfPermissions length = %d, want 2", len(perm.AnyOfPermissions))
	}
}

func TestPermissionPolicyResponse_NullPolicy(t *testing.T) {
	jsonData := `{"permissionPolicy": null, "version": 1}`

	var response PermissionPolicyResponse
	if err := json.Unmarshal([]byte(jsonData), &response); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}

	if response.PermissionPolicy != nil {
		t.Error("PermissionPolicy should be nil")
	}
	if response.Version != 1 {
		t.Errorf("Version = %d, want 1", response.Version)
	}
}

func TestServicePermissionPolicy_EmptyPermissions(t *testing.T) {
	policy := ServicePermissionPolicy{
		Permissions: map[string]OperationPermission{},
	}

	data, err := json.Marshal(policy)
	if err != nil {
		t.Fatalf("Marshal error: %v", err)
	}

	var unmarshaled ServicePermissionPolicy
	if err := json.Unmarshal(data, &unmarshaled); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}

	// Note: Go's JSON unmarshaling converts empty maps to nil when using omitempty
	// This is expected behavior - an empty permissions map is equivalent to nil
	if len(unmarshaled.Permissions) != 0 {
		t.Errorf("Permissions length = %d, want 0", len(unmarshaled.Permissions))
	}
}

func TestServicePermissionPolicy_ParseFromFile(t *testing.T) {
	tmpDir := t.TempDir()
	policyFilePath := filepath.Join(tmpDir, "policy.json")

	policyContent := `{
		"permissions": {
			"service.config.read": {
				"anyOfPermissions": ["admin", "service.reader"]
			},
			"service.config.update": {
				"anyOfPermissions": ["admin"]
			},
			"service.config.delete": {
				"anyOfPermissions": ["super-admin"]
			}
		}
	}`
	if err := os.WriteFile(policyFilePath, []byte(policyContent), 0644); err != nil {
		t.Fatalf("Failed to write policy file: %v", err)
	}

	data, err := os.ReadFile(policyFilePath)
	if err != nil {
		t.Fatalf("Failed to read policy file: %v", err)
	}

	var policy ServicePermissionPolicy
	if err := json.Unmarshal(data, &policy); err != nil {
		t.Fatalf("Failed to parse policy: %v", err)
	}

	if len(policy.Permissions) != 3 {
		t.Errorf("Permissions length = %d, want 3", len(policy.Permissions))
	}

	readPerm, ok := policy.Permissions["service.config.read"]
	if !ok {
		t.Error("Missing 'service.config.read' permission")
	} else if len(readPerm.AnyOfPermissions) != 2 {
		t.Errorf("service.config.read AnyOfPermissions length = %d, want 2", len(readPerm.AnyOfPermissions))
	}

	updatePerm, ok := policy.Permissions["service.config.update"]
	if !ok {
		t.Error("Missing 'service.config.update' permission")
	} else if len(updatePerm.AnyOfPermissions) != 1 {
		t.Errorf("service.config.update AnyOfPermissions length = %d, want 1", len(updatePerm.AnyOfPermissions))
	}

	deletePerm, ok := policy.Permissions["service.config.delete"]
	if !ok {
		t.Error("Missing 'service.config.delete' permission")
	} else if deletePerm.AnyOfPermissions[0] != "super-admin" {
		t.Errorf("service.config.delete AnyOfPermissions[0] = %q, want %q", deletePerm.AnyOfPermissions[0], "super-admin")
	}
}

func TestOperationPermission_EmptyPermissions(t *testing.T) {
	perm := OperationPermission{
		AnyOfPermissions: []string{},
	}

	data, err := json.Marshal(perm)
	if err != nil {
		t.Fatalf("Marshal error: %v", err)
	}

	var unmarshaled OperationPermission
	if err := json.Unmarshal(data, &unmarshaled); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}

	// Note: Go's JSON unmarshaling converts empty slices to nil when using omitempty
	// This is expected behavior - an empty permissions list is equivalent to nil
	if len(unmarshaled.AnyOfPermissions) != 0 {
		t.Errorf("AnyOfPermissions length = %d, want 0", len(unmarshaled.AnyOfPermissions))
	}
}

func TestOperationPermission_MultiplePermissions(t *testing.T) {
	perm := OperationPermission{
		AnyOfPermissions: []string{"admin", "team:platform", "role:owner", "service.my-service.admin"},
	}

	data, err := json.Marshal(perm)
	if err != nil {
		t.Fatalf("Marshal error: %v", err)
	}

	var unmarshaled OperationPermission
	if err := json.Unmarshal(data, &unmarshaled); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}

	if len(unmarshaled.AnyOfPermissions) != 4 {
		t.Errorf("AnyOfPermissions length = %d, want 4", len(unmarshaled.AnyOfPermissions))
	}

	expected := []string{"admin", "team:platform", "role:owner", "service.my-service.admin"}
	for i, permission := range unmarshaled.AnyOfPermissions {
		if permission != expected[i] {
			t.Errorf("AnyOfPermissions[%d] = %q, want %q", i, permission, expected[i])
		}
	}
}

func TestServicePermissionPolicy_JSONFieldNames(t *testing.T) {
	policy := ServicePermissionPolicy{
		Permissions: map[string]OperationPermission{
			"test.op": {AnyOfPermissions: []string{"perm1"}},
		},
	}

	data, err := json.Marshal(policy)
	if err != nil {
		t.Fatalf("Marshal error: %v", err)
	}

	var result map[string]interface{}
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatalf("Unmarshal to map error: %v", err)
	}

	// Verify JSON field name is "permissions" not "Permissions"
	if _, ok := result["permissions"]; !ok {
		t.Error("JSON should have 'permissions' field (lowercase)")
	}
	if _, ok := result["Permissions"]; ok {
		t.Error("JSON should not have 'Permissions' field (uppercase)")
	}
}

func TestOperationPermission_JSONFieldNames(t *testing.T) {
	perm := OperationPermission{
		AnyOfPermissions: []string{"admin"},
	}

	data, err := json.Marshal(perm)
	if err != nil {
		t.Fatalf("Marshal error: %v", err)
	}

	var result map[string]interface{}
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatalf("Unmarshal to map error: %v", err)
	}

	// Verify JSON field name is "anyOfPermissions" not "AnyOfPermissions"
	if _, ok := result["anyOfPermissions"]; !ok {
		t.Error("JSON should have 'anyOfPermissions' field (camelCase)")
	}
	if _, ok := result["AnyOfPermissions"]; ok {
		t.Error("JSON should not have 'AnyOfPermissions' field (PascalCase)")
	}
}
