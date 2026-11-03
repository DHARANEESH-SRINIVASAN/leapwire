import { afterEach, describe, expect, it, vi } from "vitest";
import { createFetch } from "../src/index.js";
import { CUSTOMER, END, LINES, MAIN, ORIGIN, PLAN, bundle, controlled, flush, ndjson, network } from "./helpers.js";

afterEach(() => {
  vi.useRealTimers();
});

describe("bundle responses", () => {
  it("returns main as a real Response as soon as its line is read, while the rest is still streaming", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);

    const pending = leapwire("/api/orders/42");
    body.push(JSON.stringify(MAIN) + "\n");
    const response = await pending;

    expect(response).toBeInstanceOf(Response);
    expect(response.status).toBe(200);
    expect(response.ok).toBe(true);
    expect(response.url).toBe(`${ORIGIN}/api/orders/42`);
    expect(response.headers.get("content-type")).toBe("application/json");
    expect(response.headers.get("etag")).toBe('"v1"');
    expect(response.headers.has("leapwire")).toBe(false);
    expect(await response.json()).toEqual(MAIN.body);

    body.push(ndjson([PLAN, CUSTOMER, LINES, END]));
    body.close();
    await flush();
    expect(await (await leapwire("/api/customers/19")).json()).toEqual(CUSTOMER.body);
    expect(net.calls).toHaveLength(1);
  });

  it("serves a pushed part once, then sends the next request for that URL to the network", async () => {
    const net = network();
    net.reply("/api/orders/42", () => bundle(ndjson([MAIN, PLAN, CUSTOMER, LINES, END])));
    const leapwire = createFetch(net.base);

    await leapwire("/api/orders/42");
    const first = await leapwire("/api/customers/19");
    const second = await leapwire("/api/customers/19");

    expect(await first.json()).toEqual(CUSTOMER.body);
    expect(first.url).toBe(`${ORIGIN}/api/customers/19`);
    expect(await second.json()).toEqual({ from: "network", path: "/api/customers/19" });
    expect(net.calls.map((call) => call.path)).toEqual(["/api/orders/42", "/api/customers/19"]);
    expect(net.calls[1]?.headers.get("Leapwire-Accept")).toBe("1");
  });

  it("matches parts by absolute URL, so absolute, relative and URL inputs share one entry", async () => {
    const net = network();
    net.reply("/api/orders/42", () => bundle(ndjson([MAIN, PLAN, CUSTOMER, LINES, END])));
    const leapwire = createFetch(net.base);

    await leapwire(`${ORIGIN}/api/orders/42`);
    const viaUrl = await leapwire(new URL("/api/customers/19#top", ORIGIN));
    const viaRequest = await leapwire(new Request(`${ORIGIN}/api/orders/42/lines`));

    expect(await viaUrl.json()).toEqual(CUSTOMER.body);
    expect(await viaRequest.json()).toEqual(LINES.body);
    expect(net.calls).toHaveLength(1);
  });

  it("makes a request for a planned URL wait for its part instead of going to the network", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, PLAN]));
    await main;
    const waiting = leapwire("/api/customers/19");
    await flush();
    expect(net.calls).toHaveLength(1);

    body.push(JSON.stringify(CUSTOMER) + "\n");
    expect(await (await waiting).json()).toEqual(CUSTOMER.body);
    expect(net.calls).toHaveLength(1);
    expect(leapwire.stats().waits).toBe(1);
    body.close();
  });

  it("gives the part to the first waiter and sends other waiters for the same URL to the network", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, PLAN]));
    await main;
    const first = leapwire("/api/customers/19");
    const second = leapwire("/api/customers/19");
    await flush();
    body.push(JSON.stringify(CUSTOMER) + "\n");

    expect(await (await first).json()).toEqual(CUSTOMER.body);
    expect(await (await second).json()).toEqual({ from: "network", path: "/api/customers/19" });
    body.close();
  });

  it("sends the waiting request to the network when its part has status 0", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, PLAN]));
    await main;
    const waiting = leapwire("/api/customers/19");
    await flush();
    body.push(JSON.stringify({ t: "part", url: "/api/customers/19", status: 0 }) + "\n");

    expect(await (await waiting).json()).toEqual({ from: "network", path: "/api/customers/19" });
    expect(net.calls.map((call) => call.path)).toEqual(["/api/orders/42", "/api/customers/19"]);
    body.push(ndjson([LINES, END]));
    body.close();
    expect(await (await leapwire("/api/orders/42/lines")).json()).toEqual(LINES.body);
  });

  it("never caches a part that is not status 200", async () => {
    const net = network();
    const failed = { t: "part", url: "/api/customers/19", status: 0 };
    const notFound = { t: "part", url: "/api/orders/42/lines", status: 404, body: { error: "missing" } };
    net.reply("/api/orders/42", () => bundle(ndjson([MAIN, PLAN, failed, notFound, END])));
    const leapwire = createFetch(net.base);

    await leapwire("/api/orders/42");
    await leapwire("/api/customers/19");
    await leapwire("/api/orders/42/lines");

    expect(net.calls).toHaveLength(3);
    expect(leapwire.stats().parts).toBe(0);
  });

  it("releases every waiter when the end line arrives", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, PLAN]));
    await main;
    const customer = leapwire("/api/customers/19");
    const lines = leapwire("/api/orders/42/lines");
    await flush();
    expect(net.calls).toHaveLength(1);
    body.push(JSON.stringify({ t: "end", parts: 0, skipped: 2 }) + "\n");

    expect(await (await customer).json()).toEqual({ from: "network", path: "/api/customers/19" });
    expect(await (await lines).json()).toEqual({ from: "network", path: "/api/orders/42/lines" });
    await expect(body.cancelled).resolves.toBeUndefined();
  });

  it("releases waiters when the stream closes without an end line", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, PLAN]));
    await main;
    const waiting = leapwire("/api/customers/19");
    await flush();
    body.close();

    expect(await (await waiting).json()).toEqual({ from: "network", path: "/api/customers/19" });
  });

  it("does not wait for URLs the bundle never planned", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, PLAN]));
    await main;

    expect(await (await leapwire("/api/other")).json()).toEqual({ from: "network", path: "/api/other" });
    body.close();
  });

  it("ignores plans and parts for another origin than the bundle's", async () => {
    const net = network();
    const foreign = "https://other.example/api/x";
    net.reply("/api/orders/42", () =>
      bundle(ndjson([MAIN, { t: "plan", urls: [foreign] }, { t: "part", url: foreign, status: 200, body: { stolen: true } }, END])),
    );
    const leapwire = createFetch(net.base, { origins: [ORIGIN, "https://other.example"] });

    await leapwire("/api/orders/42");
    await leapwire(foreign);

    expect(net.calls.map((call) => call.path)).toEqual(["/api/orders/42", foreign]);
    expect(leapwire.stats().parts).toBe(0);
  });

  it("keeps the exact JSON text of bodies, including integers beyond 2^53", async () => {
    const net = network();
    const mainText = '{"id":12345678901234567890,"price":1.50,"name":"caf\\u00e9","nested":{"a":[1,{"b":"}"}]}}';
    const partText = '[ 1e2 , -0.0 , "\\"quoted\\"" ]';
    const text =
      `{"t":"main","status":200,"headers":{"content-type":"application/json"},"body":${mainText}}\n` +
      `{"t":"plan","urls":["/api/x"]}\n` +
      `{ "body" : ${partText} , "t":"part","url":"/api/x","status":200}\n` +
      `{"t":"end","parts":1,"skipped":0}\n`;
    net.reply("/api/orders/42", () => bundle(text));
    const leapwire = createFetch(net.base);

    expect(await (await leapwire("/api/orders/42")).text()).toBe(mainText);
    expect(await (await leapwire("/api/x")).text()).toBe(partText);
  });

  it("defaults the content type of a main or part without headers to application/json", async () => {
    const net = network();
    const text = ndjson([
      { t: "main", status: 200, body: { ok: true } },
      { t: "plan", urls: ["/api/x"] },
      { t: "part", url: "/api/x", status: 200, body: null },
      END,
    ]);
    net.reply("/api/orders/42", () => bundle(text));
    const leapwire = createFetch(net.base);

    const main = await leapwire("/api/orders/42");
    const part = await leapwire("/api/x");

    expect(main.headers.get("content-type")).toBe("application/json");
    expect(part.headers.get("content-type")).toBe("application/json");
    expect(await part.json()).toBeNull();
  });

  it("accepts CRLF line endings, blank lines and a final line without a newline", async () => {
    const net = network();
    const text = [MAIN, PLAN, CUSTOMER, LINES, END].map((line) => JSON.stringify(line)).join("\r\n\r\n");
    net.reply("/api/orders/42", () => bundle(text));
    const leapwire = createFetch(net.base);

    await leapwire("/api/orders/42");
    expect(await (await leapwire("/api/orders/42/lines")).json()).toEqual(LINES.body);
    expect(net.calls).toHaveLength(1);
  });

  it("recognises the Leapwire header case-insensitively and needs nothing else", async () => {
    const net = network();
    net.reply(
      "/api/orders/42",
      () => new Response(ndjson([MAIN, PLAN, CUSTOMER, END]), { headers: { leapwire: " Bundle " } }),
    );
    const leapwire = createFetch(net.base);

    expect(await (await leapwire("/api/orders/42")).json()).toEqual(MAIN.body);
    expect(await (await leapwire("/api/customers/19")).json()).toEqual(CUSTOMER.body);
  });

  it("clear() empties the cache, releases waiters and ignores the rest of bundles in flight", async () => {
    const net = network();
    net.reply("/api/orders/1", () => bundle(ndjson([MAIN, PLAN, CUSTOMER, END])));
    const body = controlled();
    net.reply("/api/orders/2", () => bundle(body.stream));
    const leapwire = createFetch(net.base);

    await leapwire("/api/orders/1");
    const main = leapwire("/api/orders/2");
    body.push(ndjson([MAIN, PLAN]));
    await main;
    const waiting = leapwire("/api/orders/42/lines");
    await flush();

    leapwire.clear();
    body.push(ndjson([LINES, END]));
    body.close();
    await flush();

    expect(await (await waiting).json()).toEqual({ from: "network", path: "/api/orders/42/lines" });
    expect(await (await leapwire("/api/customers/19")).json()).toEqual({ from: "network", path: "/api/customers/19" });
    expect(await (await leapwire("/api/orders/42/lines")).json()).toEqual({ from: "network", path: "/api/orders/42/lines" });
  });
});

