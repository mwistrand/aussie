import { NextRequest, NextResponse } from 'next/server';

/**
 * Mock endpoint that always returns 401 Unauthorized.
 *
 * This endpoint is used for testing the auth rate limiter (brute force protection).
 * Each call simulates a failed authentication attempt, which should be tracked
 * by Aussie's AuthRateLimitService and eventually result in a 429 lockout.
 *
 * Use this to verify:
 * - Failed attempts are tracked correctly
 * - Lockout triggers after max attempts (default: 5)
 * - 429 response includes Retry-After header
 * - Progressive lockout escalation works
 */
export async function POST(request: NextRequest) {
  // Extract identifier for logging/debugging
  const contentType = request.headers.get('content-type') || '';
  let username = 'unknown';

  try {
    if (contentType.includes('application/json')) {
      const body = await request.json();
      username = body.username || 'unknown';
    }
  } catch {
    // Ignore parse errors
  }

  // Always return 401 to simulate failed authentication
  return NextResponse.json(
    {
      error: 'Invalid credentials',
      message: 'Authentication failed. This endpoint always fails for testing purposes.',
      username,
      timestamp: new Date().toISOString(),
    },
    { status: 401 }
  );
}

// Also support GET for simple testing
export async function GET() {
  return NextResponse.json(
    {
      error: 'Invalid credentials',
      message: 'Authentication failed. This endpoint always fails for testing purposes.',
      timestamp: new Date().toISOString(),
    },
    { status: 401 }
  );
}
