import { afterEach, describe, expect, it, vi } from "vitest";
import { createFetch, install } from "../src/index.js";
import { network } from "./helpers.js";

afterEach(() => {
  vi.unstubAllGlobals();
  history.replaceState(null, "", "/");
});

describe("install", () => {
  it("replaces globalThis.fetch and uninstall restores the exact original function and descriptor", () => {
    const original = globalThis.fetch;
    const descriptor = Object.getOwnPropertyDescriptor(globalThis, "fetch");

    const leapwire = install();
    expect(globalThis.fetch).toBe(leapwire);
    expect(globalThis.fetch).not.toBe(original);
    leapwire.uninstall();

    expect(globalThis.fetch).toBe(original);
    expect(Object.getOwnPropertyDescriptor(globalThis, "fetch")).toEqual(descriptor);
  });

  it("patches history while installed and restores pushState, replaceState and popstate on uninstall", async () => {
    const net = network();
    vi.stubGlobal("fetch", net.base);
    const push = history.pushState;
    const replace = history.replaceState;
    const ownPush = Object.getOwnPropertyDescriptor(history, "pushState");
    const ownReplace = Object.getOwnPropertyDescriptor(history, "replaceState");

    const leapwire = install();
    expect(history.pushState).not.toBe(push);
    expect(history.replaceState).not.toBe(replace);
    leapwire.uninstall();

    expect(history.pushState).toBe(push);
    expect(history.replaceState).toBe(replace);
    expect(Object.getOwnPropertyDescriptor(history, "pushState")).toEqual(ownPush);
    expect(Object.getOwnPropertyDescriptor(history, "replaceState")).toEqual(ownReplace);

    const page = createFetch(net.base);
    await page("/api/a");
    window.dispatchEvent(new Event("popstate"));
    history.pushState(null, "", "/after");
    await page("/api/b");
    expect(net.calls[1]?.headers.get("Leapwire-Page")).toBe(net.calls[0]?.headers.get("Leapwire-Page"));
  });

  it("starts a new page id on pushState, replaceState and popstate and follows location.pathname", async () => {
    const net = network();
    vi.stubGlobal("fetch", net.base);
    const leapwire = install();

    await fetch("/api/a");
    await fetch("/api/b");
    history.pushState({ n: 1 }, "", "/orders/42");
    await fetch("/api/orders/42");
    history.replaceState({ n: 2 }, "", "/orders/43");
    await fetch("/api/orders/43");
    window.dispatchEvent(new Event("popstate"));
    await fetch("/api/orders/43");
    leapwire.uninstall();

    const pages = net.calls.map((call) => call.headers.get("Leapwire-Page"));
    expect(pages[0]).toBe(pages[1]);
    expect(new Set(pages).size).toBe(4);
    expect(net.calls.map((call) => call.headers.get("Leapwire-Route"))).toEqual([
      "/",
      "/",
      "/orders/42",
      "/orders/43",
      "/orders/43",
    ]);
    expect(history.state).toEqual({ n: 2 });
    expect(location.pathname).toBe("/orders/43");
  });

  it("turns the installed function into a plain passthrough after uninstall", async () => {
    const net = network();
    vi.stubGlobal("fetch", net.base);
    const leapwire = install();

    leapwire.uninstall();
    leapwire.uninstall();
    await leapwire("/api/a");

    expect(net.calls[0]?.args).toBe(1);
    expect(net.calls[0]?.headers.has("Leapwire-Page")).toBe(false);
    expect(globalThis.fetch).toBe(net.base);
  });

  it("leaves a fetch wrapper installed after it in place and stops touching requests", async () => {
    const net = network();
    vi.stubGlobal("fetch", net.base);
    const leapwire = install();
    const wrapper = (input: RequestInfo | URL, init?: RequestInit) => leapwire(input, init);
    vi.stubGlobal("fetch", wrapper);

    leapwire.uninstall();
    await fetch("/api/a");

    expect(globalThis.fetch).toBe(wrapper);
    expect(net.calls[0]?.headers.has("Leapwire-Page")).toBe(false);
  });

  it("calls the original fetch with globalThis as this", async () => {
    const seen: unknown[] = [];
    vi.stubGlobal("fetch", function (this: unknown) {
      seen.push(this);
      return Promise.resolve(new Response("{}"));
    });
    const leapwire = install();

    await fetch("/api/a");
    await fetch("https://elsewhere.example/x");
    leapwire.uninstall();

    expect(seen).toEqual([globalThis, globalThis]);
  });

  it("throws a clear error when there is no fetch to wrap", () => {
    vi.stubGlobal("fetch", undefined);
    expect(() => install()).toThrow(TypeError);
    expect(() => createFetch()).toThrow(/fetch implementation/);
  });
});

describe("React Native style environment", () => {
  it("works without location, history or randomUUID when origins are explicit", async () => {
    vi.stubGlobal("location", undefined);
    vi.stubGlobal("history", undefined);
    vi.stubGlobal("crypto", undefined);
    const net = network();
    const leapwire = createFetch(net.base, { origins: ["https://api.example.com"] });

    await leapwire("https://api.example.com/orders/42");
    leapwire.setRoute("OrderDetail");
    await leapwire("https://api.example.com/customers/19");
    await leapwire("/relative/cannot/be/resolved");

    const [first, second, third] = net.calls;
    expect(first?.headers.get("Leapwire-Route")).toBe("");
    expect(first?.headers.get("Leapwire-Page")).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(second?.headers.get("Leapwire-Route")).toBe("OrderDetail");
    expect(second?.headers.get("Leapwire-Page")).not.toBe(first?.headers.get("Leapwire-Page"));
    expect(third?.args).toBe(1);
  });

  it("touches nothing when there is no location and no origins option", async () => {
    vi.stubGlobal("location", undefined);
    const net = network();
    const leapwire = createFetch(net.base);

    await leapwire("https://api.example.com/orders/42");

    expect(net.calls[0]?.args).toBe(1);
  });

  it("install works without history and uninstall still restores fetch", async () => {
    vi.stubGlobal("location", undefined);
    vi.stubGlobal("history", undefined);
    const net = network();
    vi.stubGlobal("fetch", net.base);

    const leapwire = install({ origins: ["https://api.example.com"] });
    leapwire.setRoute("Home");
    await fetch("https://api.example.com/me");
    leapwire.uninstall();

    expect(net.calls[0]?.headers.get("Leapwire-Route")).toBe("Home");
    expect(globalThis.fetch).toBe(net.base);
  });
});
