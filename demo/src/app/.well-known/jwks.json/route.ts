import { NextResponse } from 'next/server';
import { getJwks } from '@/lib/auth';

/**
 * JWKS endpoint for demo app token validation.
 *
 * Aussie uses this endpoint to fetch the public keys needed to
 * validate JWT tokens issued by the demo app login flow.
 */
export async function GET() {
  const jwks = await getJwks();

  return NextResponse.json(jwks, {
    headers: {
      'Cache-Control': 'public, max-age=3600', // Cache for 1 hour
    },
  });
}
