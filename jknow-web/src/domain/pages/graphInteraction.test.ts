// 图谱页交互状态机（任务 9）的语义钉：面板指边优先级 / 聚焦强度步进 /
// 节点角色判定序（hovered/selected/dragging 的派生态）/ 拖拽钉位 /
// 派生边时序（入场-开关渐变-呼吸）/ 逆关系说法并读。
// 与 graphVisuals.test.ts 同一口径：决策序走样即语义错误，逐例钉住。

import { describe, expect, it } from "vitest";
import {
  panelFocus,
  focusRampTarget,
  focusRampStep,
  resolveNodeRole,
  derivedPhase,
  triangleWave,
  mergeAlsoLabels,
  pinnedPosition,
  type InteractionSnapshot,
  type NodeFacts
} from "./graphInteraction";

const st = (over: Partial<InteractionSnapshot> = {}): InteractionSnapshot => ({
  selected: null,
  hovered: null,
  panelEdge: null,
  panelEdgeActive: false,
  activeNodes: null,
  smallLib: false,
  ...over
});

const facts = (over: Partial<NodeFacts> = {}): NodeFacts => ({
  isPanelEndpoint: false,
  isSelNeighbor: false,
  isHoverNeighbor: false,
  ...over
});

describe("panelFocus：面板此刻在指哪条边", () => {
  const pin = { entity: "E1", fact: "F1" };

  it("指着的那一行优先于钉住的", () => {
    expect(panelFocus("F2", pin, null, "E1")).toBe("F2");
  });

  it("钉住的只在钉它的那个实体还选着时作数", () => {
    expect(panelFocus(null, pin, null, "E1")).toBe("F1");
    expect(panelFocus(null, pin, null, "E2")).toBeNull();
    expect(panelFocus(null, pin, null, null)).toBeNull();
  });

  it("画布或面板正指着某个节点时让位——「它连着谁」要照常读得出", () => {
    expect(panelFocus(null, pin, "N9", "E1")).toBeNull();
  });

  it("什么都没有就是 null", () => {
    expect(panelFocus(null, null, null, null)).toBeNull();
  });
});

describe("focusRamp：聚焦强度的目标与步进（走查微调 7）", () => {
  it("有任何焦点（悬停/选中/面板指边）目标就是 1", () => {
    expect(focusRampTarget({ hovered: null, selected: null, panelEdge: null })).toBe(0);
    expect(focusRampTarget({ hovered: "n1", selected: null, panelEdge: null })).toBe(1);
    expect(focusRampTarget({ hovered: null, selected: "n1", panelEdge: null })).toBe(1);
    expect(focusRampTarget({ hovered: null, selected: null, panelEdge: "e1" })).toBe(1);
  });

  it("每帧走 frameMs/rampMs，不越界", () => {
    expect(focusRampStep(0, 1, 16, 240)).toBeCloseTo(16 / 240);
    expect(focusRampStep(1, 1, 16, 240)).toBe(1);
    expect(focusRampStep(0, 0, 16, 240)).toBe(0);
    expect(focusRampStep(0.98, 1, 16, 240)).toBeLessThanOrEqual(1);
    expect(focusRampStep(0.02, 0, 16, 240)).toBeGreaterThanOrEqual(0);
  });

  it("半路反向：目标换了就朝反方向走", () => {
    expect(focusRampStep(0.5, 0, 16, 240)).toBeCloseTo(0.5 - 16 / 240);
  });
});

