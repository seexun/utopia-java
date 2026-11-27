/* 本体模式图画布（对照旧仓 pages/OntologySchemaGraph.tsx 的画布面）：
   节点胶囊/白描边实色、三种边语义的色与形、图例药丸、缩放塔、点选、
   拖拽摆位、悬停/选中高亮、关系标签按缩放显现。
   画布机器（graphCanvas）与视觉常量（graphVisuals）在 domain，两页共用。
   搜索补景属任务 9。 */
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
  attachDrag,
  deferToHoverLayer,
  drawLast,
  hoveredNode,
  selectedNode,
  sigmaOptions,
  withTopLayer
} from "@/domain/pages/graphCanvas";
import { S } from "@/i18n";
import {
  cn,
  ToolButton,
  ToolDivider,
  ToolTower
} from "@/components/knowledge/kit";
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
    /* 悬停态（旧仓 hoverRef / hoverEdgeRef）：指到节点/边上时高亮那一束 */
    const hoverNodeRef = ref<string | null>(null);
    const hoverEdgeRef = ref<string | null>(null);
    /* 拖过的节点的新位置（旧仓 draggedPositionsRef）：布局是增量重建的——
       每次重建 sigma 都从这张表把用户摆过的节点放回原位，拖完往哪放就在哪，
       不会被重新布局拽回去 */
    const draggedPositions = new Map<string, { x: number; y: number }>();
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
        /* 用户摆过的节点钉回原位（旧仓同款）：布局算的是机器的位置，
           拖过的那几个永远听人的 */
        for (const [id, pos] of draggedPositions) {
          if (g.hasNode(id))
            g.mergeNodeAttributes(id, { x: pos.x, y: pos.y });
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
          // 几十上百个类，缩到 0.05 就看得见全貌（旧仓同值；实例图缩得更远）
          minCameraRatio: 0.05,
          maxCameraRatio: 8,
          // 胶囊不随缩小消失（走查"城市缩着缩着变成一个圆圈"）：sigma 默认
          // 节点半径 < 5px 就不画名字，底下的裸实心圆就露出来。叶子类 size=8，
          // 最先掉——所以总是它先变圆圈。本体页类少、胶囊不压，阈值归零；
          // 密度筛同步放宽（0.8 → 2），缩远时少几个名字被网格筛掉
          labelRenderedSizeThreshold: 0,
          labelDensity: 2
        } as never);
        const sigma = new Sigma(g, container, {
          ...settings,
          nodeReducer: (node, attrs) => {
            const res = { ...attrs };
            const base = attrs.size as number;
            const sel = selectedRef.value;
            const hov = hoverNodeRef.value;
            const selClass =
              sel?.kind === "class" && g.hasNode(sel.id) ? sel.id : null;
            // 选中压过指到（与实例图同一条）：指针落到自己选中的那个类上，
            // 读到的还是选中那一副
            if (selClass === node) {
              const picked = selectedNode(res, attrs, base);
              return hov === node ? deferToHoverLayer(picked) : picked;
            }
            if (hov === node) return hoveredNode(res);
            return res;
          },
          edgeReducer: (edge, attrs) => {
            const res = { ...attrs };
            const [s, t] = g.extremities(edge);
            const sel = selectedRef.value;
            const kind = String(attrs.kind ?? RELATION_KIND);
            const relIds = (attrs.relationIds as string[]) ?? [];
            const base = edgeColorFor(kind, false, false);
            res.color = base;
            // 关掉的规则也画，只是暗一档
            if (kind === RULE_KIND && attrs.enabled === false)
              res.color = EDGE_DIM;
            /* 结构性的边（继承/互斥）不挂标签；关系边挂——名字来自 schema.ts
               建边时的 label（r.label / "N 条关系"），由 renderEdgeLabels 按缩放
               开关（见下方 updateEdgeLabels）。规则边挂名字：那是它唯一说得出
               自己是谁的地方 */
            if (kind !== RELATION_KIND && kind !== RULE_KIND) res.label = "";
            /* 自环（域 === 值域，如 供货给: 公司→公司）不挂标签。曲边程序
               createDrawCurvedEdgeLabel 算标签锚点时拿「两端点距离」做除数——
               自环两端是同一个节点，距离为 0，坐标算成 Infinity，canvas 把名字
               画到画布左上角外，只留顶部 1px 残片（走查"箭头位置有个黑色的
               东西"）。位置在数学上无定义，名字交给悬停高亮与右侧面板说 */
            if (s === t) res.label = "";

            const hov = hoverNodeRef.value;
            const hoverHit = hov !== null && (hov === s || hov === t);
            const hoverEdgeHit = hoverEdgeRef.value === edge;
            const selHit =
              sel?.kind === "class"
                ? sel.id === s || sel.id === t
                : sel?.kind === "relation"
                  ? relIds.includes(sel.id)
                  : sel?.kind === "rule"
                    ? attrs.ruleId === sel.id
                    : false;
            // 指到/选中相关的边：提亮、加粗、换到最后画的那一批
            if (selHit || hoverHit || hoverEdgeHit) {
              res.color = edgeColorFor(kind, false, true);
              res.size = Math.max((attrs.size as number) ?? 1, 1) * 1.5;
              res.zIndex = 3;
              res.type = drawLast(String(res.type ?? EDGE_TYPE_ARROW));
              // 提亮的边把名字亮出来（不看缩放）
              res.forceLabel = true;
              return res;
            }
            // 选中了什么但这条边跟它无关：压到背景色附近去
            if (sel) {
              res.color = EDGE_DIM;
              res.label = "";
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
            // 单独一条：选中它；并起来的一捆：选中 domain 那个类
            if (relIds.length === 1)
              emit("select", {
                kind: "relation",
                id: relIds[0]
              } satisfies SchemaSelection);
            else emit("select", { kind: "class", id: s } satisfies SchemaSelection);
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
        // 悬停：指到节点/边上，那一束提亮加粗（旧仓同款四个事件）
        sigma.on("enterNode", ({ node }) => {
          hoverNodeRef.value = node;
          sigma.refresh();
        });
        sigma.on("leaveNode", () => {
          hoverNodeRef.value = null;
          sigma.refresh();
        });
        sigma.on("enterEdge", ({ edge }) => {
          hoverEdgeRef.value = edge;
          sigma.refresh();
        });
        sigma.on("leaveEdge", () => {
          hoverEdgeRef.value = null;
          sigma.refresh();
        });
        /* 拖节点。**没有活的力模拟要喂**——这里的布局是一次性算完就定住的，
           拖完往哪放就在哪，不会被力模拟拽回去（「手动摆布局求清楚」）。
           阈值/包围盒冻结/光标那一套在 attachDrag 里（迁移时整段遗漏） */
        attachDrag(sigma, {
          container: () => containerRef.value,
          hovering: () => !!hoverNodeRef.value,
          onMove: (node, pos) => {
            draggedPositions.set(node, pos);
          }
        });
        /* 关系标签只在放大后出现（旧仓同款 ratio < 1.1）——本体大起来
           （导入包常有几十上百个类）时，全部常显就是一堵读不动的字墙。
           迁移时漏了这一行，renderEdgeLabels 一直是默认的 false，
           所以关系线上一个名字都没有 */
        const updateEdgeLabels = () =>
          sigma.setSetting(
            "renderEdgeLabels",
            sigma.getCamera().ratio < 1.1
          );
        sigma.getCamera().on("updated", updateEdgeLabels);
        updateEdgeLabels();
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
            旧仓这一排不带"模式图"字样；说明性徽标不可点，不给悬停反馈。
            canvas-chrome-docked 是**条件类**（旧仓同款）：选中类/关系、右侧
            停靠面板打开时才给面板让出一列；平时不占位。此前写成了常驻，
            图例容器被 right: 24.75rem 拉成 918px 宽 */}
        <div
          class={cn(
            "pointer-events-none absolute left-3 top-3 z-10 flex flex-wrap items-center gap-2",
            (props.selected?.kind === "class" ||
              props.selected?.kind === "relation") &&
              "canvas-chrome-docked"
          )}
        >
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
