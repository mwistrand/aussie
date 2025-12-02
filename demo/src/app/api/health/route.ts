import { NextResponse } from "next/server";

// GET /api/health - PUBLIC endpoint for health checks
export async function GET() {
  return NextResponse.json({
    status: "healthy",
    timestamp: new Date().toISOString(),
    service: "demo-service",
  });
}
