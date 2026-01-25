// Rate limit test endpoint - Returns 200 OK with minimal processing.
// Used by the testing dashboard to test rate limiting behavior.
// This endpoint has its own rate limit config (100 req/min, burst 50).

export async function GET() {
  return new Response(JSON.stringify({ status: "ok" }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
