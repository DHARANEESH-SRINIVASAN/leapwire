import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import net from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";

const BASE = process.env.BASE || "http://127.0.0.1:8098";
const CHROME = process.env.CHROME || "/usr/bin/google-chrome";
const RUNS = Number(process.env.RUNS || 15);
const TRAIN = Number(process.env.TRAIN || 12);
const LATENCIES = (process.env.LAT || "40,150,300").split(",").map(Number);
const USERS = ["alice", "bob"];

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const setRtt = (ms) => new Promise((resolve, reject) => {
  const socket = net.connect(8198, "127.0.0.1", () => socket.write(String(ms)));
  socket.on("data", () => {
    socket.end();
    resolve();
  });
  socket.on("error", reject);
});

async function launch() {
  const profile = mkdtempSync(join(tmpdir(), "leapwire-bench-"));
  const chrome = spawn(CHROME, ["--headless=new", "--no-sandbox", "--disable-gpu", "--no-first-run",
    "--no-default-browser-check", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: "ignore" });
  const portFile = join(profile, "DevToolsActivePort");
  const started = Date.now();
  let endpoint = null;
  while (endpoint === null) {
    if (Date.now() - started > 20000) {
      chrome.kill();
      throw new Error("Chrome did not start: " + CHROME);
    }
    if (existsSync(portFile)) {
      const [port, path] = readFileSync(portFile, "utf8").split("\n");
      if (port && path) {
        endpoint = `ws://127.0.0.1:${port}${path}`;
      }
    }
    await pause(50);
  }
  const socket = new WebSocket(endpoint);
  await new Promise((resolve, reject) => {
    socket.onopen = resolve;
    socket.onerror = () => reject(new Error("Cannot connect to " + endpoint));
  });
  let next = 0;
  const pending = new Map();
  const listeners = new Set();
  socket.onmessage = (event) => {
    const message = JSON.parse(event.data);
    const waiter = message.id === undefined ? undefined : pending.get(message.id);
    if (waiter) {
      pending.delete(message.id);
      if (message.error) {
        waiter.reject(new Error(message.error.message));
      } else {
        waiter.resolve(message.result);
      }
      return;
    }
    for (const listener of listeners) {
      listener(message);
    }
  };
  const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
    const id = ++next;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
  });
  const exited = new Promise((resolve) => chrome.once("exit", resolve));
  const close = async () => {
    await send("Browser.close").catch(() => {});
    socket.close();
    await Promise.race([exited, pause(5000)]);
    chrome.kill();
    rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  };
  return { send, listeners, close };
}

async function result(session) {
  const started = Date.now();
  for (;;) {
    const evaluated = await session("Runtime.evaluate", {
      expression: "window.__result ? JSON.stringify(window.__result) : null",
      returnByValue: true,
    });
    if (evaluated.result.value) {
      return JSON.parse(evaluated.result.value);
    }
    if (Date.now() - started > 30000) {
      throw new Error("The page did not finish loading");
    }
    await pause(20);
  }
}

async function load(browser, mode, user) {
  const { browserContextId } = await browser.send("Target.createBrowserContext");
  const { targetId } = await browser.send("Target.createTarget", { url: "about:blank", browserContextId });
  const { sessionId } = await browser.send("Target.attachToTarget", { targetId, flatten: true });
  const session = (method, params) => browser.send(method, params, sessionId);
  let api = 0;
  const count = (message) => {
    if (message.sessionId === sessionId && message.method === "Network.requestWillBeSent"
        && new URL(message.params.request.url).pathname.startsWith("/api/")) {
      api++;
    }
  };
  browser.listeners.add(count);
  try {
    await session("Network.enable");
    await session("Network.setCacheDisabled", { cacheDisabled: true });
    await session("Network.emulateNetworkConditions", {
      offline: false, latency: 0, downloadThroughput: 1.6e6 / 8, uploadThroughput: 750e3 / 8,
    });
    await session("Network.setExtraHTTPHeaders", {
      headers: { authorization: "Basic " + Buffer.from(`${user}:${user}`).toString("base64") },
    });
    await session("Page.navigate", { url: `${BASE}/index.html?mode=${mode}` });
    return { ...(await result(session)), api };
  } finally {
    browser.listeners.delete(count);
    await browser.send("Target.disposeBrowserContext", { browserContextId });
  }
}

const median = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
};

const browser = await launch();
try {
  await setRtt(0);
  const expected = {};
  for (const user of USERS) {
    expected[user] = await load(browser, "plain", user);
    if (expected[user].error) {
      throw new Error(`Plain load failed for ${user}: ${expected[user].error}`);
    }
  }
  console.log("plain answers:", JSON.stringify(expected));
  for (let i = 0; i < TRAIN; i++) {
    const trained = await load(browser, "leapwire", USERS[i % USERS.length]);
    if (trained.error) {
      throw new Error("Training load failed: " + trained.error);
    }
  }
  const rows = [];
  let mismatches = 0;
  for (const latency of LATENCIES) {
    await setRtt(latency);
    const times = { plain: [], leapwire: [] };
    const requests = { plain: [], leapwire: [] };
    const pushed = { used: 0, received: 0 };
    for (const mode of ["plain", "leapwire"]) {
      for (let i = 0; i < RUNS; i++) {
        const user = USERS[i % USERS.length];
        const loaded = await load(browser, mode, user);
        const same = !loaded.error && loaded.customer === expected[user].customer
          && JSON.stringify(loaded.products) === JSON.stringify(expected[user].products);
        if (!same) {
          mismatches++;
          console.log("MISMATCH", mode, user, JSON.stringify(loaded));
          continue;
        }
        times[mode].push(loaded.ms);
        requests[mode].push(loaded.api);
        if (loaded.stats) {
          pushed.used += loaded.stats.hits + loaded.stats.waits;
          pushed.received += loaded.stats.parts;
        }
      }
    }
    const row = {
      rttMs: latency,
      plainMs: Math.round(median(times.plain)),
      leapwireMs: Math.round(median(times.leapwire)),
      speedup: (median(times.plain) / median(times.leapwire)).toFixed(2),
      requestsPlain: median(requests.plain),
      requestsLeapwire: median(requests.leapwire),
      pushedUsed: `${pushed.used}/${pushed.received}`,
    };
    rows.push(row);
    console.log(JSON.stringify(row));
  }
  console.log("mismatches:", mismatches);
  console.table(rows);
} finally {
  await setRtt(0).catch(() => {});
  await browser.close();
}
