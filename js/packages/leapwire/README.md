# leapwire

Remove request waterfalls without changing application code. A screen usually loads its data as a chain of GET requests where each one needs a value from the previous answer: `/api/orders/42` returns `customerId: 19`, then the app asks for `/api/customers/19`. The Leapwire Spring Boot starter learns those chains from ordinary traffic and answers the first request with the whole chain in one streamed response. This package is the client half: a drop-in `fetch` that sends the headers the server needs, hands your code the first response as soon as it arrives, and answers the follow-up requests from what the server already pushed.

Your code keeps calling `fetch` (or a client built on it) exactly as before. A server without Leapwire, a request Leapwire does not handle and a response that is not a bundle all behave as if this package were not there.

```sh
npm install leapwire
```

No runtime dependencies. The server side and the wire protocol are in the [main repository](https://github.com/DHARANEESH-SRINIVASAN/leapwire).

## Browsers

Call `install()` once, before the app makes its first request:

```ts
import { install } from "leapwire";

install();
```

`install()` replaces `globalThis.fetch` with a Leapwire fetch over the original one and watches `history.pushState`, `history.replaceState` and `popstate`, so every client-side route change starts a new page visit. The route sent to the server is `location.pathname`.

It returns the installed fetch, which also has `stats()`, `clear()`, `setRoute()` and `uninstall()`:

```ts
const leapwire = install();

function logout() {
  leapwire.clear();
}

leapwire.uninstall();
```

`uninstall()` puts the original `fetch` and `history` methods back. If another library wrapped `fetch` after Leapwire, its wrapper is left in place and Leapwire inside it stops touching requests.

## React Native and other environments

Without `location` and `history`, tell Leapwire which origins to handle and which screen is showing. `setRoute(name)` sets the route and starts a new page visit:

```tsx
import { NavigationContainer, createNavigationContainerRef } from "@react-navigation/native";
import { createFetch } from "leapwire";

export const leapwire = createFetch(fetch, { origins: ["https://api.example.com"] });

const navigation = createNavigationContainerRef();

export function App() {
  return (
    <NavigationContainer
      ref={navigation}
      onReady={() => leapwire.setRoute(navigation.getCurrentRoute()?.name ?? "")}
      onStateChange={() => leapwire.setRoute(navigation.getCurrentRoute()?.name ?? "")}
    >
      <RootStack />
    </NavigationContainer>
  );
}
```

Use `leapwire` wherever the app calls `fetch`, or call `install({ origins: [...] })` instead of `createFetch` to replace the global `fetch`. It returns the same `setRoute`.

React Native's default `fetch` has no streaming response body, so Leapwire reads the whole bundle and then processes it. It is still one network round trip.

`createFetch` never touches globals. In a browser, using `createFetch` instead of `install()` means calling `setRoute` on route changes yourself.

## axios

axios with `adapter: "fetch"` looks up the global `fetch` on every request in versions 1.7.0 to 1.11.x and 1.12.2 and later, so `install()` covers it, including instances created before `install()` ran. Versions 1.12.0 and 1.12.1 keep the `fetch` that existed when axios was first imported and miss a later `install()`; upgrade them. axios's default adapter in browsers is XMLHttpRequest, which Leapwire does not see. If you pass `env: { fetch }` to axios, pass the Leapwire fetch there.

```ts
import axios from "axios";
import { install } from "leapwire";

install();
export const api = axios.create({ adapter: "fetch" });
```

## Options

`createFetch(base?, options?)` and `install(options?)` take the same options.

| Option | Default | Meaning |
|---|---|---|
| `origins` | `[location.origin]` when `location` exists, otherwise none | Origins whose GET requests Leapwire handles. Without `location`, nothing is handled until you list origins. |
| `ttl` | `10000` | Milliseconds a pushed part is kept, and the longest a request waits for a part the server announced. |
| `route` | `location.pathname` | A function that returns the current route. `setRoute()` overrides it once called. |
| `buffered` | `"auto"` | `"auto"` streams when `response.body` is a `ReadableStream` and reads the whole text otherwise. `true` always reads the whole text. |

`createFetch` uses `globalThis.fetch` when no `base` is given.

## What is touched and what is not

- **Touched:** GET requests to an allowed origin. They get three extra headers, `Leapwire-Accept: 1`, `Leapwire-Page` (a random id of the current page visit) and `Leapwire-Route`. The caller's other headers, signal, credentials and every other option are kept.
- **Not touched:** every other method, every other origin, and requests made after `uninstall()`. They go to the original `fetch` with exactly the arguments you passed.
- **Responses that are not bundles** (no `Leapwire: bundle` header) are returned as the original `Response` object.
- **Bundles:** the first response becomes a real `Response` with its status, `content-type`, `etag`, `last-modified` and `cache-control` headers, and its JSON body as the server wrote it, including integers beyond 2^53. It is returned as soon as its line arrives, and the rest of the bundle is read in the background.
- **Pushed parts** are kept in memory, per tab or app, keyed by absolute URL, and each is served **once**. The next request for that URL goes to the network. A request for a URL the server announced but has not sent yet waits for it, up to `ttl`. A part the server could not produce sends the waiting request to the network.
- **Failures:** if a bundle is cut or malformed, the original request still resolves. When its first response never arrived, Leapwire repeats it as a plain request, and requests that were waiting go to the network. An aborted signal rejects with the abort reason, as `fetch` does.

`stats()` returns counts of bundles received, parts received, `hits` served from the cache, `waits` answered by a part that arrived later, `misses` that went to the network, and parts `expired` unused after `ttl`. `clear()` empties the cache and makes bundles still in flight stop filling it. Call it on logout.

## Cross-origin APIs

When the API is on another origin, the browser sends a CORS preflight for the extra request headers, and only exposes the `Leapwire` response header if the server allows it. The server must send `Access-Control-Allow-Headers: Leapwire-Accept, Leapwire-Page, Leapwire-Route` and `Access-Control-Expose-Headers: Leapwire`. React Native does not apply CORS.

## License

Apache-2.0
