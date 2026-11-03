const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";

const ROUTE_LIMIT = 256;

export function newPageId(): string {
  const source = typeof crypto === "undefined" ? undefined : crypto;
  if (typeof source?.randomUUID === "function") {
    return source.randomUUID();
  }
  const bytes = new Uint8Array(22);
  if (typeof source?.getRandomValues === "function") {
    source.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  let id = "";
  for (const byte of bytes) {
    id += ALPHABET.charAt(byte & 63);
  }
  return id;
}

export function routeHeader(route: string): string {
  const encoded = route.replace(/[^\x20-\x7e]/gu, encodeChar);
  if (encoded.length <= ROUTE_LIMIT) {
    return encoded;
  }
  return encoded.slice(0, ROUTE_LIMIT).replace(/%[0-9A-Fa-f]?$/u, "");
}

function encodeChar(char: string): string {
  try {
    return encodeURIComponent(char);
  } catch {
    return "%EF%BF%BD";
  }
}

export function watchNavigation(onChange: () => void): () => void {
  const undo: (() => void)[] = [];
  if (typeof history !== "undefined" && history !== null) {
    const target = history;
    for (const name of ["pushState", "replaceState"] as const) {
      const original = target[name];
      if (typeof original !== "function") {
        continue;
      }
      const own = Object.getOwnPropertyDescriptor(target, name);
      let active = true;
      const patched = function (this: History, ...args: Parameters<History["pushState"]>): void {
        original.apply(this, args);
        if (active) {
          onChange();
        }
      };
      target[name] = patched;
      undo.push(() => {
        active = false;
        if (target[name] !== patched) {
          return;
        }
        if (own) {
          Object.defineProperty(target, name, own);
        } else {
          Reflect.deleteProperty(target, name);
        }
      });
    }
  }
  if (typeof window !== "undefined" && window !== null && typeof window.addEventListener === "function") {
    const target = window;
    target.addEventListener("popstate", onChange);
    undo.push(() => target.removeEventListener("popstate", onChange));
  }
  return () => {
    for (const step of undo) {
      step();
    }
  };
}
