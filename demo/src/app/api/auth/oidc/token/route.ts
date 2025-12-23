import { NextRequest, NextResponse } from 'next/server';
import { exchangeAuthorizationCode, generateToken } from '@/lib/auth';

// CORS headers for cross-origin requests from demo-ui
const corsHeaders = {
  'Access-Control-Allow-Origin': 'http://localhost:8080',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
};

/**
 * Create a JSON response with CORS headers.
 */
function jsonResponse(
  data: Record<string, unknown>,
  status: number = 200
): NextResponse {
  return NextResponse.json(data, { status, headers: corsHeaders });
}

/**
 * Handle CORS preflight requests.
 */
export async function OPTIONS() {
  return new NextResponse(null, { status: 204, headers: corsHeaders });
}

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
      return jsonResponse(
        {
          error: 'invalid_request',
          error_description:
            'Content-Type must be application/x-www-form-urlencoded or application/json',
        },
        400
      );
    }

    // Validate grant type
    if (grantType !== 'authorization_code') {
      return jsonResponse(
        {
          error: 'unsupported_grant_type',
          error_description: 'Only authorization_code grant type is supported',
        },
        400
      );
    }

    // Validate required parameters
    if (!code) {
      return jsonResponse(
        {
          error: 'invalid_request',
          error_description: 'code is required',
        },
        400
      );
    }

    if (!clientId) {
      return jsonResponse(
        {
          error: 'invalid_request',
          error_description: 'client_id is required',
        },
        400
      );
    }

    if (!redirectUri) {
      return jsonResponse(
        {
          error: 'invalid_request',
          error_description: 'redirect_uri is required',
        },
        400
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
      return jsonResponse(
        {
          error: errorCode,
          error_description: errorDesc || errorCode,
        },
        400
      );
    }

    // Generate tokens - TypeScript now knows result has claims
    const { claims } = result as { claims: Parameters<typeof generateToken>[0] };
    const expiresIn = 3600; // 1 hour
    const accessToken = await generateToken(claims, expiresIn);

    // For demo purposes, id_token is the same as access_token
    // In production, these would have different claims/audiences
    const idToken = accessToken;

    return jsonResponse({
      access_token: accessToken,
      token_type: 'Bearer',
      expires_in: expiresIn,
      id_token: idToken,
    });
  } catch (error) {
    console.error('Token endpoint error:', error);
    return jsonResponse(
      {
        error: 'server_error',
        error_description: 'An unexpected error occurred',
      },
      500
    );
  }
}
