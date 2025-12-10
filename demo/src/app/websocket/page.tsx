"use client";

import { useState, useRef, useCallback, useEffect } from "react";

interface Message {
  id: string;
  direction: "sent" | "received" | "system";
  content: string;
  timestamp: Date;
}

interface WebSocketPanelProps {
  title: string;
  endpoint: string;
  description: string;
  requiresAuth?: boolean;
}

function WebSocketPanel({
  title,
  endpoint,
  description,
  requiresAuth = false,
}: WebSocketPanelProps) {
  const [connected, setConnected] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputValue, setInputValue] = useState("");
  const [error, setError] = useState<string | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  const addMessage = useCallback(
    (direction: Message["direction"], content: string) => {
      setMessages((prev) => [
        ...prev,
        {
          id: `${Date.now()}-${Math.random()}`,
          direction,
          content,
          timestamp: new Date(),
        },
      ]);
    },
    []
  );

  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      return;
    }

    setConnecting(true);
    setError(null);

    // Connect through Aussie gateway (pass-through mode)
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const host = window.location.hostname;
    const port = "1234"; // Aussie port
    const wsUrl = `${protocol}//${host}:${port}/demo-service${endpoint}`;

    addMessage("system", `Connecting to ${wsUrl}...`);

    const ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      setConnected(true);
      setConnecting(false);
      addMessage("system", "Connected!");
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        addMessage("received", JSON.stringify(data, null, 2));
      } catch {
        addMessage("received", event.data);
      }
    };

    ws.onerror = (event) => {
      console.error("WebSocket error:", event);
      setError("Connection error occurred");
      addMessage("system", "Error: Connection failed");
    };

    ws.onclose = (event) => {
      setConnected(false);
      setConnecting(false);
      wsRef.current = null;
      addMessage(
        "system",
        `Disconnected: ${event.code} ${event.reason || "(no reason)"}`
      );
    };

    wsRef.current = ws;
  }, [endpoint, addMessage]);

  const disconnect = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.close(1000, "User disconnected");
      wsRef.current = null;
    }
  }, []);

  const sendMessage = useCallback(() => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
      setError("Not connected");
      return;
    }

    if (!inputValue.trim()) {
      return;
    }

    wsRef.current.send(inputValue);
    addMessage("sent", inputValue);
    setInputValue("");
  }, [inputValue, addMessage]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
      }
    },
    [sendMessage]
  );

  const clearMessages = useCallback(() => {
    setMessages([]);
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (wsRef.current) {
        wsRef.current.close();
      }
    };
  }, []);

  return (
    <div className="flex flex-col h-full border border-zinc-200 dark:border-zinc-800 rounded-lg overflow-hidden">
      {/* Header */}
      <div className="bg-zinc-100 dark:bg-zinc-900 px-4 py-3 border-b border-zinc-200 dark:border-zinc-800">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-lg font-semibold text-zinc-900 dark:text-zinc-100">
              {title}
            </h2>
            <p className="text-sm text-zinc-600 dark:text-zinc-400">
              {description}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <span
              className={`inline-block w-3 h-3 rounded-full ${
                connected
                  ? "bg-green-500"
                  : connecting
                  ? "bg-yellow-500 animate-pulse"
                  : "bg-red-500"
              }`}
            />
            <span className="text-sm text-zinc-600 dark:text-zinc-400">
              {connected ? "Connected" : connecting ? "Connecting..." : "Disconnected"}
            </span>
          </div>
        </div>
        {requiresAuth && (
          <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
            Requires authentication - log in first at /login
          </p>
        )}
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto p-4 space-y-2 bg-white dark:bg-black min-h-[300px] max-h-[400px]">
        {messages.length === 0 ? (
          <p className="text-center text-zinc-400 dark:text-zinc-600 text-sm">
            No messages yet. Connect to start.
          </p>
        ) : (
          messages.map((msg) => (
            <div
              key={msg.id}
              className={`text-sm ${
                msg.direction === "sent"
                  ? "text-right"
                  : msg.direction === "system"
                  ? "text-center"
                  : "text-left"
              }`}
            >
              <span
                className={`inline-block px-3 py-2 rounded-lg max-w-[80%] ${
                  msg.direction === "sent"
                    ? "bg-blue-500 text-white"
                    : msg.direction === "system"
                    ? "bg-zinc-200 dark:bg-zinc-800 text-zinc-600 dark:text-zinc-400 text-xs"
                    : "bg-zinc-100 dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100"
                }`}
              >
                <pre className="whitespace-pre-wrap font-mono text-xs">
                  {msg.content}
                </pre>
              </span>
              <div className="text-xs text-zinc-400 mt-1">
                {msg.timestamp.toLocaleTimeString()}
              </div>
            </div>
          ))
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Error */}
      {error && (
        <div className="px-4 py-2 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 text-sm">
          {error}
        </div>
      )}

      {/* Input */}
      <div className="border-t border-zinc-200 dark:border-zinc-800 p-4 bg-zinc-50 dark:bg-zinc-950">
        <div className="flex gap-2">
          <input
            type="text"
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Type a message..."
            disabled={!connected}
            className="flex-1 px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100 disabled:opacity-50 disabled:cursor-not-allowed"
          />
          <button
            onClick={sendMessage}
            disabled={!connected || !inputValue.trim()}
            className="px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Send
          </button>
        </div>
        <div className="flex gap-2 mt-3">
          {!connected ? (
            <button
              onClick={connect}
              disabled={connecting}
              className="px-4 py-2 bg-green-500 text-white rounded-lg hover:bg-green-600 disabled:opacity-50"
            >
              {connecting ? "Connecting..." : "Connect"}
            </button>
          ) : (
            <button
              onClick={disconnect}
              className="px-4 py-2 bg-red-500 text-white rounded-lg hover:bg-red-600"
            >
              Disconnect
            </button>
          )}
          <button
            onClick={clearMessages}
            className="px-4 py-2 border border-zinc-300 dark:border-zinc-700 text-zinc-700 dark:text-zinc-300 rounded-lg hover:bg-zinc-100 dark:hover:bg-zinc-800"
          >
            Clear
          </button>
        </div>
      </div>
    </div>
  );
}

