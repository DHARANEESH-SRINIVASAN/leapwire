# Leapwire

[![CI](https://github.com/DHARANEESH-SRINIVASAN/leapwire/actions/workflows/ci.yml/badge.svg)](https://github.com/DHARANEESH-SRINIVASAN/leapwire/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Screens that load in one round trip instead of six, without changing your code.** Leapwire is a Spring Boot starter and a small npm package. The server learns, from ordinary traffic, which API calls a screen makes after each other, and once it is sure, it answers the first call with the whole chain in one streamed response.

## The problem: request waterfalls

Most screens load their data as a chain, because each request needs a value from the previous answer:

```
GET /api/session              → { "userId": 1 }
GET /api/users/1              → { "latestOrderId": 43 }
GET /api/orders/43            → { "customerId": 20 }
GET /api/customers/20         ┐
GET /api/orders/43/lines      ┘ → [ { "productId": 503 } ]
GET /api/products?ids=503
```

Every step waits a full network round trip. On a phone at 150 ms that is close to a second before the screen has its data. The usual fixes, a hand-written aggregate endpoint, GraphQL or server components, all mean rewriting the screen or the API.

## What Leapwire does

1. **Learns.** A servlet filter watches GET requests that carry the client's page header. When a value from one JSON response (`$.customerId`, or every `$[*].productId`) shows up in the next request's path or query, it records a binding per screen. After 10 pages from 3 different users agree, 90% of the time, the rule switches on.
2. **Speculates.** The next time a screen asks for `/api/orders/43`, the server reads `customerId: 20` from the answer and fetches `/api/customers/20` itself, then follows the chain (4 levels, 8 requests, 2 seconds at most). Each speculative request is a real HTTP request to the same server with the caller's credentials, so Spring Security and every filter run exactly as if the browser had asked.
3. **Streams.** The first answer goes out immediately, followed by each learned answer the moment it is ready, as NDJSON lines ([protocol](docs/PROTOCOL.md)).
4. **Serves from memory.** The npm package wraps `fetch`: it returns the first answer to your code as a normal `Response` and keeps the others for a few seconds. When your code asks for `/api/customers/20`, the answer is already there.

Your controllers and your React code stay exactly as they are.

## Results

Measured in headless Chrome against the [example app](leapwire-example): the 6-call order screen above, Spring Security with HTTP basic, H2, 25 ms of work per handler. Network delay comes from a TCP proxy that adds half the round trip in each direction, and Chrome is throttled to 1.6 Mbps down and 750 kbps up. Medians of 15 page loads per row, alternating two users, after 12 training loads (i7-1255U, JDK 25.0.5, Chrome 156, default settings, `bench/run.mjs`).

| Round trip | Without Leapwire | With Leapwire | Faster | Requests |
|---:|---:|---:|---:|---:|
| 40 ms | 382 ms | **187 ms** | 2.0× | 6 → **1** |
| 150 ms | 945 ms | **294 ms** | 3.2× | 6 → **1** |
| 300 ms | 1,687 ms | **443 ms** | 3.8× | 6 → **1** |

Every one of the 90 Leapwire page loads showed exactly the data the same user gets without Leapwire, and all 75 pushed responses were used: no speculative request was wasted. The gain grows with the depth of the chain and the latency of the network. A screen whose requests are already independent, or a fast office network, gains much less.

## Quick start

Leapwire is not on Maven Central or npm yet. Build it from source with `mvn install` and `cd js && npm ci && npm run build`.

### Spring Boot

Add `io.github.dharaneesh-srinivasan:leapwire-spring-boot-starter` to a Spring MVC application. It configures itself and does nothing for requests that do not come from the client package.

Mark endpoints that must never be fetched ahead of time, for example ones with side effects on GET:

```java
@LeapwireSkip
@GetMapping("/api/orders/{id}/views")
Views views(@PathVariable long id) { ... }
```

### Browser and React

```ts
import { install } from "leapwire";

install();
```

`install()` wraps the global `fetch`, so code using `fetch` directly, TanStack Query, SWR, or axios with `adapter: "fetch"` is covered. Every request stays untouched except same-origin GETs, which get three headers.

### React Native

```ts
import { createFetch } from "leapwire";

const fetch = createFetch(globalThis.fetch, { origins: ["https://api.example.com"] });
fetch.setRoute("OrderDetail");
```

Call `setRoute` on every screen change. React Native's `fetch` cannot stream response bodies, so the bundle is read in one piece; it is still one round trip. See the [package README](js/packages/leapwire/README.md) for every option.

## Safety

- **Same credentials, same rules.** Speculative requests carry the caller's `Authorization` and `Cookie` headers and go through the real filter chain. A bundle never contains anything the user could not fetch: a target the user may not read comes back as a `403` part with no body (tested with two users in the example app).
- **GET only.** Nothing else is ever speculated, and `@LeapwireSkip` excludes an endpoint completely.
- **One client cannot train it.** A rule needs pages from at least 3 distinct users (principal, session or address) and decays every hour.
- **Pushed answers stay on the client that asked,** in memory, served once, for 10 seconds. `clear()` drops them, for example on logout.
- **No overhead for everyone else.** A request without the client's headers gets byte-for-byte the response it gets without Leapwire (tested on the raw socket).

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `leapwire.enabled` | `true` | Switch it off completely |
| `leapwire.min-pages` | `10` | Pages that must show a binding before it is used |
| `leapwire.min-users` | `3` | Distinct users among those pages |
| `leapwire.min-confidence` | `0.9` | Share of pages that must follow the binding |
| `leapwire.max-depth` | `4` | Levels followed after the first request |
| `leapwire.max-requests` | `8` | Speculative requests per bundle |
| `leapwire.deadline` | `2s` | The bundle ends this long after the first answer |
| `leapwire.page-ttl` | `30s` | How long a page's requests are kept for learning |
| `leapwire.max-pages` | `10000` | Pages kept in memory for learning |
| `leapwire.max-body-bytes` | `262144` | Larger JSON responses are not learned or bundled |

## Limits

- Spring MVC on a servlet container, plain HTTP between the server and itself. With `server.ssl` enabled Leapwire stays off and logs why. WebFlux is not supported.
- JSON responses to GET requests only. Rules are learned per screen route, so screens that branch differently between users only get the parts they share.
- Speculative requests see `127.0.0.1` as the client address and no forwarded headers. Mark endpoints that depend on them with `@LeapwireSkip`.
- Tested on Tomcat with Spring Boot 4.1.2, on JDK 17 and 25. Jetty, HTTP/2 and Spring Session have not been tested yet.

## Run it yourself

```sh
mvn install -DskipTests
cd js && npm ci && npm run build && cd ..
mkdir -p bench/web/leapwire && cp js/packages/leapwire/dist/*.js bench/web/leapwire/
cd leapwire-example
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" io.github.dharaneeshsrinivasan.leapwire.example.ExampleApplication \
  --server.port=8099 --example.delay=25ms --spring.web.resources.static-locations=file:../bench/web/
```

In two more terminals (the benchmark needs Node 22.4 or newer and Chrome, and has no dependencies: it drives Chrome over the DevTools protocol):

```sh
cd bench && LISTEN=8098 TARGET=8099 node rtt-proxy.mjs
cd bench && node run.mjs
```

Set `CHROME` to the Chrome binary if it is not `/usr/bin/google-chrome`.

## Tests

- **Server:** 45 tests on a real Tomcat: the exact bundle lines after learning, activation thresholds, list bindings in every join mode, depth and request limits, the deadline, `@LeapwireSkip`, context paths, byte-identical passthrough, and Spring Security isolation between two users.
- **Client:** 73 tests: header injection, streaming across random chunk boundaries (property-based), serve-once cache, waiting for planned parts, timeouts, aborts, malformed bundles, buffered mode for React Native, install and uninstall.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md) and the [changelog](CHANGELOG.md).

## License

Apache License 2.0
