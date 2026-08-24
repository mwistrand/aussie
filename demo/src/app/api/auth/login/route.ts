import { NextRequest, NextResponse } from 'next/server';
import {
  generateToken,
  parseRedirectUrl,
  createDeviceCode,
  getDeviceCode,
  createAuthorizationCode,
  exchangeAuthorizationCode,
  USER_GROUPS,
} from '@/lib/auth';

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
 * 1. POST (browser flow): Form-based login that generates a token and callback URL
 * 2. POST with flow=device_code: Initiate device code flow for CLI
 * 3. GET with flow=device_code&device_code=xxx: Poll for device code authorization
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
          'aussie-cli',
          String(formData.get('redirect_uri') || ''),
          String(formData.get('code_verifier') || '')
        );

        if ('error' in result && result.error) {
          return NextResponse.json({ error: result.error }, { status: 400 });
        }
        if (!result.claims) {
          return NextResponse.json({ error: 'Invalid authorization code' }, { status: 400 });
        }

        const token = await generateToken(result.claims);
        return NextResponse.json({
          access_token: token,
          token_type: 'Bearer',
          expires_in: 3600,
        });
      }
    }

    // Device code flow initiation
    if (flow === 'device_code') {
      const { deviceCode, userCode, expiresIn } = createDeviceCode();
      const verificationUrl = `${request.nextUrl.origin}/login?flow=device&code=${deviceCode}`;

      return NextResponse.json({
        device_code: deviceCode,
        user_code: userCode,
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

    // Generate signed JWT token
    const claims = {
      sub: body.username.trim(),
      name: body.username.trim(),
      groups,
      permissions,
      teamId,
    };
    const token = await generateToken(claims);

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

        // Legacy browser callback flow.
        callbackUrl.searchParams.set('token', token);
        return NextResponse.json({
          success: true,
          redirectTo: callbackUrl.toString(),
        });
      } catch {
        return NextResponse.json({ error: 'Invalid callback URL' }, { status: 400 });
      }
    }

    // Build the Aussie callback URL
    // The aussie gateway will validate this token and create a session
    const aussieCallback = `/auth/callback?token=${encodeURIComponent(token)}&redirect=${encodeURIComponent(redirectUrl)}`;

    const response = NextResponse.json({
      success: true,
      callbackUrl: aussieCallback,
      token, // Include token for debugging/testing
    });
    response.cookies.delete('aussie_session');
    return response;
  } catch (error) {
    console.error('Login error:', error);
    return NextResponse.json({ error: 'Login failed' }, { status: 500 });
  }
}

// GET: Device code polling or browser login redirect
export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams;
  const flow = searchParams.get('flow');
  const deviceCode = searchParams.get('device_code');
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

  if (flow !== 'device_code' || !deviceCode) {
    return NextResponse.json(
      { error: 'Invalid request. Use POST to initiate device code flow.' },
      { status: 400 }
    );
  }

  const entry = getDeviceCode(deviceCode);

  if (!entry) {
    return NextResponse.json({ error: 'expired_token' }, { status: 400 });
  }

  switch (entry.status) {
    case 'pending':
      // Return 202 Accepted to indicate authorization is still pending
      return NextResponse.json(
        { error: 'authorization_pending' },
        { status: 202 }
      );

    case 'authorized':
      // Return the token
      return NextResponse.json({
        token: entry.token,
        token_type: 'Bearer',
      });

    case 'expired':
      return NextResponse.json({ error: 'expired_token' }, { status: 400 });

    default:
      return NextResponse.json({ error: 'unknown_error' }, { status: 500 });
  }
}
