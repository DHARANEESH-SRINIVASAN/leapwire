import { vi } from "vitest";

export const ORIGIN = "http://localhost:3000";

export const BUNDLE_HEADERS = {
  "Content-Type": "application/x-ndjson",
  Leapwire: "bundle",
  "Cache-Control": "no-store",
  Vary: "Leapwire-Accept",
};

export function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
}

export function ndjson(lines: unknown[]): string {
  return lines.map((line) => JSON.stringify(line)).join("\n") + "\n";
}

export function bundle(body: BodyInit | null): Response {
  return new Response(body, { headers: BUNDLE_HEADERS });
}

export function pathOf(input: RequestInfo | URL): string {
  const raw = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  const url = new URL(raw, ORIGIN);
  return url.origin === ORIGIN ? url.pathname + url.search : url.href;
}

export function sentHeaders(input: RequestInfo | URL, init: RequestInit | undefined): Headers {
  return new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined));
}

export interface Call {
  input: RequestInfo | URL;
  init: RequestInit | undefined;
  args: number;
  path: string;
  headers: Headers;
}

export function network() {
  const calls: Call[] = [];
  const queues = new Map<string, (() => Response | Promise<Response>)[]>();
  const base = vi.fn(async function (input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
    const path = pathOf(input);
    calls.push({ input, init, args: arguments.length, path, headers: sentHeaders(input, init) });
    const next = queues.get(path)?.shift();
    return next ? next() : json({ from: "network", path });
  });
  return {
    base,
    calls,
    reply(path: string, respond: () => Response | Promise<Response>): void {
      const queue = queues.get(path) ?? [];
      queue.push(respond);
      queues.set(path, queue);
    },
  };
}

export interface Controlled {
  stream: ReadableStream<Uint8Array>;
  push(text: string): void;
  close(): void;
  fail(error: unknown): void;
  cancelled: Promise<unknown>;
}

export function controlled(): Controlled {
  const encoder = new TextEncoder();
  let controller: ReadableStreamDefaultController<Uint8Array> | undefined;
  let onCancel: (reason: unknown) => void = () => undefined;
  const cancelled = new Promise<unknown>((resolve) => {
    onCancel = resolve;
  });
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
    cancel(reason) {
      onCancel(reason);
    },
  });
  return {
    stream,
    push: (text) => controller?.enqueue(encoder.encode(text)),
    close: () => controller?.close(),
    fail: (error) => controller?.error(error),
    cancelled,
  };
}

export function chunked(bytes: Uint8Array, sizes: number[], onDrained: () => void = () => undefined): ReadableStream<Uint8Array> {
  let offset = 0;
  let turn = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (offset >= bytes.length) {
        controller.close();
        onDrained();
        return;
      }
      const size = sizes[turn++ % sizes.length] ?? 1;
      controller.enqueue(bytes.slice(offset, offset + size));
      offset += size;
    },
  });
}

export function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

export const MAIN = {
  t: "main",
  status: 200,
  headers: { "content-type": "application/json", etag: '"v1"' },
  body: { id: 42, customerId: 19 },
};

export const PLAN = { t: "plan", urls: ["/api/customers/19", "/api/orders/42/lines"] };

export const CUSTOMER = {
  t: "part",
  url: "/api/customers/19",
  status: 200,
  headers: { "content-type": "application/json" },
  body: { id: 19, name: "Ada" },
};

export const LINES = {
  t: "part",
  url: "/api/orders/42/lines",
  status: 200,
  headers: { "content-type": "application/json" },
  body: [{ productId: 501 }, { productId: 502 }],
};

export const END = { t: "end", parts: 2, skipped: 0 };