export default function WebSocketTestPage() {
  return (
    <div className="min-h-screen bg-zinc-50 dark:bg-black p-8">
      <div className="max-w-6xl mx-auto">
        <header className="mb-8">
          <h1 className="text-3xl font-bold text-zinc-900 dark:text-zinc-100">
            WebSocket Test
          </h1>
          <p className="mt-2 text-zinc-600 dark:text-zinc-400">
            Test Aussie&apos;s WebSocket proxy functionality with the demo service endpoints.
          </p>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-500">
            Connections go through Aussie at port 1234, which proxies to the demo service.
          </p>
        </header>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <WebSocketPanel
            title="Echo (Public)"
            endpoint="/ws/echo"
            description="Send any message and receive it back. No authentication required."
            requiresAuth={false}
          />
          <WebSocketPanel
            title="Chat (Authenticated)"
            endpoint="/ws/chat"
            description="Chat room that requires authentication. Messages are broadcast to all connected users."
            requiresAuth={true}
          />
        </div>

        <div className="mt-8 p-4 bg-zinc-100 dark:bg-zinc-900 rounded-lg">
          <h3 className="font-semibold text-zinc-900 dark:text-zinc-100 mb-2">
            How it works
          </h3>
          <ul className="text-sm text-zinc-600 dark:text-zinc-400 space-y-1 list-disc list-inside">
            <li>
              <strong>Echo:</strong> Public WebSocket endpoint. Aussie proxies the
              connection without authentication.
            </li>
            <li>
              <strong>Chat:</strong> Authenticated WebSocket endpoint. Aussie validates
              your session cookie, then forwards a short-lived JWT with your claims to
              the backend.
            </li>
            <li>
              WebSocket connections are proxied through{" "}
              <code className="bg-zinc-200 dark:bg-zinc-800 px-1 rounded">
                ws://localhost:1234/demo-service/ws/*
              </code>
            </li>
            <li>
              To test authenticated endpoints, first{" "}
              <a
                href="/login"
                className="text-blue-500 hover:underline"
              >
                log in here
              </a>
              .
            </li>
          </ul>
        </div>
      </div>
    </div>
  );
}
