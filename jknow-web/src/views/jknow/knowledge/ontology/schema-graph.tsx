/* 本体模式图画布（对照旧仓 pages/OntologySchemaGraph.tsx 的画布面，W1 静态视觉）：
   节点胶囊/白描边实色、三种边语义的色与形、图例药丸、缩放塔、点选。
   画布机器（graphCanvas）与视觉常量（graphVisuals）在 domain，两页共用。
   悬停压暗/拖拽/搜索补景属任务 9。 */
import {
  computed,
  defineComponent,
  onBeforeUnmount,
  ref,
  watch,
  type PropType
} from "vue";
import { circular } from "graphology-layout";
import forceAtlas2 from "graphology-layout-forceatlas2";
import Sigma from "sigma";
import { EdgeArrowProgram, EdgeLineProgram } from "sigma/rendering";
import EdgeCurveProgram, { EdgeCurvedArrowProgram } from "@sigma/edge-curve";
import Maximize2Icon from "~icons/lucide/maximize-2";
import ZoomInIcon from "~icons/lucide/zoom-in";
import ZoomOutIcon from "~icons/lucide/zoom-out";
import {
  drawWorldGrid,
  EDGE_SUBCLASS,
  EDGE_SUBCLASS_FOCUS,
  EDGE_RELATION,
  EDGE_RELATION_FOCUS,
  EDGE_DISJOINT,
  EDGE_DISJOINT_FOCUS,
  EDGE_RULE,
  EDGE_RULE_FOCUS,
  EDGE_SCHEMA_DIM as EDGE_DIM,
  refreshPalette
} from "@/domain/pages/graphVisuals";
import {
  selectedNode,
  sigmaOptions,
  withTopLayer
} from "@/domain/pages/graphCanvas";
import { S } from "@/i18n";
import { ToolButton, ToolDivider, ToolTower } from "@/components/knowledge/kit";
import {
  buildSchemaGraph,
  EDGE_TYPE_ARROW,
  EDGE_TYPE_CURVED_ARROW,
  EDGE_TYPE_CURVED_LINE,
  EDGE_TYPE_LINE,
  MIN_EDGE_THICKNESS,
  NODE_SIZE_ROOT,
  NODE_SIZE_STEP,
  SCHEMA_BUDGETS
} from "./schema";

export interface SchemaSelection {
  kind: "class" | "relation" | "rule";
  id: string;
}

/* 画布闭包里的边语义标记（与 schema.ts 同源常量，逐字） */
const SUBCLASS_KIND = "subclass";
const RELATION_KIND = "relation";
const DISJOINT_KIND = "disjoint";
const RULE_KIND = "rule";

/** 图例条目：线样与画布同语言（旧仓 LEGEND_* 常量的渲染面） */
function edgeColorFor(kind: string, dim: boolean, focused: boolean): string {
  if (kind === SUBCLASS_KIND)
    return focused ? EDGE_SUBCLASS_FOCUS : dim ? EDGE_DIM : EDGE_SUBCLASS;
  if (kind === DISJOINT_KIND)
    return focused ? EDGE_DISJOINT_FOCUS : dim ? EDGE_DIM : EDGE_DISJOINT;
  if (kind === RULE_KIND)
    return focused ? EDGE_RULE_FOCUS : dim ? EDGE_DIM : EDGE_RULE;
  return focused ? EDGE_RELATION_FOCUS : dim ? EDGE_DIM : EDGE_RELATION;
}

