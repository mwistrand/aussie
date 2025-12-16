import { NextRequest, NextResponse } from 'next/server';
import {
  getDeviceCode,
  authorizeDeviceCode,
  findDeviceCodeByUserCode,
  USER_GROUPS,
} from '@/lib/auth';

export interface DeviceAuthRequest {
  device_code?: string;
  user_code?: string;
  username: string;
  group?: string;
}

/**
 * Device code authorization endpoint.
 *
 * This endpoint is called from the browser after the user has authenticated.
 * It authorizes the pending device code with the user's claims.
 *
 * The CLI will poll /api/auth/login?flow=device_code&device_code=xxx to get
 * the token once this endpoint has been called.
 */
export async function POST(request: NextRequest) {
  try {
    const body: DeviceAuthRequest = await request.json();

    // Find the device code entry
    let deviceCode: string | undefined;

    if (body.device_code) {
      deviceCode = body.device_code;
    } else if (body.user_code) {
      const found = findDeviceCodeByUserCode(body.user_code);
      if (found) {
        deviceCode = found.deviceCode;
      }
    }

    if (!deviceCode) {
      return NextResponse.json(
        { error: 'Device code or user code is required' },
        { status: 400 }
      );
    }

    const entry = getDeviceCode(deviceCode);

    if (!entry) {
      return NextResponse.json(
        { error: 'Device code not found or expired' },
        { status: 404 }
      );
    }

    if (entry.status !== 'pending') {
      return NextResponse.json(
        { error: `Device code already ${entry.status}` },
        { status: 400 }
      );
    }

    // Validate username
    if (!body.username || body.username.trim() === '') {
      return NextResponse.json(
        { error: 'Username is required' },
        { status: 400 }
      );
    }

    // Build user claims
    const username = body.username.trim().toLowerCase();
    let groups: string[] = [];

    // Check for predefined user groups (username-based defaults)
    if (USER_GROUPS[username]) {
      groups = [...USER_GROUPS[username]];
    }

    // Add the selected group from the form
    if (body.group) {
      groups = [...new Set([...groups, body.group])];
    }

    // No permissions are added directly - they come from group expansion
    const permissions: string[] = [];

    // Authorize the device code
    const success = await authorizeDeviceCode(deviceCode, {
      sub: body.username.trim(),
      name: body.username.trim(),
      groups,
      permissions,
    });

    if (!success) {
      return NextResponse.json(
        { error: 'Failed to authorize device code' },
        { status: 500 }
      );
    }

    return NextResponse.json({
      success: true,
      message: 'Device authorized. You may close this window and return to your CLI.',
    });
  } catch (error) {
    console.error('Device authorization error:', error);
    return NextResponse.json(
      { error: 'Device authorization failed' },
      { status: 500 }
    );
  }
}

/**
 * GET endpoint to check device code status.
 */
export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams;
  const deviceCode = searchParams.get('device_code');
  const userCode = searchParams.get('user_code');

  let entry;

  if (deviceCode) {
    entry = getDeviceCode(deviceCode);
  } else if (userCode) {
    const found = findDeviceCodeByUserCode(userCode);
    entry = found?.entry;
  }

  if (!entry) {
    return NextResponse.json(
      { error: 'Device code not found' },
      { status: 404 }
    );
  }

  return NextResponse.json({
    status: entry.status,
    user_code: entry.userCode,
    expires_at: new Date(entry.expiresAt).toISOString(),
  });
}
