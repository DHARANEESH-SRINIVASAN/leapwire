import { afterEach, describe, expect, it, vi } from "vitest";
import axios from "axios";
import { install } from "../src/index.js";
import { CUSTOMER, END, LINES, MAIN, ORIGIN, PLAN, bundle, ndjson, network } from "./helpers.js";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("axios with adapter: \"fetch\"", () => {
  it("looks up the global fetch on every request, so an instance created before install() goes through Leapwire", async () => {
    const net = network();
    vi.stubGlobal("fetch", net.base);
    const client = axios.create({ adapter: "fetch", baseURL: ORIGIN });

    await client.get("/api/before");
    const leapwire = install();
    await client.get("/api/during");
    leapwire.uninstall();
    await client.get("/api/after");

    expect(net.calls.map((call) => call.path)).toEqual(["/api/before", "/api/during", "/api/after"]);
    expect(net.calls.map((call) => call.headers.get("Leapwire-Accept"))).toEqual([null, "1", null]);
  });

  it("gets main from a bundle and the follow-up request from the pushed part", async () => {
    const net = network();
    net.reply("/api/orders/42", () => bundle(ndjson([MAIN, PLAN, CUSTOMER, LINES, END])));
    vi.stubGlobal("fetch", net.base);
    const leapwire = install();
    const client = axios.create({ adapter: "fetch", baseURL: ORIGIN });

    const order = await client.get<{ customerId: number }>("/api/orders/42");
    const customer = await client.get(`/api/customers/${order.data.customerId}`);
    const stats = leapwire.stats();
    leapwire.uninstall();

    expect(order.status).toBe(200);
    expect(order.data).toEqual(MAIN.body);
    expect(order.headers["etag"]).toBe('"v1"');
    expect(customer.data).toEqual(CUSTOMER.body);
    expect(net.calls).toHaveLength(1);
    expect(stats).toMatchObject({ bundles: 1, parts: 2, hits: 1 });
  });

  it("does not see a fetch passed through env.fetch, which is the one case install() cannot reach", async () => {
    const net = network();
    const own = network();
    vi.stubGlobal("fetch", net.base);
    const leapwire = install();
    const client = axios.create({ adapter: "fetch", baseURL: ORIGIN, env: { fetch: own.base } });

    await client.get("/api/x");
    leapwire.uninstall();

    expect(net.calls).toHaveLength(0);
    expect(own.calls[0]?.headers.has("Leapwire-Accept")).toBe(false);
  });
});
