"use client";

import { useState, useRef, useCallback, useEffect } from "react";

// Types
interface GatewayConfig {
  aussieUrl: string;
  serviceId: string;
}

interface RateLimitRequest {
  requestNumber: number;
  status: number;
  timestamp: Date;
  rateLimitRemaining: string | null;
  rateLimitLimit: string | null;
  rateLimitReset: string | null;
  retryAfter: string | null;
}

interface AuthRateLimitRequest {
  requestNumber: number;
  status: number;
  response: string;
  timestamp: Date;
  retryAfter: string | null;
}

interface WebSocketMessage {
  id: string;
  type: "sent" | "received" | "system" | "error";
  content: string;
  timestamp: Date;
}

// Utility functions
function formatTimestamp(): string {
  return new Date().toLocaleTimeString();
}

function parseJwt(token: string): Record<string, unknown> | null {
  try {
    const base64Url = token.split(".")[1];
    const base64 = base64Url.replace(/-/g, "+").replace(/_/g, "/");
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split("")
        .map((c) => "%" + ("00" + c.charCodeAt(0).toString(16)).slice(-2))
        .join("")
    );
    return JSON.parse(jsonPayload);
  } catch {
    return null;
  }
}

function base64UrlEncode(buffer: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < buffer.length; i++) {
    binary += String.fromCharCode(buffer[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function generateCodeVerifier(): string {
  const array = new Uint8Array(64);
  crypto.getRandomValues(array);
  return base64UrlEncode(array);
}

async function generateCodeChallenge(verifier: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(verifier);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return base64UrlEncode(new Uint8Array(hash));
}

function getCloseReason(code: number): string {
  const reasons: Record<number, string> = {
    1000: "Normal closure",
    1001: "Going away",
    1002: "Protocol error",
    1003: "Unsupported data",
    1005: "No status received",
    1006: "Abnormal closure",
    1007: "Invalid frame payload data",
    1008: "Policy violation",
    1009: "Message too big",
    1010: "Missing extension",
    1011: "Internal error",
    1015: "TLS handshake failure",
    4001: "Unauthorized",
    4003: "Forbidden",
    4429: "Rate limit exceeded",
  };
  return reasons[code] || "Unknown reason";
}

// Card component for sections
function Card({
  title,
  children,
  className = "",
}: {
  title: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={`bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-lg p-6 ${className}`}
    >
      <h2 className="text-xl font-semibold text-zinc-900 dark:text-zinc-100 mb-4">
        {title}
      </h2>
      {children}
    </div>
  );
}

// Status badge component
function StatusBadge({ status }: { status: number }) {
  const getColor = () => {
    if (status >= 200 && status < 300)
      return "bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400";
    if (status === 429)
      return "bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400";
    return "bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400";
  };
  return (
    <span className={`inline-block px-2 py-1 text-xs rounded font-mono ${getColor()}`}>
      {status}
    </span>
  );
}

// Stat box component
function StatBox({
  label,
  value,
  variant = "default",
}: {
  label: string;
  value: string | number;
  variant?: "default" | "success" | "warning" | "danger";
}) {
  const getColor = () => {
    switch (variant) {
      case "success":
        return "text-green-600 dark:text-green-400";
      case "warning":
        return "text-amber-600 dark:text-amber-400";
      case "danger":
        return "text-red-600 dark:text-red-400";
      default:
        return "text-zinc-900 dark:text-zinc-100";
    }
  };
  return (
    <div className="bg-zinc-50 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded-lg p-4 text-center">
      <div className={`text-2xl font-bold ${getColor()}`}>{value}</div>
      <div className="text-xs text-zinc-500 dark:text-zinc-400 uppercase mt-1">
        {label}
      </div>
    </div>
  );
}

// Main Testing Dashboard Page
export default function TestingDashboardPage() {
  // Gateway config state
  const [config, setConfig] = useState<GatewayConfig>({
    aussieUrl: "http://localhost:1234",
    serviceId: "demo-service",
  });

  // Auth state
  const [authStatus, setAuthStatus] = useState<{
    authenticated: boolean;
    user: string | null;
    isOidc: boolean;
  }>({ authenticated: false, user: null, isOidc: false });
  const [authResult, setAuthResult] = useState<{
    show: boolean;
    success: boolean;
    message: string;
  }>({ show: false, success: false, message: "" });
  const [loginForm, setLoginForm] = useState({
    username: "testuser",
    password: "password",
    isAdmin: false,
  });

  // API test state
  const [apiPath, setApiPath] = useState("/api/health");
  const [apiMethod, setApiMethod] = useState("GET");
  const [apiResult, setApiResult] = useState<{
    show: boolean;
    success: boolean;
    message: string;
  }>({ show: false, success: false, message: "" });
  const [corsHeaders, setCorsHeaders] = useState<string>("No request made yet");

  // WebSocket state
  const echoSocketRef = useRef<WebSocket | null>(null);
  const chatSocketRef = useRef<WebSocket | null>(null);
  const [echoStatus, setEchoStatus] = useState<"disconnected" | "connecting" | "connected">("disconnected");
  const [chatStatus, setChatStatus] = useState<"disconnected" | "connecting" | "connected">("disconnected");
  const [echoMessages, setEchoMessages] = useState<WebSocketMessage[]>([
    { id: "init", type: "system", content: "Not connected", timestamp: new Date() },
  ]);
  const [chatMessages, setChatMessages] = useState<WebSocketMessage[]>([
    { id: "init", type: "system", content: "Not connected", timestamp: new Date() },
  ]);
  const [echoInput, setEchoInput] = useState("");
  const [chatInput, setChatInput] = useState("");

  // HTTP Rate limit test state
  const [httpRateLimitRunning, setHttpRateLimitRunning] = useState(false);
  const httpRateLimitAbortRef = useRef<AbortController | null>(null);
  const [httpRateLimitStats, setHttpRateLimitStats] = useState<{
    show: boolean;
    successCount: number;
    totalCount: number;
    rateLimitedAt: number | null;
    duration: number;
    headers: Record<string, string | null>;
    requests: RateLimitRequest[];
  }>({
    show: false,
    successCount: 0,
    totalCount: 0,
    rateLimitedAt: null,
    duration: 0,
    headers: {},
    requests: [],
  });
  const [httpRateLimitLiveCount, setHttpRateLimitLiveCount] = useState(0);

  // WebSocket Connection Rate Limit Test state
  const [wsConnTestRunning, setWsConnTestRunning] = useState(false);
  const wsConnTestSocketsRef = useRef<WebSocket[]>([]);
  const [wsConnTestStats, setWsConnTestStats] = useState<{
    show: boolean;
    successCount: number;
    rateLimitedAt: number | null;
  }>({ show: false, successCount: 0, rateLimitedAt: null });
  const [wsConnTestMessages, setWsConnTestMessages] = useState<WebSocketMessage[]>([]);
  const [wsConnLiveCount, setWsConnLiveCount] = useState(0);

  // WebSocket Message Rate Limit Test state
  const [wsMsgTestRunning, setWsMsgTestRunning] = useState(false);
  const wsMsgTestSocketRef = useRef<WebSocket | null>(null);
  const [wsMsgTestStats, setWsMsgTestStats] = useState<{
    show: boolean;
    successCount: number;
    closeCode: number | null;
  }>({ show: false, successCount: 0, closeCode: null });
  const [wsMsgTestMessages, setWsMsgTestMessages] = useState<WebSocketMessage[]>([]);
  const [wsMsgLiveCount, setWsMsgLiveCount] = useState(0);

  // Auth Rate Limit Test state
  const [authRateLimitRunning, setAuthRateLimitRunning] = useState(false);
  const authRateLimitAbortRef = useRef<AbortController | null>(null);
  const [authTestConfig, setAuthTestConfig] = useState({
    username: "attacker@example.com",
    maxAttempts: 10,
  });
  const [authRateLimitStats, setAuthRateLimitStats] = useState<{
    show: boolean;
    failedCount: number;
    totalCount: number;
    lockedOutAt: number | null;
    retryAfter: string | null;
    headers: Record<string, string | null>;
    requests: AuthRateLimitRequest[];
  }>({
    show: false,
    failedCount: 0,
    totalCount: 0,
    lockedOutAt: null,
    retryAfter: null,
    headers: {},
    requests: [],
  });
  const [authRateLimitLiveCount, setAuthRateLimitLiveCount] = useState(0);

  // Check session on mount and handle OIDC callback
  useEffect(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const code = urlParams.get("code");
    const state = urlParams.get("state");

    if (code && state) {
      handleOidcCallback(code, state);
    } else {
      const oidcToken = getOidcAccessToken();
      if (oidcToken) {
        updateOidcAuthStatus();
      } else {
        checkSession();
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // OIDC token helpers
  const getOidcAccessToken = (): string | null => {
    if (typeof window === "undefined") return null;
    const token = sessionStorage.getItem("oidc_access_token");
    const expiresAt = sessionStorage.getItem("oidc_token_expires_at");
    if (!token) return null;
    if (expiresAt && Date.now() > parseInt(expiresAt)) {
      clearOidcToken();
      return null;
    }
    return token;
  };

  const clearOidcToken = () => {
    sessionStorage.removeItem("oidc_access_token");
    sessionStorage.removeItem("oidc_token_expires_at");
  };

  const updateOidcAuthStatus = () => {
    const token = getOidcAccessToken();
    if (token) {
      const claims = parseJwt(token);
      setAuthStatus({
        authenticated: true,
        user: (claims?.sub as string) || (claims?.name as string) || "Unknown",
        isOidc: true,
      });
    } else {
      setAuthStatus({ authenticated: false, user: null, isOidc: false });
    }
  };

  // Auth functions
  const checkSession = async () => {
    try {
      const sessionUrl = `${config.aussieUrl}/auth/session`;
      const response = await fetch(sessionUrl, {
        method: "GET",
        credentials: "include",
        headers: { Accept: "application/json" },
      });

      if (response.ok) {
        const session = await response.json();
        setAuthStatus({
          authenticated: true,
          user: session.subject || session.userId || "Unknown",
          isOidc: false,
        });
        setAuthResult({
          show: true,
          success: true,
          message: `Session info:\n${JSON.stringify(session, null, 2)}`,
        });
      } else {
        setAuthStatus({ authenticated: false, user: null, isOidc: false });
        if (response.status === 401) {
          setAuthResult({
            show: true,
            success: false,
            message: "No active session. Please log in.",
          });
        } else {
          setAuthResult({
            show: true,
            success: false,
            message: `Session check failed: ${response.status} ${response.statusText}`,
          });
        }
      }
    } catch (error) {
      setAuthStatus({ authenticated: false, user: null, isOidc: false });
      setAuthResult({
        show: true,
        success: false,
        message: `Error checking session: ${error instanceof Error ? error.message : "Unknown error"}\n\nMake sure Aussie is running on ${config.aussieUrl}`,
      });
    }
  };

  const login = async () => {
    if (!loginForm.username) {
      setAuthResult({ show: true, success: false, message: "Please enter a username" });
      return;
    }

    setAuthResult({ show: true, success: true, message: "Logging in..." });

    try {
      const loginUrl = `${config.aussieUrl}/${config.serviceId}/api/auth/login`;
      const response = await fetch(loginUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        credentials: "include",
        body: JSON.stringify({
          username: loginForm.username,
          password: loginForm.password,
          isAdmin: loginForm.isAdmin,
          redirect: window.location.href,
        }),
      });

      const text = await response.text();
      const data = text ? JSON.parse(text) : {};

      if (!response.ok) {
        throw new Error(data.error || `Login failed: ${response.status}`);
      }

      if (data.callbackUrl) {
        const fullCallbackUrl = data.callbackUrl.startsWith("http")
          ? data.callbackUrl
          : `${config.aussieUrl}${data.callbackUrl}`;
        setAuthResult({
          show: true,
          success: true,
          message: `Login successful! Redirecting to create session...\n\nCallback URL: ${fullCallbackUrl}`,
        });
        setTimeout(() => {
          window.location.href = fullCallbackUrl;
        }, 1000);
      } else {
        setAuthResult({
          show: true,
          success: true,
          message: `Login response:\n${JSON.stringify(data, null, 2)}`,
        });
        setTimeout(checkSession, 500);
      }
    } catch (error) {
      setAuthResult({
        show: true,
        success: false,
        message: `Login error: ${error instanceof Error ? error.message : "Unknown error"}`,
      });
    }
  };

  const loginWithOidcPkce = async () => {
    try {
      setAuthResult({ show: true, success: true, message: "Initiating OIDC login with PKCE..." });

      const codeVerifier = generateCodeVerifier();
      const codeChallenge = await generateCodeChallenge(codeVerifier);
      const state = generateCodeVerifier().substring(0, 32);

      sessionStorage.setItem("pkce_code_verifier", codeVerifier);
      sessionStorage.setItem("pkce_state", state);

      const redirectUri = window.location.origin + window.location.pathname;
      const idpUrl = new URL("/api/auth/oidc/authorize", "http://localhost:3000");
      idpUrl.searchParams.set("response_type", "code");
      idpUrl.searchParams.set("client_id", "aussie-gateway");
      idpUrl.searchParams.set("redirect_uri", redirectUri);
      idpUrl.searchParams.set("state", state);
      idpUrl.searchParams.set("code_challenge", codeChallenge);
      idpUrl.searchParams.set("code_challenge_method", "S256");
      idpUrl.searchParams.set("scope", "openid profile email");

      setAuthResult({
        show: true,
        success: true,
        message: `Redirecting to OIDC authorization...\n\nCode Challenge: ${codeChallenge.substring(0, 20)}...\nState: ${state.substring(0, 10)}...\nRedirect URI: ${redirectUri}`,
      });

      setTimeout(() => {
        window.location.href = idpUrl.toString();
      }, 1500);
    } catch (error) {
      setAuthResult({
        show: true,
        success: false,
        message: `OIDC PKCE error: ${error instanceof Error ? error.message : "Unknown error"}`,
      });
    }
  };

  const handleOidcCallback = async (code: string, state: string) => {
    setAuthResult({ show: true, success: true, message: "Exchanging authorization code for tokens..." });

    try {
      const storedState = sessionStorage.getItem("pkce_state");
      if (!storedState || storedState !== state) {
        throw new Error("State mismatch. This may indicate a CSRF attack.");
      }

      const codeVerifier = sessionStorage.getItem("pkce_code_verifier");
      if (!codeVerifier) {
        throw new Error("Code verifier not found. The PKCE flow may have been interrupted.");
      }

      const redirectUri = window.location.origin + window.location.pathname;
      const tokenUrl = "http://localhost:3000/api/auth/oidc/token";

      const response = await fetch(tokenUrl, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "authorization_code",
          code,
          code_verifier: codeVerifier,
          redirect_uri: redirectUri,
          client_id: "aussie-gateway",
        }),
      });

      const data = await response.json();
      if (!response.ok) {
        throw new Error(data.error_description || data.error || `Token exchange failed: ${response.status}`);
      }

      sessionStorage.removeItem("pkce_code_verifier");
      sessionStorage.removeItem("pkce_state");

      if (data.access_token) {
        sessionStorage.setItem("oidc_access_token", data.access_token);
        if (data.expires_in) {
          const expiresAt = Date.now() + data.expires_in * 1000;
          sessionStorage.setItem("oidc_token_expires_at", expiresAt.toString());
        }
      }

      const cleanUrl = window.location.origin + window.location.pathname;
      window.history.replaceState({}, document.title, cleanUrl);

      setAuthResult({
        show: true,
        success: true,
        message: `OIDC PKCE flow completed successfully!\n\nTokens received:\n${JSON.stringify(data, null, 2)}`,
      });

      updateOidcAuthStatus();
    } catch (error) {
      setAuthResult({
        show: true,
        success: false,
        message: `Token exchange error: ${error instanceof Error ? error.message : "Unknown error"}`,
      });
      sessionStorage.removeItem("pkce_code_verifier");
      sessionStorage.removeItem("pkce_state");
      const cleanUrl = window.location.origin + window.location.pathname;
      window.history.replaceState({}, document.title, cleanUrl);
    }
  };

  const logout = async () => {
    const hadOidcToken = !!getOidcAccessToken();
    clearOidcToken();

    if (hadOidcToken) {
      setAuthResult({ show: true, success: true, message: "Logged out successfully (OIDC token cleared)" });
      updateOidcAuthStatus();
      return;
    }

    try {
      const logoutUrl = `${config.aussieUrl}/auth/session`;
      const response = await fetch(logoutUrl, {
        method: "DELETE",
        credentials: "include",
        headers: { Accept: "application/json" },
      });

      if (response.ok) {
        setAuthResult({ show: true, success: true, message: "Logged out successfully" });
      } else {
        const data = await response.json().catch(() => ({}));
        setAuthResult({
          show: true,
          success: false,
          message: `Logout failed: ${data.error || response.statusText}`,
        });
      }
      checkSession();
    } catch (error) {
      setAuthResult({
        show: true,
        success: false,
        message: `Logout error: ${error instanceof Error ? error.message : "Unknown error"}`,
      });
    }
  };

  // API test functions
  const makeRequest = async () => {
    const url = `${config.aussieUrl}/${config.serviceId}${apiPath}`;
    setApiResult({ show: true, success: true, message: `Requesting ${apiMethod} ${url}...` });

    try {
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        Accept: "application/json",
      };

      const accessToken = getOidcAccessToken();
      if (accessToken) {
        headers["Authorization"] = `Bearer ${accessToken}`;
      }

      const options: RequestInit = {
        method: apiMethod,
        headers,
        credentials: "include",
      };

      if (apiMethod === "POST" || apiMethod === "PUT") {
        options.body = JSON.stringify({
          name: "Test User",
          email: "test@example.com",
          timestamp: new Date().toISOString(),
        });
      }

      const response = await fetch(url, options);
      const corsHeadersObj = extractCorsHeaders(response.headers);

      let data;
      const contentType = response.headers.get("content-type");
      if (contentType && contentType.includes("application/json")) {
        data = await response.json();
      } else {
        data = await response.text();
      }

      setApiResult({
        show: true,
        success: response.ok,
        message: `Status: ${response.status} ${response.statusText}\n\nResponse:\n${JSON.stringify(data, null, 2)}`,
      });

      setCorsHeaders(formatCorsHeaders(corsHeadersObj, response));
    } catch (error) {
      setApiResult({
        show: true,
        success: false,
        message: `Error: ${error instanceof Error ? error.message : "Unknown error"}\n\nThis may be a CORS error. Check browser console for details.`,
      });
      setCorsHeaders("Request failed - no headers available");
    }
  };

  const sendPreflight = async () => {
    const url = `${config.aussieUrl}/${config.serviceId}${apiPath}`;
    setApiResult({ show: true, success: true, message: `Sending OPTIONS preflight to ${url}...` });

    try {
      const response = await fetch(url, {
        method: "OPTIONS",
        headers: {
          Origin: window.location.origin,
          "Access-Control-Request-Method": "POST",
          "Access-Control-Request-Headers": "Content-Type,Authorization",
        },
      });

      const corsHeadersObj = extractCorsHeaders(response.headers);
      setApiResult({
        show: true,
        success: response.ok,
        message: `Preflight Response: ${response.status} ${response.statusText}`,
      });
      setCorsHeaders(formatCorsHeaders(corsHeadersObj, response));
    } catch (error) {
      setApiResult({
        show: true,
        success: false,
        message: `Preflight Error: ${error instanceof Error ? error.message : "Unknown error"}`,
      });
      setCorsHeaders("Preflight failed - no headers available");
    }
  };

  const extractCorsHeaders = (headers: Headers): Record<string, string> => {
    const corsKeys = [
      "access-control-allow-origin",
      "access-control-allow-methods",
      "access-control-allow-headers",
      "access-control-allow-credentials",
      "access-control-expose-headers",
      "access-control-max-age",
      "vary",
    ];
    const result: Record<string, string> = {};
    corsKeys.forEach((key) => {
      const value = headers.get(key);
      if (value) result[key] = value;
    });
    return result;
  };

  const formatCorsHeaders = (corsHeaders: Record<string, string>, response: Response): string => {
    const entries = Object.entries(corsHeaders);
    if (entries.length === 0) {
      return "No CORS headers found in response.\n\nThis could mean:\n- CORS is not configured\n- The request was same-origin\n- The server didn't include CORS headers";
    }
    let output = `CORS Headers from ${response.url}:\n\n`;
    entries.forEach(([key, value]) => {
      output += `${key}: ${value}\n`;
    });
    return output;
  };

  // WebSocket helper
  const getWebSocketUrl = (path: string): string => {
    const wsProtocol = config.aussieUrl.startsWith("https") ? "wss" : "ws";
    const host = config.aussieUrl.replace(/^https?:\/\//, "");
    return `${wsProtocol}://${host}/${config.serviceId}${path}`;
  };

  const addWsMessage = useCallback(
    (
      setter: React.Dispatch<React.SetStateAction<WebSocketMessage[]>>,
      content: string,
      type: WebSocketMessage["type"]
    ) => {
      setter((prev) => [
        ...prev,
        { id: `${Date.now()}-${Math.random()}`, type, content, timestamp: new Date() },
      ]);
    },
    []
  );

  // Echo WebSocket functions
  const connectEcho = () => {
    if (echoSocketRef.current?.readyState === WebSocket.OPEN) return;

    const url = getWebSocketUrl("/ws/echo");
    setEchoStatus("connecting");
    addWsMessage(setEchoMessages, `Connecting to ${url}...`, "system");

    try {
      const socket = new WebSocket(url);
      echoSocketRef.current = socket;

      socket.onopen = () => {
        setEchoStatus("connected");
        addWsMessage(setEchoMessages, "Connected to Echo WebSocket", "system");
      };

      socket.onmessage = (event) => {
        const content = event.data instanceof Blob ? "[Blob data]" : event.data;
        addWsMessage(setEchoMessages, `Server: ${content}`, "received");
      };

      socket.onerror = () => {
        addWsMessage(setEchoMessages, "Error: WebSocket error occurred", "error");
      };

      socket.onclose = (event) => {
        setEchoStatus("disconnected");
        const reason = event.reason || getCloseReason(event.code);
        addWsMessage(setEchoMessages, `Disconnected: ${reason} (code: ${event.code})`, "system");
        echoSocketRef.current = null;
      };
    } catch (error) {
      setEchoStatus("disconnected");
      addWsMessage(
        setEchoMessages,
        `Failed to connect: ${error instanceof Error ? error.message : "Unknown error"}`,
        "error"
      );
    }
  };

  const disconnectEcho = () => {
    echoSocketRef.current?.close(1000, "User disconnected");
  };

  const sendEchoMessage = () => {
    if (!echoSocketRef.current || echoSocketRef.current.readyState !== WebSocket.OPEN) return;
    if (!echoInput.trim()) return;
    echoSocketRef.current.send(echoInput);
    addWsMessage(setEchoMessages, `You: ${echoInput}`, "sent");
    setEchoInput("");
  };

  // Chat WebSocket functions
  const connectChat = () => {
    if (chatSocketRef.current?.readyState === WebSocket.OPEN) return;

    const url = getWebSocketUrl("/ws/chat");
    setChatStatus("connecting");
    addWsMessage(setChatMessages, `Connecting to ${url}...`, "system");
    addWsMessage(setChatMessages, "Note: This endpoint requires authentication", "system");

    try {
      const socket = new WebSocket(url);
      chatSocketRef.current = socket;

      socket.onopen = () => {
        setChatStatus("connected");
        addWsMessage(setChatMessages, "Connected to Chat WebSocket", "system");
      };

      socket.onmessage = (event) => {
        const content = event.data instanceof Blob ? "[Blob data]" : event.data;
        addWsMessage(setChatMessages, `Server: ${content}`, "received");
      };

      socket.onerror = () => {
        addWsMessage(setChatMessages, "Error: WebSocket error occurred", "error");
      };

      socket.onclose = (event) => {
        setChatStatus("disconnected");
        const reason = event.reason || getCloseReason(event.code);
        addWsMessage(setChatMessages, `Disconnected: ${reason} (code: ${event.code})`, "system");
        if (event.code === 4429 || event.code === 1008 || event.code === 4001) {
          addWsMessage(
            setChatMessages,
            "Hint: Try logging in first using the Authentication section above",
            "error"
          );
        }
        chatSocketRef.current = null;
      };
    } catch (error) {
      setChatStatus("disconnected");
      addWsMessage(
        setChatMessages,
        `Failed to connect: ${error instanceof Error ? error.message : "Unknown error"}`,
        "error"
      );
    }
  };

  const disconnectChat = () => {
    chatSocketRef.current?.close(1000, "User disconnected");
  };

  const sendChatMessage = () => {
    if (!chatSocketRef.current || chatSocketRef.current.readyState !== WebSocket.OPEN) return;
    if (!chatInput.trim()) return;
    chatSocketRef.current.send(chatInput);
    addWsMessage(setChatMessages, `You: ${chatInput}`, "sent");
    setChatInput("");
  };

  // HTTP Rate limit test
  const startHttpRateLimitTest = async () => {
    const url = `${config.aussieUrl}/${config.serviceId}/api/rate-limit-test`;
    setHttpRateLimitRunning(true);
    setHttpRateLimitLiveCount(0);
    httpRateLimitAbortRef.current = new AbortController();

    const startTime = Date.now();
    let successCount = 0;
    let totalAttempted = 0;
    let rateLimitedAt: number | null = null;
    let lastHeaders: Record<string, string | null> = {};
    const requests: RateLimitRequest[] = [];

    try {
      while (!httpRateLimitAbortRef.current.signal.aborted && totalAttempted < 200) {
        totalAttempted++;
        setHttpRateLimitLiveCount(totalAttempted);

        const response = await fetch(url, {
          method: "GET",
          credentials: "include",
          signal: httpRateLimitAbortRef.current.signal,
        });

        const requestResult: RateLimitRequest = {
          requestNumber: totalAttempted,
          status: response.status,
          timestamp: new Date(),
          rateLimitRemaining: response.headers.get("X-RateLimit-Remaining"),
          rateLimitLimit: response.headers.get("X-RateLimit-Limit"),
          rateLimitReset: response.headers.get("X-RateLimit-Reset"),
          retryAfter: response.headers.get("Retry-After"),
        };

        requests.push(requestResult);
        lastHeaders = {
          "X-RateLimit-Limit": requestResult.rateLimitLimit,
          "X-RateLimit-Remaining": requestResult.rateLimitRemaining,
          "X-RateLimit-Reset": requestResult.rateLimitReset,
          "Retry-After": requestResult.retryAfter,
        };

        if (response.status === 429) {
          rateLimitedAt = totalAttempted;
          break;
        }

        if (response.ok) successCount++;

        await new Promise((resolve) => setTimeout(resolve, 10));
      }
    } catch (err) {
      if ((err as Error).name !== "AbortError") {
        console.error("Rate limit test error:", err);
      }
    }

    const durationMs = Date.now() - startTime;
    setHttpRateLimitStats({
      show: true,
      successCount,
      totalCount: totalAttempted,
      rateLimitedAt,
      duration: durationMs,
      headers: lastHeaders,
      requests: requests.slice(-20),
    });
    setHttpRateLimitRunning(false);
  };

  const stopHttpRateLimitTest = () => {
    httpRateLimitAbortRef.current?.abort();
  };

  // WebSocket Connection Rate Limit Test
  const startWsConnectionTest = async () => {
    const wsUrl = getWebSocketUrl("/ws/echo");
    setWsConnTestRunning(true);
    setWsConnLiveCount(0);
    setWsConnTestMessages([]);
    wsConnTestSocketsRef.current = [];

    let successCount = 0;
    let totalAttempted = 0;
    let rateLimitedAt: number | null = null;

    addWsMessage(setWsConnTestMessages, `Starting connection rate limit test to ${wsUrl}`, "system");

    while (totalAttempted < 50 && !rateLimitedAt) {
      if (!wsMsgTestRunning && totalAttempted > 0) {
        // Check if we should stop
        const shouldContinue = await new Promise<boolean>((resolve) => {
          setTimeout(() => resolve(true), 50);
        });
        if (!shouldContinue) break;
      }

      totalAttempted++;
      setWsConnLiveCount(totalAttempted);

      try {
        const result = await new Promise<{
          success: boolean;
          socket?: WebSocket;
          rateLimited?: boolean;
          error?: string;
        }>((resolve) => {
          const socket = new WebSocket(wsUrl);
          let resolved = false;

          socket.onopen = () => {
            if (!resolved) {
              resolved = true;
              resolve({ success: true, socket });
            }
          };

          socket.onerror = () => {
            if (!resolved) {
              resolved = true;
              resolve({ success: false, error: "Connection failed" });
            }
          };

          socket.onclose = (event) => {
            if (!resolved) {
              resolved = true;
              if (event.code === 4429 || event.reason?.includes("rate limit")) {
                resolve({ success: false, rateLimited: true });
              } else {
                resolve({
                  success: false,
                  error: `Closed: ${event.code} - ${event.reason || "Unknown"}`,
                });
              }
            }
          };

          setTimeout(() => {
            if (!resolved) {
              resolved = true;
              try {
                socket.close();
              } catch {}
              resolve({ success: false, error: "Timeout" });
            }
          }, 5000);
        });

        if (result.success && result.socket) {
          successCount++;
          wsConnTestSocketsRef.current.push(result.socket);
          addWsMessage(setWsConnTestMessages, `#${totalAttempted}: Connected`, "received");
        } else if (result.rateLimited) {
          rateLimitedAt = totalAttempted;
          addWsMessage(setWsConnTestMessages, `#${totalAttempted}: Rate limited (429)`, "error");
        } else {
          addWsMessage(setWsConnTestMessages, `#${totalAttempted}: Failed - ${result.error}`, "error");
        }
      } catch (err) {
        addWsMessage(
          setWsConnTestMessages,
          `#${totalAttempted}: Error - ${err instanceof Error ? err.message : "Unknown"}`,
          "error"
        );
      }

      await new Promise((resolve) => setTimeout(resolve, 50));
    }

    // Clean up
    addWsMessage(
      setWsConnTestMessages,
      `Closing ${wsConnTestSocketsRef.current.length} connections...`,
      "system"
    );
    wsConnTestSocketsRef.current.forEach((socket) => {
      try {
        socket.close(1000, "Test complete");
      } catch {}
    });
    wsConnTestSocketsRef.current = [];

    setWsConnTestStats({ show: true, successCount, rateLimitedAt });
    addWsMessage(
      setWsConnTestMessages,
      rateLimitedAt
        ? `Test complete: Rate limited after ${rateLimitedAt} attempts (${successCount} successful)`
        : `Test complete: ${successCount} successful connections (no rate limit hit)`,
      "system"
    );

    setWsConnTestRunning(false);
  };

  const stopWsConnectionTest = () => {
    setWsConnTestRunning(false);
    addWsMessage(setWsConnTestMessages, "Test stopped by user", "system");
  };

  // WebSocket Message Rate Limit Test
  const startWsMessageTest = async () => {
    const wsUrl = getWebSocketUrl("/ws/echo");
    setWsMsgTestRunning(true);
    setWsMsgLiveCount(0);
    setWsMsgTestMessages([]);

    let successCount = 0;
    let closeCode: number | null = null;

    addWsMessage(setWsMsgTestMessages, `Connecting to ${wsUrl}...`, "system");

    try {
      const socket = await new Promise<WebSocket>((resolve, reject) => {
        const ws = new WebSocket(wsUrl);
        ws.onopen = () => resolve(ws);
        ws.onerror = () => reject(new Error("Connection failed"));
        setTimeout(() => reject(new Error("Connection timeout")), 5000);
      });

      wsMsgTestSocketRef.current = socket;
      addWsMessage(setWsMsgTestMessages, "Connected! Sending rapid messages...", "received");

      let running = true;
      socket.onclose = (event) => {
        closeCode = event.code;
        running = false;
        addWsMessage(
          setWsMsgTestMessages,
          `Connection closed: ${event.code} - ${event.reason || getCloseReason(event.code)}`,
          event.code === 4429 ? "error" : "system"
        );
      };

      socket.onerror = () => {
        addWsMessage(setWsMsgTestMessages, "WebSocket error occurred", "error");
      };

      while (running && successCount < 500 && socket.readyState === WebSocket.OPEN) {
        try {
          socket.send(`Test message #${successCount + 1}`);
          successCount++;
          setWsMsgLiveCount(successCount);
        } catch {
          break;
        }

        if (successCount % 10 === 0) {
          await new Promise((resolve) => setTimeout(resolve, 1));
        }
      }

      await new Promise((resolve) => setTimeout(resolve, 100));
    } catch (err) {
      addWsMessage(
        setWsMsgTestMessages,
        `Error: ${err instanceof Error ? err.message : "Unknown"}`,
        "error"
      );
    }

    if (wsMsgTestSocketRef.current?.readyState === WebSocket.OPEN) {
      addWsMessage(setWsMsgTestMessages, "Closing connection...", "system");
      wsMsgTestSocketRef.current.close(1000, "Test complete");
    }
    wsMsgTestSocketRef.current = null;

    setWsMsgTestStats({ show: true, successCount, closeCode });
    addWsMessage(
      setWsMsgTestMessages,
      closeCode === 4429
        ? `Test complete: Rate limited after ${successCount} messages (close code: 4429)`
        : `Test complete: ${successCount} messages sent${closeCode ? ` (close code: ${closeCode})` : ""}`,
      "system"
    );

    setWsMsgTestRunning(false);
  };

  const stopWsMessageTest = () => {
    setWsMsgTestRunning(false);
    if (wsMsgTestSocketRef.current?.readyState === WebSocket.OPEN) {
      wsMsgTestSocketRef.current.close(1000, "Test stopped");
    }
    addWsMessage(setWsMsgTestMessages, "Test stopped by user", "system");
  };

  // Auth Rate Limit Test
  const startAuthRateLimitTest = async () => {
    const url = `${config.aussieUrl}/${config.serviceId}/api/auth/fail`;
    setAuthRateLimitRunning(true);
    setAuthRateLimitLiveCount(0);
    authRateLimitAbortRef.current = new AbortController();

    let failedCount = 0;
    let totalAttempted = 0;
    let lockedOutAt: number | null = null;
    let lastHeaders: Record<string, string | null> = {};
    const requests: AuthRateLimitRequest[] = [];

    try {
      while (
        !authRateLimitAbortRef.current.signal.aborted &&
        totalAttempted < authTestConfig.maxAttempts
      ) {
        totalAttempted++;
        setAuthRateLimitLiveCount(totalAttempted);

        const response = await fetch(url, {
          method: "POST",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          credentials: "include",
          body: JSON.stringify({
            username: authTestConfig.username,
            password: `wrong-password-${totalAttempted}`,
          }),
          signal: authRateLimitAbortRef.current.signal,
        });

        let responseText = "";
        try {
          const data = await response.json();
          responseText = data.error || data.message || JSON.stringify(data);
        } catch {
          responseText = await response.text();
        }

        const requestResult: AuthRateLimitRequest = {
          requestNumber: totalAttempted,
          status: response.status,
          response: responseText.substring(0, 50),
          timestamp: new Date(),
          retryAfter: response.headers.get("Retry-After"),
        };

        requests.push(requestResult);

        if (response.status === 429) {
          lockedOutAt = totalAttempted;
          lastHeaders = {
            "Retry-After": requestResult.retryAfter,
            "X-Auth-RateLimit-Remaining": response.headers.get("X-Auth-RateLimit-Remaining"),
            "X-Auth-RateLimit-Reset": response.headers.get("X-Auth-RateLimit-Reset"),
          };
          break;
        }

        if (response.status === 401) failedCount++;

        await new Promise((resolve) => setTimeout(resolve, 100));
      }
    } catch (err) {
      if ((err as Error).name !== "AbortError") {
        console.error("Auth rate limit test error:", err);
      }
    }

    const retryAfter = lastHeaders["Retry-After"];
    let retryAfterFormatted: string | null = null;
    if (retryAfter) {
      const seconds = parseInt(retryAfter);
      retryAfterFormatted = seconds >= 60 ? `${Math.round(seconds / 60)}m` : `${seconds}s`;
    }

    setAuthRateLimitStats({
      show: true,
      failedCount,
      totalCount: totalAttempted,
      lockedOutAt,
      retryAfter: retryAfterFormatted,
      headers: lastHeaders,
      requests,
    });
    setAuthRateLimitRunning(false);
  };

  const stopAuthRateLimitTest = () => {
    authRateLimitAbortRef.current?.abort();
  };

  // Render WebSocket message
  const renderWsMessage = (msg: WebSocketMessage) => {
    const colors: Record<WebSocketMessage["type"], string> = {
      sent: "text-blue-600 dark:text-blue-400",
      received: "text-green-600 dark:text-green-400",
      system: "text-zinc-500 dark:text-zinc-400 italic",
      error: "text-red-600 dark:text-red-400",
    };
    return (
      <div
        key={msg.id}
        className={`text-sm py-1 border-b border-zinc-100 dark:border-zinc-800 last:border-0 ${colors[msg.type]}`}
      >
        [{msg.timestamp.toLocaleTimeString()}] {msg.content}
      </div>
    );
  };

  return (
    <div className="min-h-screen bg-zinc-50 dark:bg-black p-8">
      <div className="max-w-4xl mx-auto space-y-6">
        <header className="mb-8">
          <h1 className="text-3xl font-bold text-zinc-900 dark:text-zinc-100">
            Aussie Gateway Testing Dashboard
          </h1>
          <p className="mt-2 text-zinc-600 dark:text-zinc-400">
            Testing HTTP, CORS, and WebSocket connections through the API gateway
          </p>
        </header>

        {/* Architecture Info */}
        <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg p-4">
          <h3 className="font-semibold text-blue-700 dark:text-blue-400 mb-2">Architecture</h3>
          <p className="text-sm text-blue-900 dark:text-blue-200">
            <strong>This UI</strong> runs on <code className="bg-blue-100 dark:bg-blue-800 px-1 rounded">localhost:3000</code>
            <br />
            <strong>Aussie Gateway</strong> runs on <code className="bg-blue-100 dark:bg-blue-800 px-1 rounded">localhost:1234</code>
            <br />
            <strong>Demo API</strong> runs on <code className="bg-blue-100 dark:bg-blue-800 px-1 rounded">localhost:3000</code>
          </p>
          <p className="text-sm text-blue-800 dark:text-blue-300 mt-2">
            When you make a request, the browser sends it to Aussie, which proxies it to the Demo
            API. CORS headers allow this cross-origin communication.
          </p>
        </div>

        {/* Authentication Section */}
        <Card title="Authentication">
          <div
            className={`flex items-center gap-4 p-4 rounded-lg mb-4 ${
              authStatus.authenticated
                ? "bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800"
                : "bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800"
            }`}
          >
            <span className="text-2xl">{authStatus.authenticated ? "✓" : "⚠"}</span>
            <div className="flex-1">
              <span
                className={
                  authStatus.authenticated
                    ? "text-green-700 dark:text-green-400"
                    : "text-amber-700 dark:text-amber-400"
                }
              >
                {authStatus.authenticated
                  ? `Authenticated${authStatus.isOidc ? " (OIDC Token)" : ""}`
                  : "Not authenticated"}
              </span>
              {authStatus.user && (
                <div className="font-medium text-zinc-900 dark:text-zinc-100">
                  User: {authStatus.user}
                </div>
              )}
            </div>
            {authStatus.authenticated && (
              <button
                onClick={logout}
                className="px-4 py-2 bg-red-500 text-white rounded-lg hover:bg-red-600 text-sm"
              >
                Logout
              </button>
            )}
          </div>

          {!authStatus.authenticated && (
            <div className="space-y-4">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                    Username
                  </label>
                  <input
                    type="text"
                    value={loginForm.username}
                    onChange={(e) => setLoginForm({ ...loginForm, username: e.target.value })}
                    className="w-full px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100"
                    placeholder="Enter any username"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                    Password
                  </label>
                  <input
                    type="password"
                    value={loginForm.password}
                    onChange={(e) => setLoginForm({ ...loginForm, password: e.target.value })}
                    className="w-full px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100"
                    placeholder="Enter any password"
                  />
                </div>
              </div>
              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="isAdmin"
                  checked={loginForm.isAdmin}
                  onChange={(e) => setLoginForm({ ...loginForm, isAdmin: e.target.checked })}
                  className="rounded"
                />
                <label htmlFor="isAdmin" className="text-sm text-zinc-600 dark:text-zinc-400">
                  Login as Admin (grants admin permissions)
                </label>
              </div>
              <div className="flex gap-2 flex-wrap">
                <button
                  onClick={login}
                  className="px-4 py-2 bg-green-500 text-white rounded-lg hover:bg-green-600"
                >
                  Login via Aussie
                </button>
                <button
                  onClick={loginWithOidcPkce}
                  className="px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600"
                >
                  Login with OIDC (PKCE)
                </button>
                <button
                  onClick={checkSession}
                  className="px-4 py-2 bg-zinc-500 text-white rounded-lg hover:bg-zinc-600"
                >
                  Check Session
                </button>
              </div>
            </div>
          )}

          {authResult.show && (
            <div
              className={`mt-4 p-4 rounded-lg font-mono text-sm whitespace-pre-wrap ${
                authResult.success
                  ? "bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800"
                  : "bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800"
              }`}
            >
              {authResult.message}
            </div>
          )}
        </Card>

        {/* Gateway Configuration */}
        <Card title="Gateway Configuration">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                Aussie Gateway URL
              </label>
              <input
                type="text"
                value={config.aussieUrl}
                onChange={(e) => setConfig({ ...config, aussieUrl: e.target.value })}
                className="w-full px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100 font-mono"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                Service ID
              </label>
              <input
                type="text"
                value={config.serviceId}
                onChange={(e) => setConfig({ ...config, serviceId: e.target.value })}
                className="w-full px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100 font-mono"
              />
            </div>
          </div>
        </Card>

        {/* API Testing */}
        <Card title="Test API Endpoints">
          <p className="text-sm text-zinc-600 dark:text-zinc-400 mb-4">Quick test endpoints:</p>
          <div className="flex flex-wrap gap-2 mb-4">
            {[
              { path: "/api/health", method: "GET" },
              { path: "/api/users", method: "GET" },
              { path: "/api/users", method: "POST" },
              { path: "/api/admin/stats", method: "GET", label: "GET /api/admin/stats (requires auth)" },
              { path: "/api/rate-limit-test", method: "GET" },
            ].map((endpoint) => (
              <button
                key={`${endpoint.method}-${endpoint.path}`}
                onClick={() => {
                  setApiPath(endpoint.path);
                  setApiMethod(endpoint.method);
                  setTimeout(makeRequest, 0);
                }}
                className="px-3 py-2 text-sm bg-zinc-100 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded-lg hover:bg-zinc-200 dark:hover:bg-zinc-700"
              >
                {endpoint.label || `${endpoint.method} ${endpoint.path}`}
              </button>
            ))}
          </div>

          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                Custom Path
              </label>
              <input
                type="text"
                value={apiPath}
                onChange={(e) => setApiPath(e.target.value)}
                className="w-full px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100 font-mono"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                HTTP Method
              </label>
              <select
                value={apiMethod}
                onChange={(e) => setApiMethod(e.target.value)}
                className="w-full px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100"
              >
                {["GET", "POST", "PUT", "DELETE", "OPTIONS"].map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex gap-2">
              <button
                onClick={makeRequest}
                className="px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600"
              >
                Send Request
              </button>
              <button
                onClick={sendPreflight}
                className="px-4 py-2 bg-zinc-500 text-white rounded-lg hover:bg-zinc-600"
              >
                Test Preflight
              </button>
            </div>
          </div>

          {apiResult.show && (
            <div
              className={`mt-4 p-4 rounded-lg font-mono text-sm whitespace-pre-wrap overflow-x-auto ${
                apiResult.success
                  ? "bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800"
                  : "bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800"
              }`}
            >
              {apiResult.message}
            </div>
          )}
        </Card>

        {/* Response Headers */}
        <Card title="Response Headers">
          <p className="text-sm text-zinc-600 dark:text-zinc-400 mb-4">
            CORS headers from the last response:
          </p>
          <div className="p-4 bg-zinc-50 dark:bg-zinc-800 rounded-lg font-mono text-sm whitespace-pre-wrap">
            {corsHeaders}
          </div>
        </Card>

        {/* WebSocket Testing */}
        <Card title="WebSocket Testing">
          <p className="text-sm text-zinc-600 dark:text-zinc-400 mb-4">
            Test WebSocket connections through the Aussie gateway:
          </p>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            {/* Echo WebSocket */}
            <div className="bg-zinc-50 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded-lg p-4">
              <div className="flex items-center gap-2 mb-2">
                <h3 className="font-semibold text-zinc-900 dark:text-zinc-100">Echo WebSocket</h3>
                <span className="px-2 py-0.5 text-xs bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400 rounded">
                  PUBLIC
                </span>
              </div>
              <p className="text-xs text-zinc-500 mb-2">/ws/echo - Echoes messages back</p>
              <div className="flex items-center gap-2 mb-2">
                <div
                  className={`w-3 h-3 rounded-full ${
                    echoStatus === "connected"
                      ? "bg-green-500"
                      : echoStatus === "connecting"
                        ? "bg-amber-500 animate-pulse"
                        : "bg-red-500"
                  }`}
                />
                <span className="text-sm text-zinc-600 dark:text-zinc-400 capitalize">
                  {echoStatus}
                </span>
              </div>
              <div className="h-48 overflow-y-auto bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-700 rounded p-2 mb-2 font-mono text-xs">
                {echoMessages.map(renderWsMessage)}
              </div>
              <div className="flex gap-2 mb-2">
                <input
                  type="text"
                  value={echoInput}
                  onChange={(e) => setEchoInput(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && sendEchoMessage()}
                  disabled={echoStatus !== "connected"}
                  placeholder="Type a message..."
                  className="flex-1 px-3 py-2 text-sm border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100 disabled:opacity-50"
                />
                <button
                  onClick={sendEchoMessage}
                  disabled={echoStatus !== "connected" || !echoInput.trim()}
                  className="px-4 py-2 text-sm bg-blue-500 text-white rounded-lg hover:bg-blue-600 disabled:opacity-50"
                >
                  Send
                </button>
              </div>
              <div className="flex gap-2">
                {echoStatus !== "connected" ? (
                  <button
                    onClick={connectEcho}
                    disabled={echoStatus === "connecting"}
                    className="px-3 py-1.5 text-sm bg-green-500 text-white rounded-lg hover:bg-green-600 disabled:opacity-50"
                  >
                    Connect
                  </button>
                ) : (
                  <button
                    onClick={disconnectEcho}
                    className="px-3 py-1.5 text-sm bg-red-500 text-white rounded-lg hover:bg-red-600"
                  >
                    Disconnect
                  </button>
                )}
                <button
                  onClick={() =>
                    setEchoMessages([
                      { id: "cleared", type: "system", content: "Messages cleared", timestamp: new Date() },
                    ])
                  }
                  className="px-3 py-1.5 text-sm bg-zinc-500 text-white rounded-lg hover:bg-zinc-600"
                >
                  Clear
                </button>
              </div>
            </div>

            {/* Chat WebSocket */}
            <div className="bg-zinc-50 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded-lg p-4">
              <div className="flex items-center gap-2 mb-2">
                <h3 className="font-semibold text-zinc-900 dark:text-zinc-100">Chat WebSocket</h3>
                <span className="px-2 py-0.5 text-xs bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400 rounded">
                  AUTH REQUIRED
                </span>
              </div>
              <p className="text-xs text-zinc-500 mb-2">/ws/chat - Requires authentication</p>
              <div className="flex items-center gap-2 mb-2">
                <div
                  className={`w-3 h-3 rounded-full ${
                    chatStatus === "connected"
                      ? "bg-green-500"
                      : chatStatus === "connecting"
                        ? "bg-amber-500 animate-pulse"
                        : "bg-red-500"
                  }`}
                />
                <span className="text-sm text-zinc-600 dark:text-zinc-400 capitalize">
                  {chatStatus}
                </span>
              </div>
              <div className="h-48 overflow-y-auto bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-700 rounded p-2 mb-2 font-mono text-xs">
                {chatMessages.map(renderWsMessage)}
              </div>
              <div className="flex gap-2 mb-2">
                <input
                  type="text"
                  value={chatInput}
                  onChange={(e) => setChatInput(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && sendChatMessage()}
                  disabled={chatStatus !== "connected"}
                  placeholder="Type a message..."
                  className="flex-1 px-3 py-2 text-sm border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100 disabled:opacity-50"
                />
                <button
                  onClick={sendChatMessage}
                  disabled={chatStatus !== "connected" || !chatInput.trim()}
                  className="px-4 py-2 text-sm bg-blue-500 text-white rounded-lg hover:bg-blue-600 disabled:opacity-50"
                >
                  Send
                </button>
              </div>
              <div className="flex gap-2">
                {chatStatus !== "connected" ? (
                  <button
                    onClick={connectChat}
                    disabled={chatStatus === "connecting"}
                    className="px-3 py-1.5 text-sm bg-green-500 text-white rounded-lg hover:bg-green-600 disabled:opacity-50"
                  >
                    Connect
                  </button>
                ) : (
                  <button
                    onClick={disconnectChat}
                    className="px-3 py-1.5 text-sm bg-red-500 text-white rounded-lg hover:bg-red-600"
                  >
                    Disconnect
                  </button>
                )}
                <button
                  onClick={() =>
                    setChatMessages([
                      { id: "cleared", type: "system", content: "Messages cleared", timestamp: new Date() },
                    ])
                  }
                  className="px-3 py-1.5 text-sm bg-zinc-500 text-white rounded-lg hover:bg-zinc-600"
                >
                  Clear
                </button>
              </div>
            </div>
          </div>
        </Card>

        {/* WebSocket Rate Limit Testing */}
        <Card title="WebSocket Rate Limit Testing">
          <p className="text-sm text-zinc-600 dark:text-zinc-400 mb-4">
            Test WebSocket rate limiting by rapidly establishing connections or sending messages.
          </p>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            {/* Connection Rate Limit Test */}
            <div className="bg-zinc-50 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded-lg p-4">
              <h3 className="font-semibold text-zinc-900 dark:text-zinc-100 mb-2">
                Connection Rate Limit
              </h3>
              <p className="text-xs text-zinc-500 mb-2">
                Rapidly open WebSocket connections until rate limited (HTTP 429)
              </p>
              <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded p-2 text-xs mb-3">
                <strong>Default:</strong> 10 connections/minute, burst: 5
              </div>

              {wsConnTestRunning && (
                <div className="flex items-center gap-2 bg-blue-100 dark:bg-blue-900/30 rounded p-2 mb-3">
                  <div className="w-3 h-3 bg-green-500 rounded-full animate-pulse" />
                  <span className="text-sm">
                    Connections attempted: <strong>{wsConnLiveCount}</strong>
                  </span>
                </div>
              )}

              {wsConnTestStats.show && (
                <div className="grid grid-cols-2 gap-2 mb-3">
                  <StatBox
                    label="Connected"
                    value={wsConnTestStats.successCount}
                    variant="success"
                  />
                  <StatBox
                    label="Rate Limited At"
                    value={wsConnTestStats.rateLimitedAt ? `#${wsConnTestStats.rateLimitedAt}` : "-"}
                    variant="warning"
                  />
                </div>
              )}

              {wsConnTestMessages.length > 0 && (
                <div className="h-32 overflow-y-auto bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-700 rounded p-2 mb-3 font-mono text-xs">
                  {wsConnTestMessages.map(renderWsMessage)}
                </div>
              )}

              <div className="flex gap-2">
                {!wsConnTestRunning ? (
                  <button
                    onClick={startWsConnectionTest}
                    className="px-3 py-1.5 text-sm bg-green-500 text-white rounded-lg hover:bg-green-600"
                  >
                    Start Test
                  </button>
                ) : (
                  <button
                    onClick={stopWsConnectionTest}
                    className="px-3 py-1.5 text-sm bg-red-500 text-white rounded-lg hover:bg-red-600"
                  >
                    Stop
                  </button>
                )}
                {wsConnTestStats.show && (
                  <button
                    onClick={() => {
                      setWsConnTestStats({ show: false, successCount: 0, rateLimitedAt: null });
                      setWsConnTestMessages([]);
                    }}
                    className="px-3 py-1.5 text-sm bg-zinc-500 text-white rounded-lg hover:bg-zinc-600"
                  >
                    Clear
                  </button>
                )}
              </div>
            </div>

            {/* Message Rate Limit Test */}
            <div className="bg-zinc-50 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded-lg p-4">
              <h3 className="font-semibold text-zinc-900 dark:text-zinc-100 mb-2">
                Message Rate Limit
              </h3>
              <p className="text-xs text-zinc-500 mb-2">
                Send rapid messages until rate limited (connection closed with code 4429)
              </p>
              <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded p-2 text-xs mb-3">
                <strong>Default:</strong> 100 messages/second, burst: 50
              </div>

              {wsMsgTestRunning && (
                <div className="flex items-center gap-2 bg-blue-100 dark:bg-blue-900/30 rounded p-2 mb-3">
                  <div className="w-3 h-3 bg-green-500 rounded-full animate-pulse" />
                  <span className="text-sm">
                    Messages sent: <strong>{wsMsgLiveCount}</strong>
                  </span>
                </div>
              )}

              {wsMsgTestStats.show && (
                <div className="grid grid-cols-2 gap-2 mb-3">
                  <StatBox label="Sent" value={wsMsgTestStats.successCount} variant="success" />
                  <StatBox
                    label="Close Code"
                    value={wsMsgTestStats.closeCode || "-"}
                    variant="warning"
                  />
                </div>
              )}

              {wsMsgTestMessages.length > 0 && (
                <div className="h-32 overflow-y-auto bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-700 rounded p-2 mb-3 font-mono text-xs">
                  {wsMsgTestMessages.map(renderWsMessage)}
                </div>
              )}

              <div className="flex gap-2">
                {!wsMsgTestRunning ? (
                  <button
                    onClick={startWsMessageTest}
                    className="px-3 py-1.5 text-sm bg-green-500 text-white rounded-lg hover:bg-green-600"
                  >
                    Start Test
                  </button>
                ) : (
                  <button
                    onClick={stopWsMessageTest}
                    className="px-3 py-1.5 text-sm bg-red-500 text-white rounded-lg hover:bg-red-600"
                  >
                    Stop
                  </button>
                )}
                {wsMsgTestStats.show && (
                  <button
                    onClick={() => {
                      setWsMsgTestStats({ show: false, successCount: 0, closeCode: null });
                      setWsMsgTestMessages([]);
                    }}
                    className="px-3 py-1.5 text-sm bg-zinc-500 text-white rounded-lg hover:bg-zinc-600"
                  >
                    Clear
                  </button>
                )}
              </div>
            </div>
          </div>
        </Card>

        {/* HTTP Rate Limit Testing */}
        <Card title="HTTP Rate Limit Testing">
          <p className="text-sm text-zinc-600 dark:text-zinc-400 mb-4">
            Send rapid requests to observe rate limiting behavior. The test stops when a 429
            response is received.
          </p>
          <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg p-4 mb-4">
            <h4 className="font-semibold text-blue-700 dark:text-blue-400 mb-2">
              Endpoint Configuration
            </h4>
            <p className="text-sm text-blue-900 dark:text-blue-200 mb-1">
              <code className="bg-blue-100 dark:bg-blue-800 px-1 rounded">/api/rate-limit-test</code>{" "}
              has its own rate limit:
            </p>
            <ul className="text-sm text-blue-800 dark:text-blue-300 list-disc list-inside">
              <li>100 requests per minute</li>
              <li>Burst capacity: 50</li>
            </ul>
          </div>

          {httpRateLimitRunning && (
            <div className="flex items-center gap-2 bg-blue-100 dark:bg-blue-900/30 rounded-lg p-4 mb-4">
              <div className="w-3 h-3 bg-green-500 rounded-full animate-pulse" />
              <span>
                Requests sent: <strong>{httpRateLimitLiveCount}</strong>
              </span>
            </div>
          )}

          {httpRateLimitStats.show && (
            <>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
                <StatBox
                  label="Successful"
                  value={httpRateLimitStats.successCount}
                  variant="success"
                />
                <StatBox label="Total Sent" value={httpRateLimitStats.totalCount} />
                <StatBox
                  label="Rate Limited At"
                  value={
                    httpRateLimitStats.rateLimitedAt ? `#${httpRateLimitStats.rateLimitedAt}` : "-"
                  }
                  variant="warning"
                />
                <StatBox label="Duration" value={`${httpRateLimitStats.duration}ms`} />
              </div>

              {Object.values(httpRateLimitStats.headers).some(Boolean) && (
                <div className="bg-zinc-50 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded-lg p-4 mb-4">
                  <h4 className="font-semibold text-zinc-900 dark:text-zinc-100 mb-2">
                    Last Response Headers
                  </h4>
                  <dl className="font-mono text-sm">
                    {Object.entries(httpRateLimitStats.headers).map(([key, value]) =>
                      value ? (
                        <div key={key} className="mb-1">
                          <dt className="text-zinc-500 inline">{key}: </dt>
                          <dd className="text-zinc-900 dark:text-zinc-100 inline">
                            {value}
                            {key === "X-RateLimit-Reset" &&
                              ` (${new Date(parseInt(value) * 1000).toLocaleTimeString()})`}
                          </dd>
                        </div>
                      ) : null
                    )}
                  </dl>
                </div>
              )}

              {httpRateLimitStats.requests.length > 0 && (
                <div className="max-h-48 overflow-y-auto bg-zinc-50 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded-lg">
                  <table className="w-full text-sm">
                    <thead className="bg-zinc-100 dark:bg-zinc-700 sticky top-0">
                      <tr>
                        <th className="px-4 py-2 text-left">#</th>
                        <th className="px-4 py-2 text-left">Status</th>
                        <th className="px-4 py-2 text-left">Remaining</th>
                        <th className="px-4 py-2 text-left">Time</th>
                      </tr>
                    </thead>
                    <tbody>
                      {httpRateLimitStats.requests.map((req) => (
                        <tr
                          key={req.requestNumber}
                          className="border-t border-zinc-200 dark:border-zinc-700"
                        >
                          <td className="px-4 py-2">{req.requestNumber}</td>
                          <td className="px-4 py-2">
                            <StatusBadge status={req.status} />
                          </td>
                          <td className="px-4 py-2">{req.rateLimitRemaining || "-"}</td>
                          <td className="px-4 py-2">{req.timestamp.toLocaleTimeString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}

          <div className="flex gap-2 mt-4">
            {!httpRateLimitRunning ? (
              <button
                onClick={startHttpRateLimitTest}
                className="px-4 py-2 bg-green-500 text-white rounded-lg hover:bg-green-600"
              >
                Start Test
              </button>
            ) : (
              <button
                onClick={stopHttpRateLimitTest}
                className="px-4 py-2 bg-red-500 text-white rounded-lg hover:bg-red-600"
              >
                Stop
              </button>
            )}
            {httpRateLimitStats.show && (
              <button
                onClick={() =>
                  setHttpRateLimitStats({
                    show: false,
                    successCount: 0,
                    totalCount: 0,
                    rateLimitedAt: null,
                    duration: 0,
                    headers: {},
                    requests: [],
                  })
                }
                className="px-4 py-2 bg-zinc-500 text-white rounded-lg hover:bg-zinc-600"
              >
                Clear
              </button>
            )}
          </div>
        </Card>

        {/* Auth Rate Limit Testing */}
        <Card title="Auth Rate Limit Testing (Brute Force Protection)">
          <p className="text-sm text-zinc-600 dark:text-zinc-400 mb-4">
            Test the authentication rate limiter by sending repeated failed login attempts. This
            simulates a brute force attack to verify lockout behavior.
          </p>
          <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg p-4 mb-4">
            <h4 className="font-semibold text-blue-700 dark:text-blue-400 mb-2">How It Works</h4>
            <ul className="text-sm text-blue-800 dark:text-blue-300 list-disc list-inside">
              <li>
                Sends requests to <code className="bg-blue-100 dark:bg-blue-800 px-1 rounded">/api/auth/fail</code>{" "}
                which always returns 401
              </li>
              <li>Aussie tracks failed attempts by IP and username</li>
              <li>
                After <strong>max attempts</strong> (default: 5), returns 429 with lockout
              </li>
              <li>Progressive lockout: each lockout increases duration (1.5x multiplier)</li>
            </ul>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-4">
            <div>
              <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                Test Username
              </label>
              <input
                type="text"
                value={authTestConfig.username}
                onChange={(e) => setAuthTestConfig({ ...authTestConfig, username: e.target.value })}
                className="w-full px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100 font-mono"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                Max Attempts
              </label>
              <input
                type="number"
                value={authTestConfig.maxAttempts}
                onChange={(e) =>
                  setAuthTestConfig({ ...authTestConfig, maxAttempts: parseInt(e.target.value) || 10 })
                }
                min={1}
                max={50}
                className="w-full px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100"
              />
            </div>
          </div>

          {authRateLimitRunning && (
            <div className="flex items-center gap-2 bg-blue-100 dark:bg-blue-900/30 rounded-lg p-4 mb-4">
              <div className="w-3 h-3 bg-green-500 rounded-full animate-pulse" />
              <span>
                Attempts: <strong>{authRateLimitLiveCount}</strong>
              </span>
            </div>
          )}

          {authRateLimitStats.show && (
            <>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
                <StatBox
                  label="Failed (401)"
                  value={authRateLimitStats.failedCount}
                  variant="danger"
                />
                <StatBox label="Total Sent" value={authRateLimitStats.totalCount} />
                <StatBox
                  label="Locked Out At"
                  value={
                    authRateLimitStats.lockedOutAt ? `#${authRateLimitStats.lockedOutAt}` : "-"
                  }
                  variant="warning"
                />
                <StatBox label="Retry After" value={authRateLimitStats.retryAfter || "-"} />
              </div>

              {authRateLimitStats.lockedOutAt &&
                Object.values(authRateLimitStats.headers).some(Boolean) && (
                  <div className="bg-zinc-50 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded-lg p-4 mb-4">
                    <h4 className="font-semibold text-zinc-900 dark:text-zinc-100 mb-2">
                      Lockout Response Headers
                    </h4>
                    <dl className="font-mono text-sm">
                      {Object.entries(authRateLimitStats.headers).map(([key, value]) =>
                        value ? (
                          <div key={key} className="mb-1">
                            <dt className="text-zinc-500 inline">{key}: </dt>
                            <dd className="text-zinc-900 dark:text-zinc-100 inline">
                              {value}
                              {key === "X-Auth-RateLimit-Reset" &&
                                ` (${new Date(parseInt(value) * 1000).toLocaleTimeString()})`}
                            </dd>
                          </div>
                        ) : null
                      )}
                    </dl>
                  </div>
                )}

              {authRateLimitStats.requests.length > 0 && (
                <div className="max-h-48 overflow-y-auto bg-zinc-50 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 rounded-lg">
                  <table className="w-full text-sm">
                    <thead className="bg-zinc-100 dark:bg-zinc-700 sticky top-0">
                      <tr>
                        <th className="px-4 py-2 text-left">#</th>
                        <th className="px-4 py-2 text-left">Status</th>
                        <th className="px-4 py-2 text-left">Response</th>
                        <th className="px-4 py-2 text-left">Time</th>
                      </tr>
                    </thead>
                    <tbody>
                      {authRateLimitStats.requests.map((req) => (
                        <tr
                          key={req.requestNumber}
                          className="border-t border-zinc-200 dark:border-zinc-700"
                        >
                          <td className="px-4 py-2">{req.requestNumber}</td>
                          <td className="px-4 py-2">
                            <StatusBadge status={req.status} />
                          </td>
                          <td className="px-4 py-2 max-w-[150px] truncate">{req.response}</td>
                          <td className="px-4 py-2">{req.timestamp.toLocaleTimeString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}

          <div className="flex gap-2 mt-4">
            {!authRateLimitRunning ? (
              <button
                onClick={startAuthRateLimitTest}
                className="px-4 py-2 bg-red-500 text-white rounded-lg hover:bg-red-600"
              >
                Start Brute Force Test
              </button>
            ) : (
              <button
                onClick={stopAuthRateLimitTest}
                className="px-4 py-2 bg-zinc-500 text-white rounded-lg hover:bg-zinc-600"
              >
                Stop
              </button>
            )}
            {authRateLimitStats.show && (
              <button
                onClick={() =>
                  setAuthRateLimitStats({
                    show: false,
                    failedCount: 0,
                    totalCount: 0,
                    lockedOutAt: null,
                    retryAfter: null,
                    headers: {},
                    requests: [],
                  })
                }
                className="px-4 py-2 bg-zinc-500 text-white rounded-lg hover:bg-zinc-600"
              >
                Clear
              </button>
            )}
          </div>
        </Card>
      </div>
    </div>
  );
}
