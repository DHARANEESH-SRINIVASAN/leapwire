import { newPageId, routeHeader } from "./env.js";
import { parseLine, splitLines, type LineSplitter, type PartLine, type Payload } from "./lines.js";

export interface LeapwireOptions {
  origins?: string[];
  ttl?: number;
  route?: () => string;
  buffered?: boolean | "auto";
}

export interface LeapwireStats {
  bundles: number;
  parts: number;
  hits: number;
  waits: number;
  misses: number;
  expired: number;
}

export interface LeapwireFetch {
  (input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
  setRoute(route: string): void;
  clear(): void;
  stats(): LeapwireStats;
}

export interface Client {
  fetch: LeapwireFetch;
  renew(): void;
  disable(): void;
}

type Timer = ReturnType<typeof setTimeout>;

interface Cached {
  part: Payload;
  expires: number;
  timer: Timer;
}

interface Waiter {
  settle(part: Payload | null): void;
}

interface Planned {
  owners: Set<Bundle>;
  waiters: Waiter[];
  expires: number;
  timer: Timer | undefined;
}

interface Bundle {
  url: string;
  origin: string;
  planned: Set<string>;
  detached: boolean;
  finished: boolean;
}

const DEFAULT_TTL = 10_000;

const NULL_BODY_STATUS = new Set([204, 205, 304]);

const noop = (): void => undefined;

export function createClient(base: typeof fetch, options: LeapwireOptions = {}): Client {
  const ttl = typeof options.ttl === "number" && Number.isFinite(options.ttl) ? Math.max(0, options.ttl) : DEFAULT_TTL;
  const buffered = options.buffered ?? "auto";
  const origins = allowedOrigins(options.origins);
  const cache = new Map<string, Cached>();
  const planned = new Map<string, Planned>();
  const bundles = new Set<Bundle>();
  const counts: LeapwireStats = { bundles: 0, parts: 0, hits: 0, waits: 0, misses: 0, expired: 0 };
  let page = newPageId();
  let route: string | undefined;
  let enabled = true;

  const send = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> =>
    init === undefined ? base.call(globalThis, input) : base.call(globalThis, input, init);

  const leapwire = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = enabled ? eligible(input, init, origins) : undefined;
    return url === undefined ? send(input, init) : handle(input, init, url);
  };

  async function handle(input: RequestInfo | URL, init: RequestInit | undefined, url: string): Promise<Response> {
    const signal = signalOf(input, init);
    if (signal?.aborted) {
      throw abortReason(signal);
    }
    const cached = take(url);
    if (cached) {
      counts.hits += 1;
      return toResponse(cached, url);
    }
    const entry = waitable(url);
    if (entry) {
      const part = await wait(entry, signal);
      if (part) {
        counts.waits += 1;
        return toResponse(part, url);
      }
    }
    counts.misses += 1;
    const response = await send(input, decorate(input, init));
    if (!isBundle(response)) {
      return response;
    }
    counts.bundles += 1;
    return read(response, url, input, init, signal);
  }

  function decorate(input: RequestInfo | URL, init: RequestInit | undefined): RequestInit {
    const headers = new Headers(init?.headers ?? (isRequest(input) ? input.headers : undefined));
    headers.set("Leapwire-Accept", "1");
    headers.set("Leapwire-Page", page);
    headers.set("Leapwire-Route", currentRoute());
    return { ...init, headers };
  }

  function currentRoute(): string {
    try {
      if (route !== undefined) {
        return routeHeader(route);
      }
      if (options.route) {
        return routeHeader(String(options.route()));
      }
      if (typeof location !== "undefined" && location !== null) {
        return routeHeader(location.pathname);
      }
    } catch {
      return "";
    }
    return "";
  }

