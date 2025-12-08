import { NextRequest, NextResponse } from "next/server";

/**
 * GET /api/auth/test - Auth testing endpoint
 *
 * This endpoint helps test API key authentication by:
 * 1. Echoing back the authentication headers received
 * 2. Calling the Aussie API to validate the provided key
 *
 * When accessed through the Aussie proxy with a Bearer token,
 * the API key will be validated before reaching this endpoint.
 */
export async function GET(request: NextRequest) {
  const authHeader = request.headers.get("authorization");
  const forwardedFor = request.headers.get("x-forwarded-for");
  const aussieKeyId = request.headers.get("x-aussie-key-id");
  const aussieKeyName = request.headers.get("x-aussie-key-name");

  // If we have an auth header, try to validate it against the Aussie API
  let validationResult = null;
  if (authHeader) {
    try {
      const aussieHost = process.env.AUSSIE_HOST || "http://localhost:8080";
      const response = await fetch(`${aussieHost}/admin/whoami`, {
        headers: {
          Authorization: authHeader,
        },
      });

      if (response.ok) {
        validationResult = await response.json();
      } else {
        validationResult = {
          error: `Validation failed: ${response.status} ${response.statusText}`,
          status: response.status,
        };
      }
    } catch (error) {
      validationResult = {
        error: `Failed to connect to Aussie API: ${error instanceof Error ? error.message : "Unknown error"}`,
      };
    }
  }

  return NextResponse.json({
    message: "Auth test endpoint",
    timestamp: new Date().toISOString(),
    headers: {
      authorization: authHeader ? `${authHeader.substring(0, 20)}...` : null,
      forwardedFor,
      aussieKeyId,
      aussieKeyName,
    },
    validation: validationResult,
    note: authHeader
      ? "Bearer token detected - validation attempted"
      : "No Authorization header - using noop fallback in dev mode",
  });
}

/**
 * POST /api/auth/test - Test auth with custom key
 *
 * Accepts a JSON body with an API key and validates it.
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { apiKey } = body;

    if (!apiKey) {
      return NextResponse.json(
        { error: "apiKey is required in request body" },
        { status: 400 }
      );
    }

    const aussieHost = process.env.AUSSIE_HOST || "http://localhost:8080";
    const response = await fetch(`${aussieHost}/admin/whoami`, {
      headers: {
        Authorization: `Bearer ${apiKey}`,
      },
    });

    if (response.ok) {
      const result = await response.json();
      return NextResponse.json({
        valid: true,
        identity: result,
      });
    } else {
      const errorText = await response.text();
      return NextResponse.json({
        valid: false,
        status: response.status,
        error: errorText || response.statusText,
      });
    }
  } catch (error) {
    return NextResponse.json(
      {
        error: `Validation failed: ${error instanceof Error ? error.message : "Unknown error"}`,
      },
      { status: 500 }
    );
  }
}
