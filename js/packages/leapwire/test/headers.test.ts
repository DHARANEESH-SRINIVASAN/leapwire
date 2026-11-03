import { afterEach, describe, expect, it, vi } from "vitest";
import { createFetch } from "../src/index.js";
import { ORIGIN, json, network } from "./helpers.js";

const PAGE = /^[A-Za-z0-9_-]{1,64}$/;

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("request headers", () => {
  it("adds the three Leapwire headers to a GET to the page's origin and keeps the caller's init", async () => {
    const net = network();
    const leapwire = createFetch(net.base);
    const controller = new AbortController();

    await leapwire("/api/orders/42", {
      headers: { Authorization: "Bearer abc", "X-Trace": "7" },
      signal: controller.signal,
      credentials: "include",
      cache: "no-cache",
      mode: "cors",
    });

    const [call] = net.calls;
    expect(call?.input).toBe("/api/orders/42");
    expect(call?.headers.get("Leapwire-Accept")).toBe("1");
    expect(call?.headers.get("Leapwire-Page")).toMatch(PAGE);
    expect(call?.headers.get("Leapwire-Route")).toBe(location.pathname);
    expect(call?.headers.get("Authorization")).toBe("Bearer abc");
    expect(call?.headers.get("X-Trace")).toBe("7");
    expect(call?.init?.signal).toBe(controller.signal);
    expect(call?.init?.credentials).toBe("include");
    expect(call?.init?.cache).toBe("no-cache");
    expect(call?.init?.mode).toBe("cors");
  });

  it("keeps the headers of a Request input and accepts URL objects", async () => {
    const net = network();
    const leapwire = createFetch(net.base);
    const request = new Request(`${ORIGIN}/api/orders/42`, { headers: { "X-From-Request": "yes" } });

    await leapwire(request);
    await leapwire(new URL("/api/customers/19", ORIGIN));

    const [first, second] = net.calls;
    expect(first?.input).toBe(request);
    expect(first?.headers.get("X-From-Request")).toBe("yes");
    expect(first?.headers.get("Leapwire-Accept")).toBe("1");
    expect(second?.headers.get("Leapwire-Accept")).toBe("1");
    expect(second?.path).toBe("/api/customers/19");
  });

  it("init headers replace the Request headers, as fetch does", async () => {
    const net = network();
    const leapwire = createFetch(net.base);

    await leapwire(new Request(`${ORIGIN}/api/a`, { headers: { "X-Old": "1" } }), { headers: { "X-New": "2" } });

    expect(net.calls[0]?.headers.get("X-Old")).toBeNull();
    expect(net.calls[0]?.headers.get("X-New")).toBe("2");
    expect(net.calls[0]?.headers.get("Leapwire-Page")).toMatch(PAGE);
  });

  it("keeps one page id until setRoute starts a new one with the new route", async () => {
    const net = network();
    const leapwire = createFetch(net.base);

    await leapwire("/api/a");
    await leapwire("/api/b");
    leapwire.setRoute("OrderDetail");
    await leapwire("/api/c");
    leapwire.setRoute("OrderDetail");
    await leapwire("/api/d");

    const pages = net.calls.map((call) => call.headers.get("Leapwire-Page"));
    const routes = net.calls.map((call) => call.headers.get("Leapwire-Route"));
    expect(pages[0]).toBe(pages[1]);
    expect(pages[2]).not.toBe(pages[1]);
    expect(pages[3]).not.toBe(pages[2]);
    expect(routes).toEqual([location.pathname, location.pathname, "OrderDetail", "OrderDetail"]);
  });

  it("uses the route option when given and setRoute over it once called", async () => {
    const net = network();
    let screen = "Home";
    const leapwire = createFetch(net.base, { route: () => screen });

    await leapwire("/api/a");
    screen = "Orders";
    await leapwire("/api/b");
    leapwire.setRoute("Settings");
    await leapwire("/api/c");

    expect(net.calls.map((call) => call.headers.get("Leapwire-Route"))).toEqual(["Home", "Orders", "Settings"]);
  });

  it("limits the route to 256 characters and percent-encodes what a header cannot carry", async () => {
    const net = network();
    const leapwire = createFetch(net.base);

    leapwire.setRoute(`Café/${"x".repeat(300)}`);
    await leapwire("/api/a");
    leapwire.setRoute("订单\n详情");
    await leapwire("/api/b");
    leapwire.setRoute(`${"y".repeat(254)}é`);
    await leapwire("/api/c");

    const [long = "", unicode, cut] = net.calls.map((call) => call.headers.get("Leapwire-Route") ?? "");
    expect(long).toHaveLength(256);
    expect(long.startsWith("Caf%C3%A9/xxx")).toBe(true);
    expect(unicode).toBe(encodeURIComponent("订单\n详情"));
    expect(cut).toBe("y".repeat(254));
  });

  it("uses crypto.randomUUID when present and a random [A-Za-z0-9_-] id otherwise", async () => {
    const net = network();
    await createFetch(net.base)("/api/a");
    expect(net.calls[0]?.headers.get("Leapwire-Page")).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);

    vi.stubGlobal("crypto", { getRandomValues: (bytes: Uint8Array) => bytes.fill(255) });
    await createFetch(net.base)("/api/b");
    expect(net.calls[1]?.headers.get("Leapwire-Page")).toBe("-".repeat(22));

    vi.stubGlobal("crypto", undefined);
    const fallback = createFetch(net.base);
    await fallback("/api/c");
    fallback.setRoute("Next");
    await fallback("/api/d");
    const [third, fourth] = net.calls.slice(2).map((call) => call.headers.get("Leapwire-Page"));
    expect(third).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(fourth).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(third).not.toBe(fourth);
  });
});