  function read(
    response: Response,
    url: string,
    input: RequestInfo | URL,
    init: RequestInit | undefined,
    signal: AbortSignal | undefined,
  ): Promise<Response> {
    const bundle: Bundle = { url, origin: new URL(url).origin, planned: new Set(), detached: false, finished: false };
    bundles.add(bundle);
    return new Promise<Response>((resolve, reject) => {
      let settled = false;
      let main = false;
      let stop = noop;
      const settle = (): void => {
        settled = true;
        signal?.removeEventListener("abort", onAbort);
      };
      const onAbort = (): void => {
        if (settled) {
          return;
        }
        settle();
        finish(bundle);
        stop();
        reject(abortReason(signal));
      };
      const done = (): void => {
        finish(bundle);
        if (settled) {
          return;
        }
        settle();
        if (signal?.aborted) {
          reject(abortReason(signal));
        } else {
          resolve(send(input, init));
        }
      };
      const onLine = (text: string): boolean => {
        if (bundle.finished) {
          return false;
        }
        if (!/\S/u.test(text)) {
          return true;
        }
        const line = parseLine(text);
        if (line === null) {
          return false;
        }
        if (!main) {
          if (line.t !== "main") {
            return false;
          }
          main = true;
          if (!settled) {
            settle();
            resolve(toResponse(line, url));
          }
          return !bundle.detached;
        }
        if (bundle.detached) {
          return false;
        }
        switch (line.t) {
          case "plan":
            for (const target of line.urls) {
              announce(bundle, target);
            }
            return true;
          case "part":
            receive(bundle, line);
            return true;
          case "main":
          case "end":
            return false;
          default:
            return true;
        }
      };
      const lines = splitLines(onLine);
      const body = response.body;
      const reader =
        buffered !== true && body && typeof body.getReader === "function" && typeof TextDecoder === "function"
          ? body.getReader()
          : undefined;
      if (reader) {
        stop = () => {
          reader.cancel().catch(noop);
        };
      }
      signal?.addEventListener("abort", onAbort, { once: true });
      if (signal?.aborted) {
        onAbort();
      }
      const reading = reader
        ? pump(reader, lines)
        : response.text().then((text) => {
            lines.push(text);
            lines.end();
          });
      reading.then(done, done);
    });
  }

  function announce(bundle: Bundle, raw: string): void {
    const url = resolvePart(bundle, raw);
    if (url === undefined) {
      return;
    }
    bundle.planned.add(url);
    const entry = planned.get(url);
    if (entry) {
      entry.owners.add(bundle);
      return;
    }
    const created: Planned = { owners: new Set([bundle]), waiters: [], expires: Date.now() + ttl, timer: undefined };
    created.timer = schedule(() => {
      if (planned.get(url) === created) {
        release(url, created);
      }
    }, ttl);
    planned.set(url, created);
  }

  function receive(bundle: Bundle, line: PartLine): void {
    const url = resolvePart(bundle, line.url);
    if (url === undefined) {
      return;
    }
    bundle.planned.delete(url);
    if (line.status !== 200) {
      abandon(url, bundle);
      return;
    }
    counts.parts += 1;
    const part: Payload = { status: line.status, headers: line.headers, body: line.body };
    const entry = planned.get(url);
    if (entry) {
      planned.delete(url);
      clearTimeout(entry.timer);
      const [first, ...others] = entry.waiters;
      for (const waiter of others) {
        waiter.settle(null);
      }
      if (first) {
        first.settle(part);
        return;
      }
    }
    store(url, part);
  }

  function abandon(url: string, bundle: Bundle): void {
    const entry = planned.get(url);
    if (!entry || !entry.owners.delete(bundle)) {
      return;
    }
    if (entry.owners.size === 0) {
      release(url, entry);
    }
  }

  function release(url: string, entry: Planned): void {
    planned.delete(url);
    clearTimeout(entry.timer);
    for (const waiter of entry.waiters.splice(0)) {
      waiter.settle(null);
    }
  }

  function finish(bundle: Bundle): void {
    if (bundle.finished) {
      return;
    }
    bundle.finished = true;
    bundles.delete(bundle);
    for (const url of bundle.planned) {
      abandon(url, bundle);
    }
    bundle.planned.clear();
  }

  function store(url: string, part: Payload): void {
    const previous = cache.get(url);
    if (previous) {
      clearTimeout(previous.timer);
    }
    const entry: Cached = {
      part,
      expires: Date.now() + ttl,
      timer: schedule(() => {
        if (cache.get(url) === entry) {
          cache.delete(url);
          counts.expired += 1;
        }
      }, ttl),
    };
    cache.set(url, entry);
  }

  function take(url: string): Payload | undefined {
    const entry = cache.get(url);
    if (!entry) {
      return undefined;
    }
    cache.delete(url);
    clearTimeout(entry.timer);
    if (Date.now() >= entry.expires) {
      counts.expired += 1;
      return undefined;
    }
    return entry.part;
  }

  function waitable(url: string): Planned | undefined {
    const entry = planned.get(url);
    if (entry && Date.now() >= entry.expires) {
      release(url, entry);
      return undefined;
    }
    return entry;
  }