describe("ttl", () => {
  it("discards a pushed part that was not used within ttl", async () => {
    vi.useFakeTimers();
    const net = network();
    net.reply("/api/orders/42", () => bundle(ndjson([MAIN, PLAN, CUSTOMER, LINES, END])));
    const leapwire = createFetch(net.base, { ttl: 1000 });

    await leapwire("/api/orders/42");
    vi.advanceTimersByTime(999);
    expect(await (await leapwire("/api/customers/19")).json()).toEqual(CUSTOMER.body);
    vi.advanceTimersByTime(1);

    expect(leapwire.stats().expired).toBe(1);
    expect(await (await leapwire("/api/orders/42/lines")).json()).toEqual({ from: "network", path: "/api/orders/42/lines" });
  });

  it("checks the expiry on lookup even when the timer has not fired yet", async () => {
    vi.useFakeTimers();
    const net = network();
    net.reply("/api/orders/42", () => bundle(ndjson([MAIN, PLAN, CUSTOMER, END])));
    const leapwire = createFetch(net.base, { ttl: 1000 });

    await leapwire("/api/orders/42");
    vi.setSystemTime(Date.now() + 1000);

    expect(await (await leapwire("/api/customers/19")).json()).toEqual({ from: "network", path: "/api/customers/19" });
    expect(leapwire.stats().expired).toBe(1);
  });

  it("does not start waiting for a plan older than ttl even when its timer is late", async () => {
    vi.useFakeTimers();
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base, { ttl: 1000 });

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, PLAN]));
    await main;
    vi.setSystemTime(Date.now() + 1000);

    expect(await (await leapwire("/api/customers/19")).json()).toEqual({ from: "network", path: "/api/customers/19" });
    body.close();
  });

  it("stops waiting for a planned part after ttl and goes to the network", async () => {
    vi.useFakeTimers();
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base, { ttl: 1000 });

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, PLAN]));
    await main;
    const waiting = leapwire("/api/customers/19");
    await vi.advanceTimersByTimeAsync(999);
    expect(net.calls).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);

    expect(await (await waiting).json()).toEqual({ from: "network", path: "/api/customers/19" });
    expect(net.calls).toHaveLength(2);
    body.push(ndjson([CUSTOMER, END]));
    body.close();
  });
});

