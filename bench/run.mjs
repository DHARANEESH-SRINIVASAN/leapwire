import net from "node:net";
import puppeteer from "puppeteer-core";
const BASE = process.env.BASE || "http://127.0.0.1:8098";
const CHROME = process.env.CHROME || "/usr/bin/google-chrome";
const setRtt = (ms) => new Promise((resolve) => { const s = net.connect(8198, "127.0.0.1", () => s.write(String(ms))); s.on("data", () => { s.end(); resolve(); }); });
const RUNS = Number(process.env.RUNS || 15);
const LATENCIES = (process.env.LAT || "40,150,300").split(",").map(Number);
const users = ["alice", "bob"];
const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ["--no-sandbox", "--disable-gpu"] });
async function load(mode, user) {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  const cdp = await page.createCDPSession();
  await cdp.send("Network.enable");
  await cdp.send("Network.setCacheDisabled", { cacheDisabled: true });
  await cdp.send("Network.emulateNetworkConditions", { offline: false, latency: 0, downloadThroughput: 1.6e6 / 8, uploadThroughput: 750e3 / 8 });
  await page.setExtraHTTPHeaders({ authorization: "Basic " + Buffer.from(`${user}:${user}`).toString("base64") });
  let api = 0;
  page.on("request", (r) => { if (new URL(r.url()).pathname.startsWith("/api/")) api++; });
  await page.goto(`${BASE}/index.html?mode=${mode}`);
  await page.waitForFunction("window.__result", { timeout: 30000 });
  const result = await page.evaluate(() => window.__result);
  await context.close();
  return { ...result, api };
}
const median = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
await setRtt(0);
const expected = {};
for (const user of users) {
  expected[user] = await load("plain", user);
  if (expected[user].error) { console.log("PLAIN FAILED", user, expected[user].error); process.exit(1); }
}
console.log("plain answers:", JSON.stringify(expected));
for (let i = 0; i < Number(process.env.TRAIN || 12); i++) {
  const r = await load("leapwire", users[i % 2]);
  if (r.error) { console.log("TRAIN FAILED", r.error); process.exit(1); }
}
const rows = [];
let mismatches = 0;
for (const latency of LATENCIES) {
  await setRtt(latency);
  const ms = { plain: [], leapwire: [] };
  const api = { plain: [], leapwire: [] };
  const totals = { hits: 0, parts: 0, misses: 0, bundles: 0 };
  for (const mode of ["plain", "leapwire"]) {
    for (let i = 0; i < RUNS; i++) {
      const user = users[i % 2];
      const r = await load(mode, user);
      if (r.error || r.customer !== expected[user].customer || JSON.stringify(r.products) !== JSON.stringify(expected[user].products)) { mismatches++; console.log("MISMATCH", mode, user, JSON.stringify(r)); continue; }
      ms[mode].push(r.ms);
      api[mode].push(r.api);
      if (r.stats) { totals.hits += r.stats.hits + r.stats.waits; totals.parts += r.stats.parts; totals.misses += r.stats.misses; totals.bundles += r.stats.bundles; }
    }
  }
  const row = { rttMs: latency, plainMs: Math.round(median(ms.plain)), leapwireMs: Math.round(median(ms.leapwire)), speedup: (median(ms.plain) / median(ms.leapwire)).toFixed(2), requestsPlain: median(api.plain), requestsLeapwire: median(api.leapwire), pushedUsed: `${totals.hits}/${totals.parts}`, bundles: totals.bundles };
  rows.push(row);
  console.log(JSON.stringify(row));
}
console.log("mismatches:", mismatches);
console.table(rows);
await browser.close();