describe("resolveNodeRole：reducer 决策序", () => {
  it("选中压过指到：自己刚选中的节点仍按选中画", () => {
    const role = resolveNodeRole(
      st({ selected: "n1", hovered: "n1" }),
      "n1",
      facts()
    );
    expect(role).toBe("selected");
  });

  it("面板指边的端点按「指到」画，压过悬停", () => {
    const role = resolveNodeRole(
      st({ hovered: "n1", panelEdge: "e1", panelEdgeActive: true }),
      "n2",
      facts({ isPanelEndpoint: true })
    );
    expect(role).toBe("panelEndpoint");
  });

  it("面板指边时其余节点退半档（softMuted）——不相干的跟着亮就是答非所问", () => {
    expect(
      resolveNodeRole(
        st({ panelEdge: "e1", panelEdgeActive: true, smallLib: false }),
        "n9",
        facts()
      )
    ).toBe("softMuted");
    // 小库同样不清名，只轻降灰（走查微调 4）
    expect(
      resolveNodeRole(
        st({ panelEdge: "e1", panelEdgeActive: true, smallLib: true }),
        "n9",
        facts()
      )
    ).toBe("dimmed");
  });

  it("选中状态下指边：非端点仍退半档，端点照常按指到画", () => {
    const snap = st({
      selected: "n1",
      panelEdge: "e1",
      panelEdgeActive: true,
      smallLib: false
    });
    expect(
      resolveNodeRole(snap, "n9", facts({ isSelNeighbor: true }))
    ).toBe("softMuted");
    expect(
      resolveNodeRole(snap, "n2", facts({ isPanelEndpoint: true }))
    ).toBe("panelEndpoint");
  });

  it("面板指边时不压正被悬停的那个节点；没有指边时该分支不生效", () => {
    expect(
      resolveNodeRole(
        st({ hovered: "n2", panelEdge: "e1", panelEdgeActive: true }),
        "n2",
        facts()
      )
    ).toBe("hovered");
    // 无指边：其余照旧 default（老路径不回归）
    expect(resolveNodeRole(st({ panelEdge: "e1" }), "n9", facts())).toBe(
      "default"
    );
  });

  it("悬停自己就是 hovered", () => {
    expect(resolveNodeRole(st({ hovered: "n1" }), "n1", facts())).toBe("hovered");
  });

  it("选中后邻居留出；悬停的邻居也留出（亮边不通向黑点）", () => {
    const snap = st({ selected: "n1", hovered: "n2", smallLib: false });
    expect(resolveNodeRole(snap, "n3", facts({ isSelNeighbor: true }))).toBe("neighbor");
    expect(resolveNodeRole(snap, "n4", facts({ isHoverNeighbor: true }))).toBe("neighbor");
  });

  it("选中后其余压到底（大库 muted / 小库 dimmed 不清名）", () => {
    const big = st({ selected: "n1", smallLib: false });
    const small = st({ selected: "n1", smallLib: true });
    expect(resolveNodeRole(big, "n9", facts())).toBe("muted");
    expect(resolveNodeRole(small, "n9", facts())).toBe("dimmed");
  });

  it("悬停压其余只压一档（softMuted），小库仍是 dimmed", () => {
    expect(
      resolveNodeRole(st({ hovered: "n1", smallLib: false }), "n9", facts())
    ).toBe("softMuted");
    expect(
      resolveNodeRole(st({ hovered: "n1", smallLib: true }), "n9", facts())
    ).toBe("dimmed");
  });

  it("悬停时「此刻不存在」的非邻居直接压到底——只压一半反而比不 hover 更亮", () => {
    const snap = st({
      hovered: "n1",
      smallLib: false,
      activeNodes: new Set(["n1", "n2"])
    });
    expect(
      resolveNodeRole(snap, "n9", facts()) // n9 不在 activeNodes
    ).toBe("muted");
  });

  it("默认档过时间滤门：此刻不在的大库压到底、小库轻降灰", () => {
    const snap = st({ activeNodes: new Set(["n1"]) });
    expect(resolveNodeRole(snap, "n2", facts())).toBe("muted");
    expect(
      resolveNodeRole({ ...snap, smallLib: true }, "n2", facts())
    ).toBe("dimmed");
    expect(resolveNodeRole(snap, "n1", facts())).toBe("default");
  });

  it("邻居也过时间滤门（节点亮着、线却没有 = 两边不一致）", () => {
    const snap = st({
      selected: "n1",
      activeNodes: new Set(["n1"]),
      smallLib: false
    });
    expect(
      resolveNodeRole(snap, "n2", facts({ isSelNeighbor: true }))
    ).toBe("muted");
  });
});

