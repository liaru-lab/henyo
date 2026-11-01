# HTTP transport admission

HTTP requests use CRLF framing and HTTP/1.0 or HTTP/1.1. Request lines and
individual header lines are limited to 8192 bytes excluding CRLF. Headers are
limited to 100 fields and 32768 bytes including CRLF. Bodies are limited to
1048576 bytes. Limits are enforced before body allocation.

One absolute five-second read deadline covers the request line, headers, and
body. Sending another byte does not renew it. This transport deadline is
separate from the operation's admission budget and does not limit a successfully
upgraded WebSocket session. Buffered upgrade bytes remain available to its frame
reader.

The reader requires exact body lengths. Invalid or repeated Content-Length,
repeated Authorization or Host, unsupported Transfer-Encoding, folded headers,
truncation, and malformed framing return `malformed_request` (400). Oversized
request lines return `request_line_too_large` (414); excessive headers return
`headers_too_large` (431); oversized bodies return `body_too_large` (413).
Read expiry returns `request_timeout` (408). Errors include
`outcome:"not_applied"` and contain no request data. Connections close after the
response; HTTP pipelining is not supported.

These limits do not change source access, pairing, or authorization policy.