describe("buffered mode", () => {
  it("reads the whole bundle as text when the body is not a stream, as React Native's fetch does", async () => {
    const net = network();
    net.reply("/api/orders/42", () => {
      const response = bundle(ndjson([MAIN, PLAN, CUSTOMER, LINES, END]));
      Object.defineProperty(response, "body", { value: undefined });
      return response;
    });
    const leapwire = createFetch(net.base);

    expect(await (await leapwire("/api/orders/42")).json()).toEqual(MAIN.body);
    expect(await (await leapwire("/api/customers/19")).json()).toEqual(CUSTOMER.body);
    expect(await (await leapwire("/api/orders/42/lines")).json()).toEqual(LINES.body);
    expect(net.calls).toHaveLength(1);
  });

  it("buffered: true reads text even when a stream is available", async () => {
    const net = network();
    let reads = 0;
    net.reply("/api/orders/42", () => {
      const response = bundle(ndjson([MAIN, PLAN, CUSTOMER, END]));
      const text = response.text.bind(response);
      response.text = () => {
        reads += 1;
        return text();
      };
      return response;
    });
    const leapwire = createFetch(net.base, { buffered: true });

    await leapwire("/api/orders/42");

    expect(reads).toBe(1);
    expect(await (await leapwire("/api/customers/19")).json()).toEqual(CUSTOMER.body);
  });

  it("buffered: false still reads text when there is no stream to read", async () => {
    const net = network();
    net.reply("/api/orders/42", () => {
      const response = bundle(ndjson([MAIN, PLAN, CUSTOMER, END]));
      Object.defineProperty(response, "body", { value: null });
      return response;
    });
    const leapwire = createFetch(net.base, { buffered: false });

    expect(await (await leapwire("/api/orders/42")).json()).toEqual(MAIN.body);
    expect(await (await leapwire("/api/customers/19")).json()).toEqual(CUSTOMER.body);
  });
});

