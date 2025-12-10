import Link from "next/link";

export default function Home() {
  return (
    <div className="min-h-screen bg-zinc-50 dark:bg-black p-8">
      <div className="max-w-3xl mx-auto">
        <header className="mb-12">
          <h1 className="text-4xl font-bold text-zinc-900 dark:text-zinc-100">
            Aussie Demo Service
          </h1>
          <p className="mt-3 text-lg text-zinc-600 dark:text-zinc-400">
            A sample application for testing Aussie API Gateway features.
          </p>
        </header>

        <div className="grid gap-6">
          {/* WebSocket Testing */}
          <Link
            href="/websocket"
            className="block p-6 bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-lg hover:border-blue-500 dark:hover:border-blue-500 transition-colors"
          >
            <div className="flex items-start justify-between">
              <div>
                <h2 className="text-xl font-semibold text-zinc-900 dark:text-zinc-100">
                  WebSocket Test
                </h2>
                <p className="mt-2 text-zinc-600 dark:text-zinc-400">
                  Test WebSocket proxy functionality with echo and chat endpoints.
                </p>
                <div className="mt-3 flex gap-2">
                  <span className="inline-block px-2 py-1 text-xs bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400 rounded">
                    /ws/echo (public)
                  </span>
                  <span className="inline-block px-2 py-1 text-xs bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400 rounded">
                    /ws/chat (auth)
                  </span>
                </div>
              </div>
              <span className="text-2xl">&#8594;</span>
            </div>
          </Link>

          {/* Authentication */}
          <Link
            href="/login"
            className="block p-6 bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-lg hover:border-blue-500 dark:hover:border-blue-500 transition-colors"
          >
            <div className="flex items-start justify-between">
              <div>
                <h2 className="text-xl font-semibold text-zinc-900 dark:text-zinc-100">
                  Login
                </h2>
                <p className="mt-2 text-zinc-600 dark:text-zinc-400">
                  Authenticate to test protected endpoints and WebSocket chat.
                </p>
                <div className="mt-3 flex gap-2">
                  <span className="inline-block px-2 py-1 text-xs bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400 rounded">
                    Session Cookie
                  </span>
                  <span className="inline-block px-2 py-1 text-xs bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400 rounded">
                    JWT Claims
                  </span>
                </div>
              </div>
              <span className="text-2xl">&#8594;</span>
            </div>
          </Link>

          {/* API Endpoints */}
          <div className="p-6 bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-lg">
            <h2 className="text-xl font-semibold text-zinc-900 dark:text-zinc-100">
              REST API Endpoints
            </h2>
            <p className="mt-2 text-zinc-600 dark:text-zinc-400">
              Available HTTP endpoints for testing.
            </p>
            <div className="mt-4 space-y-2">
              <div className="flex items-center gap-3 text-sm">
                <span className="px-2 py-1 bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400 rounded font-mono">
                  GET
                </span>
                <code className="text-zinc-700 dark:text-zinc-300">/api/health</code>
                <span className="text-zinc-500">- Health check (public)</span>
              </div>
              <div className="flex items-center gap-3 text-sm">
                <span className="px-2 py-1 bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400 rounded font-mono">
                  GET
                </span>
                <code className="text-zinc-700 dark:text-zinc-300">/api/users</code>
                <span className="text-zinc-500">- List users (public, auth passes claims)</span>
              </div>
              <div className="flex items-center gap-3 text-sm">
                <span className="px-2 py-1 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400 rounded font-mono">
                  POST
                </span>
                <code className="text-zinc-700 dark:text-zinc-300">/api/users</code>
                <span className="text-zinc-500">- Create user (public)</span>
              </div>
              <div className="flex items-center gap-3 text-sm">
                <span className="px-2 py-1 bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400 rounded font-mono">
                  GET
                </span>
                <code className="text-zinc-700 dark:text-zinc-300">/api/auth/test</code>
                <span className="text-zinc-500">- Auth test (public)</span>
              </div>
            </div>
          </div>
        </div>

        <footer className="mt-12 pt-6 border-t border-zinc-200 dark:border-zinc-800">
          <p className="text-sm text-zinc-500 dark:text-zinc-500">
            Access this service through Aussie at{" "}
            <code className="bg-zinc-100 dark:bg-zinc-900 px-2 py-1 rounded">
              http://localhost:1234/demo-service/...
            </code>
          </p>
        </footer>
      </div>
    </div>
  );
}
