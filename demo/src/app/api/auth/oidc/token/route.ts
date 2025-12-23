import { NextRequest, NextResponse } from 'next/server';
import { exchangeAuthorizationCode, generateToken } from '@/lib/auth';

/**
 * OIDC Token Endpoint (Demo IdP)
 *
 * This endpoint exchanges authorization codes for tokens.
 * It supports PKCE verification when code_verifier is provided.
 *
 * Request Body (application/x-www-form-urlencoded):
 * - grant_type: Must be "authorization_code"
 * - code: The authorization code from the authorize flow
 * - redirect_uri: Must match the original authorization request
 * - client_id: The client identifier
 * - code_verifier: PKCE verifier (required if code_challenge was used)
 *
 * Response:
 * - access_token: JWT token
 * - token_type: "Bearer"
 * - expires_in: Token lifetime in seconds
 * - id_token: OpenID Connect ID token (same as access_token for demo)
 */
export async function POST(request: NextRequest) {
  try {
    const contentType = request.headers.get('content-type') || '';

    let grantType: string | null = null;
    let code: string | null = null;
    let redirectUri: string | null = null;
    let clientId: string | null = null;
    let codeVerifier: string | null = null;

    // Parse request body
    if (contentType.includes('application/x-www-form-urlencoded')) {
      const formData = await request.formData();
      grantType = formData.get('grant_type') as string | null;
      code = formData.get('code') as string | null;
      redirectUri = formData.get('redirect_uri') as string | null;
      clientId = formData.get('client_id') as string | null;
      codeVerifier = formData.get('code_verifier') as string | null;
    } else if (contentType.includes('application/json')) {
      const body = await request.json();
      grantType = body.grant_type;
      code = body.code;
      redirectUri = body.redirect_uri;
      clientId = body.client_id;
      codeVerifier = body.code_verifier;
    } else {
      return NextResponse.json(
        {
          error: 'invalid_request',
          error_description:
            'Content-Type must be application/x-www-form-urlencoded or application/json',
        },
        { status: 400 }
      );
    }

    // Validate grant type
    if (grantType !== 'authorization_code') {
      return NextResponse.json(
        {
          error: 'unsupported_grant_type',
          error_description: 'Only authorization_code grant type is supported',
        },
        { status: 400 }
      );
    }

    // Validate required parameters
    if (!code) {
      return NextResponse.json(
        {
          error: 'invalid_request',
          error_description: 'code is required',
        },
        { status: 400 }
      );
    }

    if (!clientId) {
      return NextResponse.json(
        {
          error: 'invalid_request',
          error_description: 'client_id is required',
        },
        { status: 400 }
      );
    }

    if (!redirectUri) {
      return NextResponse.json(
        {
          error: 'invalid_request',
          error_description: 'redirect_uri is required',
        },
        { status: 400 }
      );
    }

    // Exchange authorization code for claims
    const result = exchangeAuthorizationCode(
      code,
      clientId,
      redirectUri,
      codeVerifier || undefined
    );

    if ('error' in result && result.error) {
      const [errorCode, errorDesc] = result.error.split(': ');
      return NextResponse.json(
        {
          error: errorCode,
          error_description: errorDesc || errorCode,
        },
        { status: 400 }
      );
    }

    // Generate tokens - TypeScript now knows result has claims
    const { claims } = result as { claims: Parameters<typeof generateToken>[0] };
    const expiresIn = 3600; // 1 hour
    const accessToken = await generateToken(claims, expiresIn);

    // For demo purposes, id_token is the same as access_token
    // In production, these would have different claims/audiences
    const idToken = accessToken;

    return NextResponse.json({
      access_token: accessToken,
      token_type: 'Bearer',
      expires_in: expiresIn,
      id_token: idToken,
    });
  } catch (error) {
    console.error('Token endpoint error:', error);
    return NextResponse.json(
      {
        error: 'server_error',
        error_description: 'An unexpected error occurred',
      },
      { status: 500 }
    );
  }
}
