import { createClient, type LeapwireFetch, type LeapwireOptions } from "./client.js";
import { watchNavigation } from "./env.js";

export type { LeapwireFetch, LeapwireOptions, LeapwireStats } from "./client.js";

export function createFetch(base?: typeof fetch, options?: LeapwireOptions): LeapwireFetch {
  return createClient(resolveBase(base), options).fetch;
}

export function install(options?: LeapwireOptions): LeapwireFetch & { uninstall(): void } {
  const original = resolveBase(undefined);
  const previous = Object.getOwnPropertyDescriptor(globalThis, "fetch");
  const client = createClient(original, options);
  let active = true;
  const installed = Object.assign(client.fetch, {
    uninstall(): void {
      if (!active) {
        return;
      }
      active = false;
      unwatch();
      client.disable();
      if (globalThis.fetch !== installed) {
        return;
      }
      if (previous) {
        Object.defineProperty(globalThis, "fetch", previous);
      } else {
        Reflect.deleteProperty(globalThis, "fetch");
      }
    },
  });
  Object.defineProperty(globalThis, "fetch", {
    value: installed,
    writable: true,
    configurable: true,
    enumerable: previous?.enumerable ?? true,
  });
  const unwatch = watchNavigation(client.renew);
  return installed;
}

function resolveBase(base: typeof fetch | undefined): typeof fetch {
  const resolved = base ?? (typeof fetch === "function" ? fetch : undefined);
  if (typeof resolved !== "function") {
    throw new TypeError("leapwire needs a fetch implementation: pass one to createFetch or define globalThis.fetch");
  }
  return resolved;
}
