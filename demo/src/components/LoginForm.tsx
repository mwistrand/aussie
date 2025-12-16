'use client';

import { useState, FormEvent } from 'react';
import { useRouter } from 'next/navigation';

interface LoginFormProps {
  redirectUrl?: string;
  errorMessage?: string;
  callbackUrl?: string;
  flow?: string;
  deviceCode?: string;
}

export default function LoginForm({ redirectUrl, errorMessage, callbackUrl, flow, deviceCode }: LoginFormProps) {
  const router = useRouter();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [isAdmin, setIsAdmin] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(errorMessage || '');

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');

    try {
      // Device code flow: authorize the pending device code
      if (flow === 'device' && deviceCode) {
        const response = await fetch('/api/auth/device', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            device_code: deviceCode,
            username,
            isAdmin,
          }),
        });

        const data = await response.json();

        if (!response.ok) {
          throw new Error(data.error || 'Device authorization failed');
        }

        // Show success message - user can close the window
        setError('');
        setIsLoading(false);
        alert('Device authorized! You can close this window and return to your CLI.');
        return;
      }

      // CLI callback flow: POST to login endpoint with callback parameter
      const loginUrl = callbackUrl
        ? `/api/auth/login?callback=${encodeURIComponent(callbackUrl)}`
        : '/api/auth/login';

      const response = await fetch(loginUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          username,
          password,
          isAdmin,
          redirect: redirectUrl || '/',
        }),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.error || 'Login failed');
      }

      // CLI callback flow: redirect to CLI's local server
      if (data.redirectTo) {
        window.location.href = data.redirectTo;
        return;
      }

      // Redirect to Aussie callback to create session
      // The callbackUrl points to Aussie's /auth/callback endpoint
      if (data.callbackUrl) {
        // For demo, we'll redirect through Aussie to create the session
        // In production, this would go to the actual Aussie gateway
        window.location.href = data.callbackUrl;
      } else {
        throw new Error('No callback URL returned');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed');
      setIsLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      {error && (
        <div className="rounded-md bg-red-50 p-4">
          <div className="flex">
            <div className="flex-shrink-0">
              <svg
                className="h-5 w-5 text-red-400"
                viewBox="0 0 20 20"
                fill="currentColor"
              >
                <path
                  fillRule="evenodd"
                  d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                  clipRule="evenodd"
                />
              </svg>
            </div>
            <div className="ml-3">
              <p className="text-sm font-medium text-red-800">{error}</p>
            </div>
          </div>
        </div>
      )}

      <div>
        <label
          htmlFor="username"
          className="block text-sm font-medium text-gray-700"
        >
          Username
        </label>
        <div className="mt-1">
          <input
            id="username"
            name="username"
            type="text"
            autoComplete="username"
            required
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="block w-full appearance-none rounded-md border border-gray-300 px-3 py-2 placeholder-gray-400 shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-indigo-500 sm:text-sm"
            placeholder="Enter any username"
          />
        </div>
      </div>

      <div>
        <label
          htmlFor="password"
          className="block text-sm font-medium text-gray-700"
        >
          Password
        </label>
        <div className="mt-1">
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="block w-full appearance-none rounded-md border border-gray-300 px-3 py-2 placeholder-gray-400 shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-indigo-500 sm:text-sm"
            placeholder="Enter any password"
          />
        </div>
        <p className="mt-1 text-xs text-gray-500">
          Demo mode: any password is accepted
        </p>
      </div>

      <div className="flex items-center">
        <input
          id="isAdmin"
          name="isAdmin"
          type="checkbox"
          checked={isAdmin}
          onChange={(e) => setIsAdmin(e.target.checked)}
          className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
        />
        <label htmlFor="isAdmin" className="ml-2 block text-sm text-gray-900">
          Admin (grants admin:read and admin:write permissions)
        </label>
      </div>

      <div>
        <button
          type="submit"
          disabled={isLoading}
          className="flex w-full justify-center rounded-md border border-transparent bg-indigo-600 py-2 px-4 text-sm font-medium text-white shadow-sm hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {isLoading ? 'Signing in...' : 'Sign in'}
        </button>
      </div>
    </form>
  );
}
