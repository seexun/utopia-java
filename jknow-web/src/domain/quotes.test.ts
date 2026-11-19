import { describe, expect, it } from "vitest";
import { QUOTE_PARAM_MAX, docSearch, quoteParam, sourceLead } from "./quotes";

/* 原文切段的用例已随实现迁到 utils/highlightSegments.test.ts
   （reskin-b4：四份本地切段归一）。 */

describe("a source's lead in the list", () => {
  it("is its first quoted sentence, else the passage's opening", () => {
    expect(sourceLead({ excerpt: "Project Aurora started", quotes: ["Zhang San leads"] })).toBe(
      "Zhang San leads",
    );
    expect(sourceLead({ excerpt: "Project Aurora started" })).toBe("Project Aurora started");
  });
});

describe("the document page's search", () => {
  it("keeps the chunk and a non-blank quote", () => {
    expect(docSearch({ chunk: "c1", quote: "Li Si leads" })).toEqual({
      chunk: "c1",
      quote: "Li Si leads",
    });
    expect(docSearch({ chunk: "c1", quote: "  " })).toEqual({ chunk: "c1", quote: undefined });
    expect(docSearch({ chunk: 3, quote: ["x"] })).toEqual({ chunk: undefined, quote: undefined });
  });
});

describe("quoteParam", () => {
  it("keeps a sentence short enough for the address bar and drops a longer one whole", () => {
    expect(quoteParam("Li Si leads Project Aurora.")).toBe("Li Si leads Project Aurora.");
    expect(quoteParam("界".repeat(QUOTE_PARAM_MAX))).toBe("界".repeat(QUOTE_PARAM_MAX));
    expect(quoteParam("界".repeat(QUOTE_PARAM_MAX + 1))).toBeUndefined();
    expect(quoteParam("   ")).toBeUndefined();
    expect(docSearch({ chunk: "c", quote: "x".repeat(QUOTE_PARAM_MAX + 1) })).toEqual({
      chunk: "c",
      quote: undefined,
    });
  });
});