export const SchemaGraphCanvas = defineComponent({
  name: "SchemaGraphCanvas",
  props: {
    loading: { type: Boolean, default: false },
    entityTypes: {
      type: Array as PropType<import("@/api/knowledge/types").EntityTypeView[]>,
      required: true
    },
    relationTypes: {
      type: Array as PropType<import("@/api/knowledge/types").RelationTypeView[]>,
      required: true
    },
    rules: {
      type: Array as PropType<import("@/api/knowledge/types").BusinessRule[]>,
      default: () => []
    },
    selected: {
      type: Object as PropType<SchemaSelection | null>,
      default: null
    },
    unscopedCount: { type: Number, default: 0 },
    hiddenCount: { type: Number, default: 0 },
    budget: { type: Number, default: SCHEMA_BUDGETS[0] },
    capped: { type: Boolean, default: false }
  },
  emits: ["select", "budget"],
  setup(props, { emit }) {
    const containerRef = ref<HTMLDivElement | null>(null);
    const gridRef = ref<HTMLCanvasElement | null>(null);
    const sigmaRef = ref<Sigma | null>(null);
    const selectedRef = ref<SchemaSelection | null>(null);
    watch(
      () => props.selected,
      s => {
        selectedRef.value = s;
        sigmaRef.value?.refresh();
      },
      { immediate: true }
    );

    /* 数据 → graphology 图（含取景与布局），sigma 重建。
       挂载后 containerRef 才有值——把它并进 watch 源，挂载即触发构图 */
    watch(
      () =>
        [
          containerRef.value,
          props.entityTypes,
          props.relationTypes,
          props.rules,
          props.budget
        ] as const,
      () => {
        const container = containerRef.value;
        if (!container) return;
        refreshPalette();
        const result = buildSchemaGraph(
          props.entityTypes,
          props.relationTypes,
          null,
          props.rules
        );
        const g = result.graph;
        // 静态布局：circular 起始 + 一次收敛的 FA2（无 worker 动画——W1 静态视觉）。
        // 参数对齐旧仓 OntologySchemaGraph.tsx layoutSchemaGraph：600 步 /
        // gravity 0.35 / scalingRatio 22 / outboundAttractionDistribution。迁移
        // 时曾被减到 80 步——FA2 是渐进展开的，步数不够就停在图还没舒展开的
        // 那一刻（用户走查"画面怪怪的"根因之一）
        if (g.order > 0) {
          circular.assign(g, { scale: 300 });
          forceAtlas2.assign(g, {
            iterations: 600,
            settings: {
              ...forceAtlas2.inferSettings(g),
              gravity: 0.35,
              scalingRatio: 22,
              outboundAttractionDistribution: true
            }
          });
        }
        sigmaRef.value?.kill();
        /* sigmaOptions 的 CanvasOptions 与本文件的字面量不逐字对齐
           （edgeProgramClasses 的映射键是本文件定义的渲染派发键，运行时一致，
           旧仓同款写法）——这里整体以 never 收口 */
        const settings = sigmaOptions({
          defaultEdgeType: EDGE_TYPE_ARROW,
          edgeProgramClasses: withTopLayer({
            [EDGE_TYPE_ARROW]: EdgeArrowProgram,
            [EDGE_TYPE_CURVED_ARROW]: EdgeCurvedArrowProgram,
            [EDGE_TYPE_LINE]: EdgeLineProgram,
            [EDGE_TYPE_CURVED_LINE]: EdgeCurveProgram
          }),
          minEdgeThickness: MIN_EDGE_THICKNESS,
          maxCameraRatio: 8
        } as never);
        const sigma = new Sigma(g, container, {
          ...settings,
          nodeReducer: (node, attrs) => {
            const res = { ...attrs };
            const sel = selectedRef.value;
            if (sel && sel.kind === "class" && sel.id === node) {
              return selectedNode(res, attrs, attrs.size as number);
            }
            return res;
          },
          edgeReducer: (edge, attrs) => {
            const res = { ...attrs };
            const [s, t] = g.extremities(edge);
            const sel = selectedRef.value;
            const kind = String(attrs.kind ?? RELATION_KIND);
            const isSub =
              (sel?.kind === "class" && (sel.id === s || sel.id === t)) ||
              (sel?.kind === "relation" &&
                ((attrs.relationIds as string[]) ?? []).includes(sel.id));
            res.color = edgeColorFor(
              kind,
              !!sel && !isSub,
              isSub && sel?.kind !== "rule"
            );
            if (kind === RULE_KIND) {
              // 关掉的规则也画，只是暗一档
              if (attrs.enabled === false) res.color = EDGE_DIM;
              if (sel?.kind === "rule" && sel.id === attrs.ruleId) {
                res.color = EDGE_RULE_FOCUS;
                res.forceLabel = true;
              }
            }
            return res;
          }
        });
        sigma.on("clickNode", ({ node }) => {
          emit("select", { kind: "class", id: node } satisfies SchemaSelection);
        });
        sigma.on("clickEdge", ({ edge }) => {
          const attrs = g.getEdgeAttributes(edge) as Record<string, unknown>;
          const s = g.source(edge);
          if (attrs.kind === RELATION_KIND) {
            const relIds = (attrs.relationIds as string[]) ?? [];
            if (relIds.length === 1)
              emit("select", {
                kind: "relation",
                id: relIds[0]
              } satisfies SchemaSelection);
          } else if (attrs.kind === RULE_KIND && attrs.ruleId) {
            emit("select", {
              kind: "rule",
              id: String(attrs.ruleId)
            } satisfies SchemaSelection);
          } else if (attrs.kind === SUBCLASS_KIND) {
            // 继承边点子类（边的起点）
            emit("select", { kind: "class", id: s } satisfies SchemaSelection);
          }
        });
        sigma.on("clickStage", () => emit("select", null));
        // 世界坐标网格：相机变动/容器尺寸变动时重绘
        const renderGrid = () => {
          if (gridRef.value) drawWorldGrid(gridRef.value, sigma);
        };
        sigma.getCamera().on("updated", renderGrid);
        sigma.on("resize", renderGrid);
        renderGrid();
        sigmaRef.value = sigma;
      },
      { immediate: true, deep: false }
    );

    onBeforeUnmount(() => {
      sigmaRef.value?.kill();
      sigmaRef.value = null;
    });

    /* 图例条目（旧仓同构）：三种边常显；规则那一条只在真有规则时出现——
       没有规则的库不该看到一个解释不存在的东西的图例 */
    const edgeLegend = computed(() =>
      [
        [S.ontology.schemaLegendInheritance, SUBCLASS_KIND, "2px"],
        [S.ontology.schemaLegendRelation, RELATION_KIND, "3px"],
        [S.ontology.schemaLegendDisjoint, DISJOINT_KIND, "2px"],
        ...(props.rules.length
          ? ([[S.ontology.schemaLegendRule, RULE_KIND, "3px"]] as const)
          : [])
      ] as const
    );

    return () => (
      <div class="absolute inset-0">
        {/* 世界坐标网格层垫在 sigma WebGL 层下 */}
        <canvas ref={gridRef} class="absolute inset-0 size-full " />
        <div ref={containerRef} class="absolute inset-0" />

        {/* 左上：静态图例（旧仓同款 badge-soft 药丸，一粒一条边）。无标题——
            旧仓这一排不带"模式图"字样；说明性徽标不可点，不给悬停反馈 */}
        <div class="canvas-chrome-docked pointer-events-none absolute left-3 top-3 z-10 flex flex-wrap items-center gap-2">
          {edgeLegend.value.map(([label, kind, h]) => (
            <span
              key={label}
              class="badge-soft badge-soft-info pointer-events-auto gap-2"
            >
              {/* 高度必须带单位：JSX 不给数字补 px，裸数字是无效 CSS，
                  色块会塌成 0 高（走查"缺图例"的根因） */}
              <span
                class="inline-block w-3 shrink-0 rounded-full"
                style={{ background: edgeColorFor(kind, false, false), height: h }}
              />
              {label}
            </span>
          ))}
          {/* 没画的有多少：只在真有隐藏类时出现（旧仓 scope.hidden > 0）。
              迁移版曾把它写成常驻并回显"模式图"——与图例标题重复 */}
          {props.hiddenCount > 0 && (
            <span class="badge-soft badge-soft-info pointer-events-auto">
              {S.ontology.schemaMoreClasses(props.hiddenCount)}
            </span>
          )}
        </div>

        {/* 左下：缩放 + 归位（旧仓同位 bottom-4 left-3）。**真实相机缩放**——
            旧仓 animatedZoom/animatedUnzoom；迁移版曾把它错接到取景档位上，
            小库（类数 < 最低档 30）两个按钮恒灰。取景档位不再占用这组按钮 */}
        <div class="pointer-events-auto absolute bottom-4 left-3 z-10 flex flex-col items-start gap-2">
          <ToolTower>
            <ToolButton
              label={S.ontology.schemaZoomIn}
              onClick={() =>
                sigmaRef.value?.getCamera().animatedZoom({ duration: 220 })
              }
            >
              {{ icon: () => <ZoomInIcon width={15} height={15} /> }}
            </ToolButton>
            <ToolButton
              label={S.ontology.schemaZoomOut}
              onClick={() =>
                sigmaRef.value?.getCamera().animatedUnzoom({ duration: 220 })
              }
            >
              {{ icon: () => <ZoomOutIcon width={15} height={15} /> }}
            </ToolButton>
            <ToolDivider />
            <ToolButton
              label={S.ontology.schemaFitView}
              onClick={() =>
                sigmaRef.value?.getCamera().animatedReset({ duration: 300 })
              }
            >
              {{ icon: () => <Maximize2Icon width={15} height={15} /> }}
            </ToolButton>
          </ToolTower>
        </div>

        {/* 空态入口画在父层（未匹配面板），这里只给过程态 */}
        {props.loading && (
          <div class="pointer-events-none absolute inset-0 grid place-items-center">
            <span
              class="inline-block animate-spin rounded-full border-2 border-current border-t-transparent text-ink-2"
              style={{ width: "28px", height: "28px" }}
            />
          </div>
        )}
      </div>
    );
  }
});

/* 取景档位（旧仓 SCHEMA_BUDGETS 的默认值在画布重建时决定 node budget 语义） */
void NODE_SIZE_ROOT;
void NODE_SIZE_STEP;