describe("malformed bundles", () => {
  it("falls back to the plain request when the first line is not JSON", async () => {
    const net = network();
    net.reply("/api/orders/42", () => bundle("<html>proxy error</html>\n"));
    net.reply("/api/orders/42", () => new Response("plain", { headers: { "X-Plain": "1" } }));
    const leapwire = createFetch(net.base);
    const init = { headers: { Authorization: "Bearer abc" } };

    const response = await leapwire("/api/orders/42", init);

    expect(await response.text()).toBe("plain");
    expect(net.calls).toHaveLength(2);
    expect(net.calls[1]?.input).toBe("/api/orders/42");
    expect(net.calls[1]?.init).toBe(init);
    expect(net.calls[1]?.headers.has("Leapwire-Accept")).toBe(false);
  });

  it.each([
    ["the first line is not main", ndjson([PLAN, MAIN])],
    ["main has an invalid status", ndjson([{ ...MAIN, status: 0 }])],
    ["main has headers that are not an object", ndjson([{ ...MAIN, headers: ["x"] }])],
    ["the body is empty", ""],
    ["the first line is a JSON array", "[1,2]\n"],
    ["the body is cut inside main", JSON.stringify(MAIN).slice(0, 30)],
  ])("falls back to the plain request when %s", async (_name, text) => {
    const net = network();
    net.reply("/api/orders/42", () => bundle(text));
    const leapwire = createFetch(net.base);

    const response = await leapwire("/api/orders/42");

    expect(await response.json()).toEqual({ from: "network", path: "/api/orders/42" });
    expect(net.calls).toHaveLength(2);
  });

  it("falls back to the plain request when the stream fails before main", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);

    const pending = leapwire("/api/orders/42");
    body.push('{"t":"ma');
    await flush();
    body.fail(new TypeError("network reset"));

    expect(await (await pending).json()).toEqual({ from: "network", path: "/api/orders/42" });
  });

  it("keeps main and sends waiters to the network when a later line is malformed", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, PLAN]));
    const response = await main;
    const waiting = leapwire("/api/customers/19");
    await flush();
    body.push('{"t":"part","url":"/api/customers/19","status":200,"body":{"id":\n');

    expect(await response.json()).toEqual(MAIN.body);
    expect(await (await waiting).json()).toEqual({ from: "network", path: "/api/customers/19" });
    await expect(body.cancelled).resolves.toBeUndefined();
  });

  it.each([
    ["a plan whose urls is not an array", { t: "plan", urls: "/api/customers/19" }],
    ["a part without a url", { t: "part", status: 200, body: {} }],
    ["a status 200 part without a body", { t: "part", url: "/api/customers/19", status: 200 }],
    ["a line without a type", { url: "/api/customers/19" }],
    ["a second main", MAIN],
  ])("stops reading the bundle at %s", async (_name, line) => {
    const net = network();
    net.reply("/api/orders/42", () => bundle(ndjson([MAIN, PLAN, line, CUSTOMER, LINES, END])));
    const leapwire = createFetch(net.base);

    expect(await (await leapwire("/api/orders/42")).json()).toEqual(MAIN.body);
    await flush();
    expect(await (await leapwire("/api/customers/19")).json()).toEqual({ from: "network", path: "/api/customers/19" });
  });

  it("ignores lines of unknown types and plan entries it cannot resolve", async () => {
    const net = network();
    const text = ndjson([MAIN, { t: "hint", anything: [1] }, { t: "plan", urls: ["http://[bad", ...PLAN.urls] }, CUSTOMER, END]);
    net.reply("/api/orders/42", () => bundle(text));
    const leapwire = createFetch(net.base);

    await leapwire("/api/orders/42");

    expect(await (await leapwire("/api/customers/19")).json()).toEqual(CUSTOMER.body);
  });
});

