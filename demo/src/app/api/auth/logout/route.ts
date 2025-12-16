import { NextRequest, NextResponse } from 'next/server';
import { verifyToken, addToBlacklist } from '@/lib/auth';

/**
 * Logout endpoint for the demo app.
 *
 * This endpoint invalidates the user's token by adding it to a blacklist.
 * In production, this would also invalidate sessions in the IdP.
 *
 * The token is extracted from the Authorization header.
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

    // Verify the token to get its expiry
    const claims = await verifyToken(token);

    if (!claims) {
      // Token is already invalid, but we'll return success anyway
      return NextResponse.json({
        success: true,
        message: 'Token already invalid or expired',
      });
    }

    // Add token to blacklist until it naturally expires
    addToBlacklist(token, claims.exp);

    return NextResponse.json({
      success: true,
      message: 'Logged out successfully',
    });
  } catch (error) {
    console.error('Logout error:', error);
    return NextResponse.json(
      { error: 'Logout failed' },
      { status: 500 }
    );
  }
}
