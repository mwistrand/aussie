import { NextResponse } from "next/server";

// GET /api/admin/stats - PRIVATE endpoint (should only be accessible from internal IPs)
export async function GET(request: Request) {
  // Log the forwarded headers for debugging
  const forwardedFor = request.headers.get("x-forwarded-for");
  const forwarded = request.headers.get("forwarded");

  return NextResponse.json({
    stats: {
      totalUsers: 3,
      activeUsers: 2,
      totalRequests: 1542,
      avgResponseTime: "45ms",
      uptime: "99.9%",
    },
    serverInfo: {
      version: "1.0.0",
      environment: process.env.NODE_ENV || "development",
      timestamp: new Date().toISOString(),
    },
    requestInfo: {
      forwardedFor,
      forwarded,
    },
  });
}

// POST /api/admin/stats/reset - PRIVATE endpoint
export async function POST() {
  // Simulate resetting stats
  return NextResponse.json({
    message: "Stats have been reset",
    resetAt: new Date().toISOString(),
  });
}
