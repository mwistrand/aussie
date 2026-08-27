import { NextRequest, NextResponse } from 'next/server';
import {
  generateToken,
  parseRedirectUrl,
  createDeviceCode,
  getDeviceCode,
  consumeDeviceCode,
  createAuthorizationCode,
  exchangeAuthorizationCode,
  USER_GROUPS,
} from '@/lib/auth';

const DEVICE_CLIENT_ID = 'aussie-cli';
const DEVICE_GRANT_TYPE = 'urn:ietf:params:oauth:grant-type:device_code';

function oauthResponse(body: Record<string, unknown>, status = 200) {
  return NextResponse.json(body, {
    status,
    headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
  });
}

export interface LoginRequest {
  username: string;
  group?: string;
  redirect?: string;
}

/**
 * Mock login endpoint for demo purposes.
 *
 * This endpoint supports multiple authentication flows:
 *
 * 1. POST (browser flow): Form-based login that generates a token for session creation
 * 2. POST with flow=device_code: Initiate device code flow for CLI
 * 3. POST with the device authorization grant: Poll for device code authorization
 *
 * The "isAdmin" flag adds admin groups to the token.
 * Users can also select specific groups to include.
 */

// POST: Browser login or device code initiation
export async function POST(request: NextRequest) {
  try {
    const contentType = request.headers.get('content-type') || '';
    const searchParams = request.nextUrl.searchParams;
    const flow = searchParams.get('flow');
    let formData: FormData | undefined;

    // OAuth authorization-code exchange for the CLI browser flow.
    if (contentType.includes('application/x-www-form-urlencoded')) {
      formData = await request.formData();
      if (formData.get('grant_type') === 'authorization_code') {
        const result = exchangeAuthorizationCode(
          String(formData.get('code') || ''),
          DEVICE_CLIENT_ID,
          String(formData.get('redirect_uri') || ''),
          String(formData.get('code_verifier') || '')
        );

        if ('error' in result && result.error) {
          return oauthResponse({ error: result.error }, 400);
        }
        if (!result.claims) {
          return oauthResponse({ error: 'invalid_grant' }, 400);
        }

        const token = await generateToken(result.claims);
        return oauthResponse({
          access_token: token,
          token_type: 'Bearer',
          expires_in: 3600,
        });
      }

      if (formData.get('grant_type') === DEVICE_GRANT_TYPE) {
        const deviceCode = String(formData.get('device_code') || '');
        const clientId = String(formData.get('client_id') || '');
        if (!deviceCode || !clientId) {
          return oauthResponse({ error: 'invalid_request' }, 400);
        }
        const entry = getDeviceCode(deviceCode);
        if (!entry) {
          return oauthResponse({ error: 'expired_token' }, 400);
        }
        if (entry.clientId !== clientId || clientId !== DEVICE_CLIENT_ID) {
          return oauthResponse({ error: 'invalid_grant' }, 400);
        }
        switch (entry.status) {
          case 'pending':
            return oauthResponse({ error: 'authorization_pending' }, 400);
          case 'authorized': {
            const token = consumeDeviceCode(deviceCode, clientId);
            if (!token) {
              return oauthResponse({ error: 'invalid_grant' }, 400);
            }
            return oauthResponse({
              access_token: token,
              token_type: 'Bearer',
            });
          }
          case 'expired':
            return oauthResponse({ error: 'expired_token' }, 400);
          default:
            return oauthResponse({ error: 'invalid_grant' }, 400);
        }
      }
    }

    // Device code flow initiation
    if (flow === 'device_code') {
      const clientId = String(formData?.get('client_id') || '');
      if (clientId !== DEVICE_CLIENT_ID) {
        return oauthResponse({ error: 'invalid_client' }, 400);
      }
      const { deviceCode, userCode, expiresIn } = createDeviceCode(clientId);
      const verificationUrl = `${request.nextUrl.origin}/login?flow=device`;

      return oauthResponse({
        device_code: deviceCode,
        user_code: userCode,
        verification_uri: verificationUrl,
        verification_uri_complete: `${verificationUrl}&code=${encodeURIComponent(userCode)}`,
        verification_url: verificationUrl,
        expires_in: expiresIn,
        interval: 5,
      });
    }

    // Standard browser login flow
    let body: LoginRequest;

    if (contentType.includes('application/json')) {
      body = await request.json();
    } else {
      // Handle form data
      formData ??= await request.formData();
      body = {
        username: formData.get('username') as string,
        group: formData.get('group') as string | undefined,
        redirect: formData.get('redirect') as string | undefined,
      };
    }

    // Validate required fields
    if (!body.username || body.username.trim() === '') {
      return NextResponse.json({ error: 'Username is required' }, { status: 400 });
    }

    // Demo mode: username and password are derived from the selected role
    // In production, this would validate against an identity provider

    // Determine groups based on selected group
    const username = body.username.trim().toLowerCase();
    let groups: string[] = [];

    // Check for predefined user groups (username-based defaults)
    if (USER_GROUPS[username]) {
      groups = [...USER_GROUPS[username]];
    }

    // Add the selected group from the form
    if (body.group) {
      groups = [...new Set([...groups, body.group])];
    }

    // No permissions are added directly - they come from group expansion
    const permissions: string[] = body.group === 'admin' ? ['*'] : [];

    // Derive teamId from group prefix (e.g., "demo-service.admin" -> "demo-service")
    const teamId = groups
      .map((g) => g.split('.')[0])
      .find((prefix) => prefix !== 'platform-team') || undefined;

    const claims = {
      sub: body.username.trim(),
      name: body.username.trim(),
      groups,
      permissions,
      teamId,
    };
    // Parse and validate redirect URL
    const redirectUrl = parseRedirectUrl(body.redirect || null);

    // Check for callback parameter (CLI callback flow)
    const callback = searchParams.get('callback');
    if (callback) {
      try {
        const callbackUrl = new URL(callback);

        if (callbackUrl.searchParams.get('response_type') === 'code') {
          const redirectUri = callbackUrl.searchParams.get('redirect_uri');
          const state = callbackUrl.searchParams.get('state');
          const codeChallenge = callbackUrl.searchParams.get('code_challenge');
          const codeChallengeMethod = callbackUrl.searchParams.get('code_challenge_method');

          if (!redirectUri || !state || !codeChallenge || codeChallengeMethod !== 'S256') {
            return NextResponse.json({ error: 'Invalid OAuth request' }, { status: 400 });
          }

          const code = createAuthorizationCode({
            clientId: 'aussie-cli',
            redirectUri,
            codeChallenge,
            codeChallengeMethod,
            claims,
            state,
          });
          const redirect = new URL(redirectUri);
          redirect.searchParams.set('code', code);
          redirect.searchParams.set('state', state);
          return NextResponse.json({ success: true, redirectTo: redirect.toString() });
        }

        return NextResponse.json({ error: 'Invalid OAuth request' }, { status: 400 });
      } catch {
        return NextResponse.json({ error: 'Invalid callback URL' }, { status: 400 });
      }
    }

    return NextResponse.json({
      success: true,
      token: await generateToken(claims),
      redirectUrl,
    });
  } catch (error) {
    console.error('Login error:', error);
    return NextResponse.json({ error: 'Login failed' }, { status: 500 });
  }
}

// GET: Browser login redirect. Device token polling uses the POST grant above.
export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams;
  const callback = searchParams.get('callback');

  // Browser login flow: redirect to login page with callback
  if (callback) {
    // Use the Host header to get the correct external hostname (not Docker container hostname)
    const host = request.headers.get('host') || 'localhost:3000';
    const protocol = request.headers.get('x-forwarded-proto') || 'http';
    const loginPageUrl = new URL('/login', `${protocol}://${host}`);
    const callbackUrl = new URL(callback);
    for (const parameter of [
      'redirect_uri',
      'response_type',
      'state',
      'code_challenge',
      'code_challenge_method',
    ]) {
      const value = searchParams.get(parameter);
      if (value) callbackUrl.searchParams.set(parameter, value);
    }
    loginPageUrl.searchParams.set('callback', callbackUrl.toString());
    return NextResponse.redirect(loginPageUrl.toString());
  }

  return NextResponse.json(
    { error: 'Invalid request. Use POST with the device authorization grant.' },
    { status: 400 }
  );
}
