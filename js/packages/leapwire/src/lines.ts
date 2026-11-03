export type Header = [string, string];

export interface Payload {
  status: number;
  headers: Header[];
  body: string | null;
}

export interface MainLine extends Payload {
  t: "main";
}

export interface PlanLine {
  t: "plan";
  urls: string[];
}

export interface PartLine extends Payload {
  t: "part";
  url: string;
}

export interface EndLine {
  t: "end";
}

export interface OtherLine {
  t: "other";
}

export type Line = MainLine | PlanLine | PartLine | EndLine | OtherLine;

export interface LineSplitter {
  push(text: string): boolean;
  end(): boolean;
}

const QUOTE = 0x22;
const BACKSLASH = 0x5c;
const COMMA = 0x2c;
const OPEN_BRACE = 0x7b;
const CLOSE_BRACE = 0x7d;
const OPEN_BRACKET = 0x5b;
const CLOSE_BRACKET = 0x5d;

export function splitLines(onLine: (line: string) => boolean): LineSplitter {
  let rest = "";
  let open = true;
  const emit = (line: string): void => {
    if (!onLine(line)) {
      open = false;
    }
  };
  return {
    push(text: string): boolean {
      if (!open) {
        return false;
      }
      let start = 0;
      let index = text.indexOf("\n");
      while (index !== -1) {
        const line = rest + text.slice(start, index);
        rest = "";
        emit(line);
        if (!open) {
          return false;
        }
        start = index + 1;
        index = text.indexOf("\n", start);
      }
      rest += text.slice(start);
      return true;
    },
    end(): boolean {
      const line = rest;
      rest = "";
      if (open && line !== "") {
        emit(line);
      }
      return open;
    },
  };
}

export function parseLine(text: string): Line | null {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return null;
  }
  if (!isRecord(value) || typeof value.t !== "string") {
    return null;
  }
  switch (value.t) {
    case "main":
      return parseMain(text, value);
    case "plan":
      return parsePlan(value);
    case "part":
      return parsePart(text, value);
    case "end":
      return { t: "end" };
    default:
      return { t: "other" };
  }
}

function parseMain(text: string, value: Record<string, unknown>): MainLine | null {
  const status = value.status;
  const headers = headerList(value.headers);
  if (typeof status !== "number" || !Number.isInteger(status) || status < 200 || status > 599 || headers === null) {
    return null;
  }
  return { t: "main", status, headers, body: bodyText(text, value) };
}

function parsePlan(value: Record<string, unknown>): PlanLine | null {
  const urls = value.urls;
  if (!Array.isArray(urls) || !urls.every((url) => typeof url === "string")) {
    return null;
  }
  return { t: "plan", urls: urls as string[] };
}

function parsePart(text: string, value: Record<string, unknown>): PartLine | null {
  const { url, status } = value;
  const headers = headerList(value.headers);
  if (typeof url !== "string" || typeof status !== "number" || headers === null) {
    return null;
  }
  const body = bodyText(text, value);
  if (status === 200 && body === null) {
    return null;
  }
  return { t: "part", url, status, headers, body };
}

function headerList(value: unknown): Header[] | null {
  if (value === undefined || value === null) {
    return [];
  }
  if (!isRecord(value)) {
    return null;
  }
  const list: Header[] = [];
  for (const [name, entry] of Object.entries(value)) {
    if (typeof entry === "string") {
      list.push([name, entry]);
    }
  }
  return list;
}

function bodyText(text: string, value: Record<string, unknown>): string | null {
  if (!("body" in value)) {
    return null;
  }
  return rawMember(text, "body") ?? JSON.stringify(value.body);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function rawMember(text: string, key: string): string | undefined {
  let index = skipSpace(text, 0);
  if (text.charCodeAt(index) !== OPEN_BRACE) {
    return undefined;
  }
  index = skipSpace(text, index + 1);
  let found: string | undefined;
  while (index < text.length && text.charCodeAt(index) === QUOTE) {
    const nameEnd = stringEnd(text, index);
    const name: unknown = JSON.parse(text.slice(index, nameEnd));
    const start = skipSpace(text, skipSpace(text, nameEnd) + 1);
    const end = valueEnd(text, start);
    if (name === key) {
      found = text.slice(start, end);
    }
    index = skipSpace(text, end);
    if (text.charCodeAt(index) === COMMA) {
      index = skipSpace(text, index + 1);
    }
  }
  return found;
}

function skipSpace(text: string, start: number): number {
  let index = start;
  while (index < text.length && isSpace(text.charCodeAt(index))) {
    index += 1;
  }
  return index;
}

function isSpace(code: number): boolean {
  return code === 0x20 || code === 0x09 || code === 0x0a || code === 0x0d;
}

function stringEnd(text: string, start: number): number {
  let index = start + 1;
  while (index < text.length) {
    const code = text.charCodeAt(index);
    if (code === BACKSLASH) {
      index += 2;
    } else if (code === QUOTE) {
      return index + 1;
    } else {
      index += 1;
    }
  }
  return index;
}

function valueEnd(text: string, start: number): number {
  const first = text.charCodeAt(start);
  if (first === QUOTE) {
    return stringEnd(text, start);
  }
  let index = start;
  if (first !== OPEN_BRACE && first !== OPEN_BRACKET) {
    while (index < text.length && !isScalarEnd(text.charCodeAt(index))) {
      index += 1;
    }
    return index;
  }
  let depth = 0;
  while (index < text.length) {
    const code = text.charCodeAt(index);
    if (code === QUOTE) {
      index = stringEnd(text, index);
      continue;
    }
    if (code === OPEN_BRACE || code === OPEN_BRACKET) {
      depth += 1;
    } else if (code === CLOSE_BRACE || code === CLOSE_BRACKET) {
      depth -= 1;
      if (depth === 0) {
        return index + 1;
      }
    }
    index += 1;
  }
  return index;
}

function isScalarEnd(code: number): boolean {
  return code === COMMA || code === CLOSE_BRACE || code === CLOSE_BRACKET || isSpace(code);
}
