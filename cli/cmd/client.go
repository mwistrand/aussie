package cmd

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/aussie/cli/internal/api"
	"github.com/aussie/cli/internal/auth"
	"github.com/aussie/cli/internal/config"
)

func newAuthenticatedAPIClient(cmd *cobra.Command) (*api.Client, error) {
	cfg, err := loadConfigForServer(cmd)
	if err != nil {
		return nil, err
	}
	token, err := auth.GetAuthTokenForHost(cfg.ApiKey, cfg.Host)
	if err != nil {
		return nil, err
	}
	return api.New(cfg.Host, token)
}

func loadConfigForServer(cmd *cobra.Command) (*config.Config, error) {
	cfg, err := config.Load()
	if err != nil {
		return nil, fmt.Errorf("failed to load config: %w", err)
	}
	if serverFlag, _ := cmd.Flags().GetString("server"); serverFlag != "" {
		cfg.Host = serverFlag
	}
	return cfg, nil
}