describe("abort", () => {
  it("rejects with the abort reason when aborted before main and does not retry", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);
    const controller = new AbortController();

    const pending = leapwire("/api/orders/42", { signal: controller.signal });
    await flush();
    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    await expect(body.cancelled).resolves.toBeUndefined();
    expect(net.calls).toHaveLength(1);
  });

  it("rejects without a request when the signal is already aborted", async () => {
    const net = network();
    const leapwire = createFetch(net.base);
    const controller = new AbortController();
    controller.abort(new Error("gone"));

    await expect(leapwire("/api/orders/42", { signal: controller.signal })).rejects.toThrow("gone");
    expect(net.calls).toHaveLength(0);
  });

  it("uses the signal of a Request input", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);
    const controller = new AbortController();

    const pending = leapwire(new Request(`${ORIGIN}/api/orders/42`, { signal: controller.signal }));
    await flush();
    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  });

  it("rejects an aborted waiter and keeps the part for the next request", async () => {
    const net = network();
    const body = controlled();
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base);
    const controller = new AbortController();

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, PLAN]));
    await main;
    const waiting = leapwire("/api/customers/19", { signal: controller.signal });
    await flush();
    controller.abort();
    await expect(waiting).rejects.toMatchObject({ name: "AbortError" });

    body.push(ndjson([CUSTOMER, LINES, END]));
    body.close();
    await flush();
    expect(await (await leapwire("/api/customers/19")).json()).toEqual(CUSTOMER.body);
    expect(net.calls).toHaveLength(1);
  });
});

describe("stats", () => {
  it("counts bundles, parts, hits, waits, misses and expired parts", async () => {
    vi.useFakeTimers();
    const net = network();
    const body = controlled();
    const failed = { t: "part", url: "/api/products", status: 0 };
    net.reply("/api/orders/42", () => bundle(body.stream));
    const leapwire = createFetch(net.base, { ttl: 1000 });

    const main = leapwire("/api/orders/42");
    body.push(ndjson([MAIN, { t: "plan", urls: [...PLAN.urls, "/api/products"] }, CUSTOMER]));
    await main;
    await leapwire("/api/customers/19");
    const waiting = leapwire("/api/orders/42/lines");
    await vi.advanceTimersByTimeAsync(0);
    body.push(ndjson([LINES, failed, { t: "plan", urls: ["/api/unused"] }, { ...CUSTOMER, url: "/api/unused" }, END]));
    body.close();
    await waiting;
    await leapwire("/api/products");
    await leapwire("/api/other");
    await vi.advanceTimersByTimeAsync(1000);

    expect(leapwire.stats()).toEqual({ bundles: 1, parts: 3, hits: 1, waits: 1, misses: 3, expired: 1 });
  });

  it("returns a snapshot that later requests do not change", async () => {
    const leapwire = createFetch(network().base);
    const before = leapwire.stats();
    await leapwire("/api/a");
    expect(before.misses).toBe(0);
    expect(leapwire.stats().misses).toBe(1);
  });
});
