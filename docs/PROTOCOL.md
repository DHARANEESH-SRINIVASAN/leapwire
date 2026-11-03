# Leapwire protocol, version 1

Leapwire removes request waterfalls. A client screen usually loads data as a chain of GET requests where each request needs a value from the previous answer (`/api/orders/42` returns `customerId: 19`, then the app asks for `/api/customers/19`). The server learns those value bindings from ordinary traffic and, once a binding is reliable, answers the first request with the whole chain in one streamed response. Application code on both sides stays unchanged.

This document is the contract between the Spring Boot starter and the npm client. Both must implement exactly this.

## 1. Request headers (client to server)

The client adds these to every GET request it sends to an allowed origin. Requests without `Leapwire-Page` are never learned from and never bundled.

| Header | Value | Meaning |
|---|---|---|
| `Leapwire-Accept` | `1` | The client can read a bundle response (section 4). Without it the server answers normally. |
| `Leapwire-Page` | 1 to 64 characters `[A-Za-z0-9_-]` | Random id of one screen visit. A new id on every page load and every client-side route change. The learner groups requests by this id. |
| `Leapwire-Route` | the client route, at most 256 characters | Where the request came from, for example `/orders/42` or a React Native screen name such as `OrderDetail`. The server normalises it (section 2.1) and learns rules per route. |

The server adds this header on requests it makes itself (section 3.3). Clients never send it.

| Header | Value | Meaning |
|---|---|---|
| `Leapwire-Speculative` | `1` | This request was made by Leapwire. It is served normally but is never learned from and never bundled. |

## 2. Learning (server)

### 2.1 Keys

- **Endpoint key:** the Spring MVC handler pattern (`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`), for example `/api/orders/{id}`. Requests that no handler pattern matches are ignored.
- **Route key:** `Leapwire-Route` with every path segment that is all digits, a UUID, or longer than 24 characters of `[A-Za-z0-9_-]` replaced by `{}`. `/orders/42` becomes `/orders/{}`.

### 2.2 What is recorded

For each GET request carrying `Leapwire-Page` and no `Leapwire-Speculative`, whose response status is 200 and whose content type is JSON (`application/json` or `+json`), at most 256 KiB:

- page id, route key, endpoint key, the path variables, the query parameters, arrival time;
- the response body parsed as JSON.

A page is kept for at most 30 seconds after its last request and at most 64 requests. At most 10,000 pages are kept in memory (least recently used evicted).

### 2.3 Value index

Every scalar in a recorded response is indexed by its text form: strings as they are, integers in decimal, booleans and `null` not indexed, strings shorter than 1 or longer than 128 characters not indexed. Each entry points to the response and a JSON path:

- `$.customerId`, `$.customer.id`: a single value.
- `$[*].productId`, `$.lines[*].productId`: every element of an array of objects (or of scalars, `$[*]`, `$.ids[*]`).

### 2.4 Bindings

When a request arrives on a page, each path variable and each query parameter value is looked up in the value index of the **earlier** responses on the same page:

- **Scalar binding:** the value equals a single-value path of an earlier response.
- **List binding:** a query parameter is a comma-separated list (`ids=501,502`) or repeated (`ids=501&ids=502`), and its values, in order, equal the values of an array path of an earlier response. The join mode (`comma` or `repeat`) is part of the binding.

A **candidate rule** is: `(route key, source endpoint key) → target`, where `target` is the target endpoint's path template, its query template, and the binding of **every** path variable and every bound query parameter to a JSON path of **one** source response. Query parameters that are not bound must have the same value every time to be part of the target (constant), otherwise the candidate is discarded. If several earlier responses could be the source, the most recent one is used.

### 2.5 Statistics and activation

Per `(route key, source endpoint key)`:

- `seen`: the number of distinct pages where the source endpoint was answered;
- per candidate rule, `followed`: the number of those pages where, after the source response, the target was requested with exactly the bound values.

A rule is **active** when `seen >= minPages` (default 10), the pages come from at least `minUsers` distinct users (default 3; the user is the authenticated principal name, otherwise the session id, otherwise the client address), and `followed / seen >= minConfidence` (default 0.9). Statistics decay: every count is multiplied by 0.5 every hour so rules follow changes in the app.

## 3. Speculation (server)

### 3.1 When

A GET request with `Leapwire-Accept: 1`, `Leapwire-Page`, no `Leapwire-Speculative`, whose `(route key, endpoint key)` has at least one active rule, and whose handler method and class are not annotated `@LeapwireSkip`. Everything else is served exactly as without Leapwire.

