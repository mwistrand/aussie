package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/spf13/cobra"
)

var revokeListCmd = &cobra.Command{
	Use:   "list",
	Short: "List currently revoked tokens",
	Long: `List all currently revoked tokens and users.

This command is useful for debugging and auditing revocation state.
Note: Large revocation lists may be truncated.

Examples:
  aussie auth revoke list
  aussie auth revoke list --limit 50
  aussie auth revoke list --users          # List users with blanket revocations
  aussie auth revoke list --format json`,
	RunE: runRevokeList,
}

var revokeListFormat string
var revokeListLimit int
var revokeListUsers bool

func init() {
	revokeCmd.AddCommand(revokeListCmd)
	revokeListCmd.Flags().StringVar(&revokeListFormat, "format", "table", "Output format (table|json)")
	revokeListCmd.Flags().IntVar(&revokeListLimit, "limit", 100, "Maximum number of entries to return")
	revokeListCmd.Flags().BoolVar(&revokeListUsers, "users", false, "List users with blanket revocations instead of tokens")
}

func runRevokeList(cmd *cobra.Command, args []string) error {
	client, err := newAuthenticatedAPIClient(cmd)
	if err != nil {
		return err
	}

	var path string
	if revokeListUsers {
		path = fmt.Sprintf("/admin/tokens/users?limit=%d", revokeListLimit)
	} else {
		path = fmt.Sprintf("/admin/tokens?limit=%d", revokeListLimit)
	}

	resp, err := client.DoJSON(http.MethodGet, path, nil)
	if err != nil {
		return err
	}

	switch resp.StatusCode {
	case http.StatusOK:
		if revokeListFormat == "json" {
			var result any
			if revokeListUsers {
				result = new(UserRevocationList)
			} else {
				result = new(TokenRevocationList)
			}
			if err := resp.DecodeJSON(result); err != nil {
				return fmt.Errorf("failed to parse response: %w", err)
			}
			output, _ := json.MarshalIndent(result, "", "  ")
			fmt.Println(string(output))
		} else {
			if revokeListUsers {
				var result UserRevocationList
				if err := resp.DecodeJSON(&result); err != nil {
					return fmt.Errorf("failed to parse response: %w", err)
				}
				printUserRevocations(result.RevokedUsers, result.Count, result.Limit)
			} else {
				var result TokenRevocationList
				if err := resp.DecodeJSON(&result); err != nil {
					return fmt.Errorf("failed to parse response: %w", err)
				}
				printTokenRevocations(result.RevokedTokens, result.Count, result.Limit)
			}
		}
		return nil
	case http.StatusUnauthorized:
		return fmt.Errorf("authentication failed. Run 'aussie login' to re-authenticate")
	case http.StatusForbidden:
		return fmt.Errorf("insufficient permissions to list revocations")
	case http.StatusServiceUnavailable:
		return fmt.Errorf("token revocation is disabled on this server")
	default:
		return fmt.Errorf("unexpected response: %s", resp.Status)
	}
}

func printTokenRevocations(tokens []string, count, limit int) {
	if tokens == nil {
		fmt.Println("No revoked tokens found.")
		return
	}

	fmt.Printf("Revoked Tokens (%d shown, limit %d):\n", count, limit)
	fmt.Println("----------------------------------------")

	if len(tokens) == 0 {
		fmt.Println("  (none)")
		return
	}

	for _, t := range tokens {
		fmt.Printf("  • %s\n", t)
	}

	if count >= limit {
		fmt.Printf("\n(Results may be truncated. Use --limit to see more.)\n")
	}
}

func printUserRevocations(users []string, count, limit int) {
	if users == nil {
		fmt.Println("No users with blanket revocations found.")
		return
	}

	fmt.Printf("Users with Blanket Revocations (%d shown, limit %d):\n", count, limit)
	fmt.Println("--------------------------------------------------")

	if len(users) == 0 {
		fmt.Println("  (none)")
		return
	}

	for _, u := range users {
		fmt.Printf("  • %s\n", u)
	}

	if count >= limit {
		fmt.Printf("\n(Results may be truncated. Use --limit to see more.)\n")
	}
}