describe("pinnedPosition：FA2 回写位（拖哪钉哪）", () => {
  const placed = new Map([
    ["a", { x: 1, y: 2 }]
  ]);

  it("被拖的钉在光标上（不闪）", () => {
    expect(pinnedPosition("a", "a", { x: 9, y: 9 }, placed)).toEqual({ x: 9, y: 9 });
  });

  it("摆过的钉在松手位——弹簧不再把它拉回", () => {
    expect(pinnedPosition("a", null, null, placed)).toEqual({ x: 1, y: 2 });
  });

  it("被拖节点若同时摆过，光标赢（拖拽进行中）", () => {
    expect(pinnedPosition("a", "a", { x: 5, y: 5 }, placed)).toEqual({ x: 5, y: 5 });
  });

  it("其余随模拟（null）", () => {
    expect(pinnedPosition("b", null, null, placed)).toBeNull();
    expect(pinnedPosition("b", "a", { x: 5, y: 5 }, placed)).toBeNull();
  });
});

describe("derivedPhase：派生边的入场与开关时序", () => {
  const now = 10_000;

  it("入场时序没到：事实先落位，推出来的后到", () => {
    expect(
      derivedPhase({
        revealed: false,
        showDerived: true,
        toggle: null,
        now,
        toggleMs: 420
      })
    ).toEqual({ kind: "concealed" });
  });

  it("没过渡在飞：开着 shown / 关着 hidden", () => {
    expect(
      derivedPhase({ revealed: true, showDerived: true, toggle: null, now, toggleMs: 420 })
    ).toEqual({ kind: "shown" });
    expect(
      derivedPhase({ revealed: true, showDerived: false, toggle: null, now, toggleMs: 420 })
    ).toEqual({ kind: "hidden" });
  });

  it("淡入在飞：on=true 且 k 随时间推进", () => {
    const p = derivedPhase({
      revealed: true,
      showDerived: true,
      toggle: { at: now - 100, on: true },
      now,
      toggleMs: 420
    });
    expect(p).toEqual({ kind: "fade", k: 100 / 420, on: true });
  });

  it("淡出在飞：只有「尚未走完」才留着不藏；走完即 hidden", () => {
    const flying = derivedPhase({
      revealed: true,
      showDerived: false,
      toggle: { at: now - 100, on: false },
      now,
      toggleMs: 420
    });
    expect(flying).toEqual({ kind: "fade", k: 100 / 420, on: false });
    expect(
      derivedPhase({
        revealed: true,
        showDerived: false,
        toggle: { at: now - 500, on: false },
        now,
        toggleMs: 420
      })
    ).toEqual({ kind: "hidden" });
    // 半途又打开的过渡（on=true）对「关着」不是淡出
    expect(
      derivedPhase({
        revealed: true,
        showDerived: false,
        toggle: { at: now - 100, on: true },
        now,
        toggleMs: 420
      })
    ).toEqual({ kind: "hidden" });
  });

  it("k 夹在 1：超时的过渡不再推进", () => {
    const p = derivedPhase({
      revealed: true,
      showDerived: true,
      toggle: { at: now - 10_000, on: true },
      now,
      toggleMs: 420
    });
    expect(p).toEqual({ kind: "shown" });
  });
});

describe("triangleWave：呼吸三角波两端各停一瞬", () => {
  const P = 2200;
  it("周期两端最亮，中点最暗", () => {
    expect(triangleWave(0, P)).toBe(1);
    expect(triangleWave(P, P)).toBe(1);
    expect(triangleWave(P / 2, P)).toBe(0);
  });

  it("越界取模循环", () => {
    expect(triangleWave(P * 3 + P / 2, P)).toBe(0);
  });
});

describe("mergeAlsoLabels：逆关系说法并读", () => {
  it("接在本名后面：PART OF ⁻¹ CONTAINS", () => {
    expect(mergeAlsoLabels("part of", ["contains", "has part"])).toBe(
      "part of ⁻¹ contains / has part"
    );
  });

  it("没有说法就返回 null（标签不动）", () => {
    expect(mergeAlsoLabels("part of", [])).toBeNull();
    expect(mergeAlsoLabels("part of", undefined)).toBeNull();
  });

  it("争议前置 ⚠ 的标签照并（⚠ 已在本名里）", () => {
    expect(mergeAlsoLabels("⚠ part of", ["contains"])).toBe("⚠ part of ⁻¹ contains");
  });
});
