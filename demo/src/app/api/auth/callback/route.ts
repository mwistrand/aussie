import { NextRequest, NextResponse } from 'next/server';
import { parseRedirectUrl } from '@/lib/auth';

/**
 * Auth callback endpoint for the demo app.
 *
 * This endpoint receives the token after Aussie has created a session
 * and redirects the user to their original destination.
 *
 * Flow:
 * 1. User submits login form
 * 2. /api/auth/login generates token and returns Aussie callback URL
 * 3. Client redirects to Aussie /auth/callback (with token)
 * 4. Aussie validates token, creates session, sets cookie
 * 5. Aussie redirects back to this endpoint (or original destination)
 * 6. This endpoint redirects to the final destination
 *
 * Note: In the current implementation, Aussie's /auth/callback may
 * redirect directly to the final destination. This endpoint handles
 * cases where the demo app needs to do additional processing.
 */
export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams;
  const redirect = searchParams.get('redirect');
  const error = searchParams.get('error');

  // Check for errors from Aussie
  if (error) {
    const errorMessage = searchParams.get('message') || 'Authentication failed';
    return NextResponse.redirect(
      new URL(`/login?error=${encodeURIComponent(errorMessage)}`, request.url)
    );
  }

  // Validate and redirect to final destination
  const finalRedirect = parseRedirectUrl(redirect);

  return NextResponse.redirect(new URL(finalRedirect, request.url));
}
