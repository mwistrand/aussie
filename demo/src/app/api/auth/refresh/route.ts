import { NextRequest, NextResponse } from 'next/server';
import { verifyToken, generateToken, isBlacklisted, addToBlacklist } from '@/lib/auth';

/**
 * Token refresh endpoint for the demo app.
 *
 * This endpoint accepts a valid (non-expired) token and issues a new one
 * with a fresh expiry. The old token is blacklisted to prevent reuse.
 *
 * In production, you might want to implement refresh tokens separately
 * or limit how many times a token can be refreshed.
 */
export async function POST(request: NextRequest) {
  try {
    const authHeader = request.headers.get('authorization');

    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return NextResponse.json(
        { error: 'No token provided' },
        { status: 401 }
      );
    }

    const token = authHeader.substring(7);

    // Check if token is blacklisted
    if (isBlacklisted(token)) {
      return NextResponse.json(
        { error: 'Token has been revoked' },
        { status: 401 }
      );
    }

    // Verify the token
    const claims = await verifyToken(token);

    if (!claims) {
      return NextResponse.json(
        { error: 'Invalid or expired token' },
        { status: 401 }
      );
    }

    // Check if token is about to expire (within 5 minutes of expiry)
    const now = Math.floor(Date.now() / 1000);
    const timeRemaining = claims.exp - now;

    // Only allow refresh if token is valid but has less than 30 minutes remaining
    // or if explicitly requested
    const body = await request.json().catch(() => ({}));
    const forceRefresh = body.force === true;

    if (timeRemaining > 1800 && !forceRefresh) {
      return NextResponse.json(
        {
          error: 'Token is not eligible for refresh yet',
          message: `Token has ${Math.floor(timeRemaining / 60)} minutes remaining. Use force=true to override.`,
          expires_in: timeRemaining,
        },
        { status: 400 }
      );
    }

    // Blacklist the old token
    addToBlacklist(token, claims.exp);

    // Issue a new token with the same claims
    const newToken = await generateToken({
      sub: claims.sub,
      name: claims.name,
      email: claims.email,
      groups: claims.groups,
      permissions: claims.permissions,
    });

    return NextResponse.json({
      token: newToken,
      token_type: 'Bearer',
      message: 'Token refreshed successfully',
    });
  } catch (error) {
    console.error('Refresh error:', error);
    return NextResponse.json(
      { error: 'Token refresh failed' },
      { status: 500 }
    );
  }
}
