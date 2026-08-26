package cmd

import (
	"fmt"
	"net/http"
	"time"

	"github.com/spf13/cobra"
)

var authKeysRotateReason string

var authKeysRotateCmd = &cobra.Command{
	Use:   "rotate",
	Short: "Trigger immediate key rotation",
	Long: `Trigger an immediate key rotation.

This command generates a new signing key and immediately activates it,
deprecating the current active key. Use this for emergency situations
like suspected key compromise.

For routine key rotation, keys are automatically rotated on a configured schedule.

Examples:
  aussie auth keys rotate --reason "Quarterly rotation"
  aussie auth keys rotate --reason "Key compromise suspected"`,
	RunE: runAuthKeysRotate,
}

func init() {
	authKeysCmd.AddCommand(authKeysRotateCmd)
	authKeysRotateCmd.Flags().StringVar(&authKeysRotateReason, "reason", "", "Reason for key rotation (required)")
	authKeysRotateCmd.MarkFlagRequired("reason")
}

// rotateRequest is the request body for key rotation.
type rotateRequest struct {
	Reason string `json:"reason"`
}

// rotateResponse is the response from a key rotation request.
type rotateResponse struct {
	KeyId       string `json:"keyId"`
	Status      string `json:"status"`
	CreatedAt   string `json:"createdAt"`
	ActivatedAt string `json:"activatedAt"`
	PreviousKey string `json:"previousKeyId,omitempty"`
}

func runAuthKeysRotate(cmd *cobra.Command, args []string) error {
	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}
	client.SetTimeout(time.Minute)

	reqBody := rotateRequest{Reason: authKeysRotateReason}
	resp, err := client.DoJSON(http.MethodPost, "/admin/keys/rotate", reqBody)
	if err != nil {
		return err
	}

	switch resp.StatusCode {
	case http.StatusOK:
		// Continue
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to rotate signing keys (requires keys.rotate)")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("key rotation is not enabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}

	var result rotateResponse
	if err := resp.DecodeJSON(&result); err != nil {
		return fmt.Errorf("failed to parse response: %w", err)
	}

	fmt.Println("Key rotation completed successfully.")
	fmt.Println()
	fmt.Printf("New Key ID:     %s\n", result.KeyId)
	fmt.Printf("Status:         %s\n", result.Status)
	fmt.Printf("Created:        %s\n", formatTimestamp(result.CreatedAt))
	fmt.Printf("Activated:      %s\n", formatTimestamp(result.ActivatedAt))
	if result.PreviousKey != "" {
		fmt.Printf("Previous Key:   %s (now DEPRECATED)\n", result.PreviousKey)
	}

	return nil
}
