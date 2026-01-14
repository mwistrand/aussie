// Latency test endpoint - Returns 204 No Content with minimal processing.
// Used by 'aussie benchmark' to measure gateway latency distribution.
// This endpoint requires authentication to test the full Aussie auth flow.

export async function GET() {
  return new Response(null, { status: 204 });
}

export async function POST() {
  return new Response(null, { status: 204 });
}