  function wait(entry: Planned, signal: AbortSignal | undefined): Promise<Payload | null> {
    return new Promise<Payload | null>((resolve, reject) => {
      const onAbort = (): void => {
        const index = entry.waiters.indexOf(waiter);
        if (index !== -1) {
          entry.waiters.splice(index, 1);
        }
        reject(abortReason(signal));
      };
      const waiter: Waiter = {
        settle(part) {
          signal?.removeEventListener("abort", onAbort);
          resolve(part);
        },
      };
      signal?.addEventListener("abort", onAbort, { once: true });
      entry.waiters.push(waiter);
    });
  }

  function clear(): void {
    for (const entry of cache.values()) {
      clearTimeout(entry.timer);
    }
    cache.clear();
    for (const bundle of bundles) {
      bundle.detached = true;
      bundle.planned.clear();
    }
    for (const [url, entry] of [...planned]) {
      release(url, entry);
    }
  }

  const fetcher: LeapwireFetch = Object.assign(leapwire, {
    setRoute(name: string): void {
      route = name;
      page = newPageId();
    },
    clear,
    stats(): LeapwireStats {
      return { ...counts };
    },
  });

  return {
    fetch: fetcher,
    renew(): void {
      page = newPageId();
    },
    disable(): void {
      enabled = false;
      clear();
    },
  };
}

async function pump(reader: ReadableStreamDefaultReader<Uint8Array>, lines: LineSplitter): Promise<void> {
  const decoder = new TextDecoder();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      lines.push(decoder.decode());
      lines.end();
      return;
    }
    if (!lines.push(decoder.decode(value, { stream: true }))) {
      reader.cancel().catch(noop);
      return;
    }
  }
}

function eligible(input: RequestInfo | URL, init: RequestInit | undefined, origins: Set<string>): string | undefined {
  try {
    const method = init?.method ?? (isRequest(input) ? input.method : "GET");
    if (method.toUpperCase() !== "GET") {
      return undefined;
    }
    const raw = typeof input === "string" ? input : isRequest(input) ? input.url : input.href;
    const url = new URL(raw, typeof location === "undefined" || location === null ? undefined : location.href);
    if (!origins.has(url.origin)) {
      return undefined;
    }
    url.hash = "";
    return url.href;
  } catch {
    return undefined;
  }
}

function isRequest(input: RequestInfo | URL): input is Request {
  return (
    typeof input === "object" &&
    input !== null &&
    typeof (input as Request).method === "string" &&
    typeof (input as Request).url === "string"
  );
}

function signalOf(input: RequestInfo | URL, init: RequestInit | undefined): AbortSignal | undefined {
  return init?.signal ?? (isRequest(input) ? input.signal : undefined) ?? undefined;
}

function abortReason(signal: AbortSignal | undefined): unknown {
  if (signal && signal.reason !== undefined) {
    return signal.reason;
  }
  if (typeof DOMException === "function") {
    return new DOMException("This operation was aborted", "AbortError");
  }
  const error = new Error("This operation was aborted");
  error.name = "AbortError";
  return error;
}

function isBundle(response: Response): boolean {
  return response.headers.get("Leapwire")?.trim().toLowerCase() === "bundle";
}

function toResponse(payload: Payload, url: string): Response {
  const headers = new Headers();
  for (const [name, value] of payload.headers) {
    try {
      headers.append(name, value);
    } catch {
      continue;
    }
  }
  if (!headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const empty = payload.body === null || NULL_BODY_STATUS.has(payload.status);
  const response = new Response(empty ? null : payload.body, { status: payload.status, headers });
  Object.defineProperty(response, "url", { value: url, configurable: true, enumerable: true });
  return response;
}

function allowedOrigins(list: string[] | undefined): Set<string> {
  const values = list ?? (typeof location === "undefined" || location === null ? [] : [location.origin]);
  const origins = new Set<string>();
  for (const value of values) {
    try {
      const origin = new URL(value).origin;
      if (origin !== "null") {
        origins.add(origin);
      }
    } catch {
      continue;
    }
  }
  return origins;
}

function resolvePart(bundle: Bundle, raw: string): string | undefined {
  try {
    const url = new URL(raw, bundle.url);
    if (url.origin !== bundle.origin) {
      return undefined;
    }
    url.hash = "";
    return url.href;
  } catch {
    return undefined;
  }
}

function schedule(task: () => void, delay: number): Timer {
  const timer = setTimeout(task, delay);
  (timer as unknown as { unref?: () => void }).unref?.();
  return timer;
}
