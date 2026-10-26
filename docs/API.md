# API reference

Base URL: `http://localhost:8080` (configurable with `shortener.base-url`). All request and response bodies are JSON. Timestamps are ISO-8601 UTC. The live OpenAPI document is served at `/v3/api-docs` and rendered at `/swagger-ui.html`.

| Method | Path | Purpose | Success |
|---|---|---|---|
| `POST` | `/api/v1/urls` | Create a short link | `201` new, `200` reused |
| `GET` | `/{code}` | Follow a short link | `302` |
| `GET` | `/api/v1/urls/{code}` | Link details | `200` |
| `GET` | `/api/v1/urls/{code}/stats` | Click analytics | `200` |
| `DELETE` | `/api/v1/urls/{code}` | Soft-delete a link | `204` |
| `GET` | `/actuator/health` | Liveness/readiness | `200` |
| `GET` | `/actuator/metrics/{name}` | Micrometer metrics | `200` |

---

## Create a short link

`POST /api/v1/urls`

| Field | Type | Required | Rules |
|---|---|---|---|
| `url` | string | yes | `http` or `https`, has a host, at most 2048 chars, no embedded `user:pass@`, not localhost/private-network/`.local`, not this service's own host. Scheme and host are lower-cased; path and query are kept as sent. |
| `alias` | string | no | 3 to 32 chars of `[A-Za-z0-9_-]`. Reserved names (`api`, `actuator`, `swagger-ui`, `h2-console`, `admin`, `error`, ...) are refused. |
| `ttlSeconds` | integer | no | Positive. Expiry relative to now. |
| `expiresAt` | timestamp | no | Must be in the future. Give **either** `ttlSeconds` or `expiresAt`. |

```bash
curl -s -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/articles/2026/10/how-we-scaled","alias":"launch-2026"}'
```

```http
HTTP/1.1 201
Location: /api/v1/urls/launch-2026
Content-Type: application/json

{
  "code": "launch-2026",
  "shortUrl": "http://localhost:8080/launch-2026",
  "originalUrl": "https://example.com/articles/2026/10/how-we-scaled",
  "customAlias": true,
  "createdAt": "2026-10-24T15:36:49.659834667Z",
  "expiresAt": null
}
```

**Idempotency.** Without `alias` and without expiry, shortening a URL that already has a permanent generated link returns that link with `200 OK` instead of creating a new one.

| Status | When |
|---|---|
| `201` | Created. `Location` points at the details resource. |
| `200` | Existing permanent link for the same URL reused. |
| `400` | Invalid JSON, failed field validation, unsafe URL, malformed or reserved alias, both expiry forms, expiry in the past. |
| `409` | Alias already in use (including a concurrent request that won the race). |
| `429` | Per-client rate limit exceeded; `Retry-After` header gives seconds to wait. |
| `503` | No free code found within the bounded number of attempts (keyspace pressure; widen `code-length`). |

## Follow a short link

`GET /{code}`

```http
HTTP/1.1 302
Location: https://example.com/articles/2026/10/how-we-scaled
Cache-Control: no-store
```

`Referer` and `User-Agent` are recorded for analytics (truncated to 512 chars). The client IP is not stored.

| Status | When |
|---|---|
| `302` | Active link. The click is recorded asynchronously and never delays or fails the redirect. |
| `404` | Unknown or deleted code. |
| `410` | Expired link. Not counted as a click. |

## Link details

`GET /api/v1/urls/{code}` returns the same shape as the create response. `404` for unknown or deleted codes. Expired links are still returned (check `expiresAt`).

## Click analytics

`GET /api/v1/urls/{code}/stats`

```json
{
  "code": "launch-2026",
  "originalUrl": "https://example.com/articles/2026/10/how-we-scaled",
  "totalClicks": 1,
  "createdAt": "2026-10-24T15:36:49.659835Z",
  "lastAccessedAt": "2026-10-24T15:36:49.929923Z",
  "expiresAt": null,
  "expired": false,
  "clicksByDay": [{ "date": "2026-10-24", "clicks": 1 }],
  "topReferrers": [{ "referrer": "https://news.example", "clicks": 1 }]
}
```

| Field | Meaning |
|---|---|
| `totalClicks` | All-time successful redirects (atomic counter). |
| `clicksByDay` | UTC days with at least one click within the last `shortener.analytics.recent-days` (default 30), ascending. |
| `topReferrers` | Up to 5 referrers by click count. Clicks without a `Referer` are counted in totals but not here. |
| `expired` | Whether the link is past `expiresAt` right now. |

Because recording is asynchronous, a click can take a few milliseconds to appear in stats.

## Delete a link

`DELETE /api/v1/urls/{code}` returns `204`. The link stops redirecting immediately (its cache entry is evicted), its analytics are kept, and its code is never reused. A second delete returns `404`.

---

## Error model

Every error is an [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) `application/problem+json` document with a stable `title`:

```json
{
  "type": "about:blank",
  "title": "Invalid request",
  "status": 400,
  "detail": "only http and https URLs can be shortened",
  "instance": "/api/v1/urls"
}
```

| `title` | Status |
|---|---|
| `Invalid request` | 400 |
| `Short URL not found` | 404 |
| `Alias already in use` | 409 |
| `Short URL expired` | 410 |
| `Too many requests` | 429 |
| `Temporarily unable to allocate a code` | 503 |

Field validation errors list every failing field in `detail`, e.g. `"ttlSeconds: must be greater than 0; url: must not be blank"`.

## Rate limiting

Only `POST /api/v1/urls` is limited, per client socket address, with a token bucket: `capacity` requests of burst (default 20) refilled at `refill-per-minute` (default 60). `X-Forwarded-For` is ignored because clients can forge it; behind a trusted proxy set `server.forward-headers-strategy=native` so the real client address is used.

## Metrics

| Metric | Tags | Meaning |
|---|---|---|
| `shortener.urls.created` | `type=generated\|alias` | Links created. |
| `shortener.urls.reused` | | Create calls answered with an existing link. |
| `shortener.urls.deleted` | | Soft deletes. |
| `shortener.urls.generation_exhausted` | | Code allocation gave up (alert on any non-zero value). |
| `shortener.redirects` | `outcome=ok\|not_found\|expired` | Redirect outcomes. |
| `shortener.analytics.failures` | | Click events that could not be recorded. |

Plus the standard Spring Boot metrics (`http.server.requests`, JVM, Hikari connection pool).
