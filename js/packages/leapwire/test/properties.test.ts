import { describe, expect, it } from "vitest";
import fc from "fast-check";
import { createFetch, type LeapwireStats } from "../src/index.js";
import { parseLine, splitLines } from "../src/lines.js";
import { bundle, chunked, flush, network } from "./helpers.js";

const value = fc.jsonValue({ stringUnit: "grapheme", maxDepth: 3 });

const scenario = fc.record({
  main: value,
  parts: fc.array(fc.record({ body: value, ok: fc.boolean() }), { maxLength: 5 }),
  sizes: fc.array(fc.integer({ min: 1, max: 48 }), { minLength: 1, maxLength: 8 }),
  eol: fc.constantFrom("\n", "\r\n"),
  trailing: fc.boolean(),
});

interface Outcome {
  main: string;
  parts: string[];
  stats: LeapwireStats;
  calls: number;
}

async function run(text: string, sizes: number[] | null, count: number): Promise<Outcome> {
  const net = network();
  let drained: () => void = () => undefined;
  const done = new Promise<void>((resolve) => {
    drained = resolve;
  });
  const bytes = new TextEncoder().encode(text);
  net.reply("/api/main", () => (sizes ? bundle(chunked(bytes, sizes, drained)) : bundle(text)));
  const leapwire = createFetch(net.base, { buffered: sizes === null });
  const main = await (await leapwire("/api/main")).text();
  if (sizes) {
    await done;
  }
  await flush();
  const parts: string[] = [];
  for (let i = 0; i < count; i += 1) {
    parts.push(await (await leapwire(`/api/p/${i}`)).text());
  }
  return { main, parts, stats: leapwire.stats(), calls: net.calls.length };
}

describe("properties", () => {
  it("streams with any chunk boundaries give exactly the results of reading the whole bundle", async () => {
    await fc.assert(
      fc.asyncProperty(scenario, async ({ main, parts, sizes, eol, trailing }) => {
        const urls = parts.map((_, i) => `/api/p/${i}`);
        const lines = [
          { t: "main", status: 200, headers: { "content-type": "application/json" }, body: main },
          { t: "plan", urls },
          ...parts.map((part, i) =>
            part.ok
              ? { t: "part", url: urls[i], status: 200, headers: { "content-type": "application/json" }, body: part.body }
              : { t: "part", url: urls[i], status: 0 },
          ),
          { t: "end", parts: parts.filter((part) => part.ok).length, skipped: parts.filter((part) => !part.ok).length },
        ];
        const text = lines.map((line) => JSON.stringify(line)).join(eol) + (trailing ? eol : "");
        const expected = parts.map((part, i) =>
          part.ok ? JSON.stringify(part.body) : JSON.stringify({ from: "network", path: urls[i] }),
        );

        const streamed = await run(text, sizes, parts.length);
        const buffered = await run(text, null, parts.length);

        expect(streamed.main).toBe(JSON.stringify(main));
        expect(streamed.parts).toEqual(expected);
        expect(streamed).toEqual(buffered);
        expect(streamed.stats.hits).toBe(parts.filter((part) => part.ok).length);
        expect(streamed.calls).toBe(1 + parts.filter((part) => !part.ok).length);
      }),
      { numRuns: 150 },
    );
  });

  it("splitLines yields the same lines wherever the text is cut", () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ unit: "grapheme" }).map((s) => s.replace(/\n/gu, "")), { maxLength: 12 }),
        fc.boolean(),
        fc.array(fc.integer({ min: 0, max: 200 }), { maxLength: 10 }),
        (items, trailing, cuts) => {
          const text = items.join("\n") + (trailing ? "\n" : "");
          const seen: string[] = [];
          const splitter = splitLines((line) => {
            seen.push(line);
            return true;
          });
          const points = [...new Set(cuts.map((cut) => Math.min(cut, text.length)))].sort((a, b) => a - b);
          let start = 0;
          for (const point of points) {
            splitter.push(text.slice(start, point));
            start = point;
          }
          splitter.push(text.slice(start));
          splitter.end();
          const expected = text.split("\n");
          if (expected[expected.length - 1] === "") {
            expected.pop();
          }
          expect(seen).toEqual(expected);
        },
      ),
      { numRuns: 300 },
    );
  });

  it("parseLine returns the body text exactly as written, whatever the key order and spacing", () => {
    fc.assert(
      fc.property(value, fc.boolean(), fc.constantFrom(undefined, 1, "\t"), (body, bodyFirst, space) => {
        const fields = { t: "part", url: "/x", status: 200, headers: { a: "1" } };
        const record = bodyFirst ? { body, ...fields } : { ...fields, body };
        const compact = parseLine(JSON.stringify(record));
        const spaced = parseLine(JSON.stringify(record, null, space));

        expect(compact).toMatchObject({ t: "part", url: "/x", status: 200, headers: [["a", "1"]] });
        expect(compact?.t === "part" && compact.body).toBe(JSON.stringify(body));
        expect(spaced?.t === "part" && spaced.body !== null && JSON.parse(spaced.body)).toEqual(
          JSON.parse(JSON.stringify(body)),
        );
      }),
      { numRuns: 300 },
    );
  });
});
