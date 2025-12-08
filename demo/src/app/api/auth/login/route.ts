import { NextRequest, NextResponse } from 'next/server';
import { generateToken, parseRedirectUrl } from '@/lib/auth';

export interface LoginRequest {
  username: string;
  password: string;
  isAdmin: boolean;
  redirect?: string;
}

/**
 * Mock login endpoint for demo purposes.
 *
 * This endpoint accepts any username/password combination and generates
 * a signed JWT token that can be used to create an Aussie session.
 *
 * The "isAdmin" flag adds admin permissions to the token.
 */
export async function POST(request: NextRequest) {
  try {
    const body: LoginRequest = await request.json();

    // Validate required fields
    if (!body.username || body.username.trim() === '') {
      return NextResponse.json(
        { error: 'Username is required' },
        { status: 400 }
      );
    }

    if (!body.password || body.password.trim() === '') {
      return NextResponse.json(
        { error: 'Password is required' },
        { status: 400 }
      );
    }

    // For demo purposes, accept any password
    // In production, this would validate against an identity provider

    // Build permissions based on admin checkbox
    const permissions: string[] = [];
    if (body.isAdmin) {
      permissions.push('admin:read', 'admin:write');
    }

    // Generate signed JWT token
    const token = await generateToken({
      sub: body.username.trim(),
      name: body.username.trim(),
      permissions,
    });

    // Parse and validate redirect URL
    const redirectUrl = parseRedirectUrl(body.redirect || null);

    // Build the Aussie callback URL
    // The aussie gateway will validate this token and create a session
    const aussieCallback = `/auth/callback?token=${encodeURIComponent(token)}&redirect=${encodeURIComponent(redirectUrl)}`;

    return NextResponse.json({
      success: true,
      callbackUrl: aussieCallback,
      token, // Include token for debugging/testing
    });
  } catch (error) {
    console.error('Login error:', error);
    return NextResponse.json(
      { error: 'Login failed' },
      { status: 500 }
    );
  }
}