describe("passthrough", () => {
  it("passes a non-GET request through with the identical arguments and response", async () => {
    const response = new Response("created", { status: 201, headers: { "X-Id": "9" } });
    const base = vi.fn(async (..._args: unknown[]) => response);
    const leapwire = createFetch(base);
    const init = { method: "POST", body: "{}", headers: { "Content-Type": "application/json" } };

    const result = await leapwire("/api/orders", init);

    expect(result).toBe(response);
    expect(base).toHaveBeenCalledTimes(1);
    expect(base.mock.calls[0]).toHaveLength(2);
    expect(base.mock.calls[0]?.[0]).toBe("/api/orders");
    expect(base.mock.calls[0]?.[1]).toBe(init);
  });

  it("passes a Request with another method through untouched", async () => {
    const net = network();
    const leapwire = createFetch(net.base);
    const request = new Request(`${ORIGIN}/api/orders/42`, { method: "DELETE" });

    await leapwire(request);

    expect(net.calls[0]?.input).toBe(request);
    expect(net.calls[0]?.args).toBe(1);
    expect(net.calls[0]?.headers.has("Leapwire-Page")).toBe(false);
  });

  it("passes a GET to another origin through with exactly the caller's arguments", async () => {
    const net = network();
    const leapwire = createFetch(net.base);

    await leapwire("https://cdn.example.com/app.json");
    await leapwire("https://cdn.example.com/app.json", { headers: { A: "1" } });

    expect(net.calls[0]?.args).toBe(1);
    expect(net.calls[0]?.input).toBe("https://cdn.example.com/app.json");
    expect(net.calls[1]?.args).toBe(2);
    expect(net.calls[1]?.headers.has("Leapwire-Accept")).toBe(false);
    expect([...(net.calls[1]?.headers.keys() ?? [])]).toEqual(["a"]);
  });

  it("touches only the configured origins when origins is given", async () => {
    const net = network();
    const leapwire = createFetch(net.base, { origins: ["https://api.example.com/"] });

    await leapwire("https://api.example.com/v1/orders");
    await leapwire("/api/local");

    expect(net.calls[0]?.headers.get("Leapwire-Accept")).toBe("1");
    expect(net.calls[1]?.args).toBe(1);
    expect(net.calls[1]?.headers.has("Leapwire-Accept")).toBe(false);
  });

  it("returns a non-bundle response untouched, byte- and header-identical", async () => {
    const body = '{"id": 42,   "big": 12345678901234567890}';
    const response = new Response(body, { status: 200, headers: { "Content-Type": "application/json", ETag: '"a"' } });
    const base = vi.fn(async (..._args: unknown[]) => response);
    const leapwire = createFetch(base);

    const result = await leapwire("/api/orders/42");

    expect(result).toBe(response);
    expect([...result.headers]).toEqual([
      ["content-type", "application/json"],
      ["etag", '"a"'],
    ]);
    expect(await result.text()).toBe(body);
    expect(leapwire.stats()).toEqual({ bundles: 0, parts: 0, hits: 0, waits: 0, misses: 1, expired: 0 });
  });

  it("returns error responses untouched", async () => {
    const response = json({ error: "nope" }, 404);
    const leapwire = createFetch(async () => response);

    expect(await leapwire("/api/missing")).toBe(response);
  });
});