### 3.2 What

1. The primary request runs normally and its response body is captured.
2. If the primary status is not 200 or its body is not JSON, the server sends the primary response unchanged and stops.
3. For each active rule of the primary, the target URL is built by substituting the bound values from the primary body. A rule whose JSON path is missing or `null` in this body is skipped. A list binding with zero values is skipped.
4. Each target response is itself treated as a source: rules for `(same route key, target endpoint key)` add further targets (depth up to 4, counting the primary as 0).
5. Duplicate URLs are executed once. At most `maxRequests` (default 8) speculative requests per primary, and the whole bundle ends at `deadline` (default 2 seconds after the primary finished). Targets whose endpoint is annotated `@LeapwireSkip` are never executed.

### 3.3 How a speculative request is executed

As a real HTTP GET from the server to itself (`http://127.0.0.1:{local port}{context path}{target}`), so every servlet filter, Spring Security rule and argument resolver runs exactly as for a request from the client. It copies from the primary request: `Authorization`, `Cookie`, `Accept-Language`, `Leapwire-Page`, `Leapwire-Route`, and sets `Accept: application/json` and `Leapwire-Speculative: 1`. It never copies `Leapwire-Accept`. Independent targets run concurrently.

## 4. Bundle response (server to client)

When at least one target is planned, the response is:

- status `200`, `Content-Type: application/x-ndjson`, header `Leapwire: bundle`, `Cache-Control: no-store`, `Vary: Leapwire-Accept`;
- a body of UTF-8 lines, each one JSON object, flushed as soon as it is written:

```
{"t":"main","status":200,"headers":{"content-type":"application/json"},"body":{...}}
{"t":"plan","urls":["/api/customers/19","/api/orders/42/lines"]}
{"t":"part","url":"/api/customers/19","status":200,"headers":{"content-type":"application/json"},"body":{...}}
{"t":"plan","urls":["/api/products?ids=501,502"]}
{"t":"part","url":"/api/orders/42/lines","status":200,"headers":{"content-type":"application/json"},"body":[...]}
{"t":"part","url":"/api/products?ids=501,502","status":200,"headers":{"content-type":"application/json"},"body":[...]}
{"t":"end","parts":3,"skipped":0}
```

Rules:

- The first line is always `main`: the primary response's status, its `content-type`, `etag`, `last-modified` and `cache-control` headers if present, and its body as a JSON value.
- `plan` lines announce URLs that will follow as `part` lines. A URL appears in a `plan` before its `part`. Plans can come in several lines as deeper targets are discovered.
- `part` lines arrive in completion order. `url` is the path plus query string exactly as planned. A part whose speculative request failed, timed out or returned a non-JSON body is sent as `{"t":"part","url":"...","status":0}` so the client stops waiting for it.
- The last line is `end` with the number of parts sent with status 200 and the number skipped.
- When no target is planned, the server sends the primary response unchanged, not a bundle.

## 5. Client behaviour

- Only GET requests to allowed origins (default: the page's own origin) are touched. Every other request passes through unchanged.
- **Cache of pushed parts:** keyed by absolute URL. A part with status 200 is kept for `ttl` (default 10 seconds) and served **once**; the next request for the same URL goes to the network.
- **Waiting for planned parts:** a request for a URL that is announced in a `plan` of a bundle still being read waits for that part instead of going to the network, until the part arrives, the bundle ends, or `ttl` passes. A part with status 0 makes the waiting request go to the network.
- **Answering the original request:** the `main` line becomes a normal `Response` (status, the listed headers, the body serialized as JSON) and is returned as soon as the line is read. The rest of the bundle is read in the background.
- **Page and route:** in browsers the page id changes on load and on every `history.pushState`, `history.replaceState` and `popstate`; the route is `location.pathname`. Applications without `location` (React Native) call `setRoute(name)`, which also starts a new page id.
- **Environments without streaming response bodies** (React Native's default `fetch`) read the whole bundle, then process it. The result is still one network round trip.
- `clear()` empties the cache, for example on logout. `uninstall()` restores the original `fetch`.

## 6. Security properties

- Speculative requests carry the same credentials as the request that caused them and pass through the same filters and authorization rules, so a bundle never contains anything the client could not fetch itself.
- Only GET requests are speculated, and `@LeapwireSkip` excludes endpoints with side effects or that must not be prefetched.
- Rules activate only after `minPages` pages from `minUsers` distinct users, so one client cannot make the server speculate for everyone.
- Pushed responses live only in the memory of the client that received them.
