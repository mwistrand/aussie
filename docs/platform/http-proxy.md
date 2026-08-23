# HTTP proxy compatibility

Gateway and pass-through requests use the same Vert.x streaming exchange. Request and response bodies are never aggregated by the proxy.

| Behavior | Support |
|---|---|
| HTTP/1.1 and HTTP/2 | Supported by Vert.x negotiation; clear-text HTTP/2 upgrade is not forced. |
| Request/response streaming | Demand-driven with 64 KiB upstream write queues and configured byte limits. |
| HEAD, 1xx, 204, 205, 304 | Response bodies are drained but never sent to the client. |
| Chunked bodies | Supported and counted while streaming; framing headers are regenerated per connection. |
| `Expect: 100-continue` | Passed to Vert.x; Aussie does not synthesize an interim response. |
| Range and cache validators | Preserved end to end. |
| Content encoding | Preserved; Aussie does not compress, decompress, or transform bodies. |
| Cookies | Repeated `Set-Cookie` fields remain separate. |
| Trailers | Not forwarded. |
| Upgrade | Removed from HTTP proxy traffic; WebSockets use the WebSocket gateway. |
| Redirects and retries | Disabled. Requests are attempted once, including non-idempotent requests. |
| `Host` | Rebuilt from the upstream authority, including brackets for IPv6 literals. |
| Forwarding headers | Caller-supplied values are removed and rebuilt from the canonical client context. |
| Hop-by-hop headers | Standard fields and fields nominated by `Connection` are removed in both directions. |

Client cancellation resets the upstream request. Timeouts return RFC 9457 `504` responses, connection failures return `502`, body limits return `413`, and exhausted instance capacity returns `503`; internal exception details are not returned.

The managed client caps connections per upstream, bounds each upstream's acquisition queue, and applies a fail-fast instance-wide active-exchange limit. Tune `aussie.resiliency.http.max-connections-per-host` and `aussie.resiliency.http.max-connections` together.
