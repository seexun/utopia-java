import { describe, expect, it } from "vitest";
import { highlightSegments } from "./highlightSegments";

const text =
  "Project Aurora started on 2023-01-10.\n\nZhang San leads Project Aurora from 2023-01-10.";

describe("highlightSegments", () => {
  it("mark the sentences a source quotes where they stand in the passage", () => {
    expect(
      highlightSegments(text, ["Zhang San leads Project Aurora from 2023-01-10."])
    ).toEqual([
      { text: "Project Aurora started on 2023-01-10.\n\n", hit: false },
      { text: "Zhang San leads Project Aurora from 2023-01-10.", hit: true },
    ]);
  });

  it("match a term's spaces flexibly across the passage's own line breaks", () => {
    expect(
      highlightSegments("Li Si leads\nProject Aurora.", ["Li Si leads Project Aurora."])
    ).toEqual([{ text: "Li Si leads\nProject Aurora.", hit: true }]);
  });

  it("leave the passage as it is when nothing matches (or no terms)", () => {
    expect(highlightSegments(text, ["Wang Wu advises Project Aurora."])).toEqual([
      { text, hit: false },
    ]);
    expect(highlightSegments(text, [])).toEqual([{ text, hit: false }]);
    expect(highlightSegments(text, ["   "])).toEqual([{ text, hit: false }]);
  });

  it("mark every hit of every term, longest first", () => {
    // 「星辰智造」长词优先，免得先被单字「星」吃掉（检索页的排序语义）
    expect(
      highlightSegments("星辰智造的星辰与智造", ["星", "智", "星辰智造"]).filter((s) => s.hit).map((s) => s.text)
    ).toEqual(["星辰智造", "星", "智"]);
    // 多词全标（检索/图谱共同的 matchAll 语义）
    expect(
      highlightSegments(text, ["Zhang San", "Project Aurora"]).filter((s) => s.hit).map((s) => s.text)
    ).toEqual(["Project Aurora", "Zhang San", "Project Aurora"]);
    // 切段拼回原文
    expect(
      highlightSegments(text, ["Zhang San", "Project Aurora"]).map((s) => s.text).join("")
    ).toBe(text);
  });

  it("read a term with the characters a pattern would take as syntax", () => {
    expect(
      highlightSegments("Revenue (USD) rose 3.5% [est.]", ["(USD) rose 3.5% [est.]"]).filter(
        (s) => s.hit
      )
    ).toEqual([{ text: "(USD) rose 3.5% [est.]", hit: true }]);
  });

  it("match case-insensitively (docs 摘要高亮的 ig 语义)", () => {
    expect(
      highlightSegments("project aurora started", ["Project Aurora"]).filter((s) => s.hit)
    ).toEqual([{ text: "project aurora", hit: true }]);
  });
});
