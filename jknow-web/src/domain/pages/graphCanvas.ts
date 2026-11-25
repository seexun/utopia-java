// 两块画布共用的那台机器（#496）。
//
// `/graph` 画实例与事实，本体页画类与关系。它们的**数据**不共享，语义也不共享，
// 但把数据变成一块能看能点的画布的那套东西是同一份：节点程序的解剖（状态环
// → 白描边 → 类型色实心）、标签何时出没、悬停卡、状态表、相机、拖拽。从前
// 这些在两个文件里各写一遍，字面
// 相同——连解释标签阈值为什么是 5 和 0.8 的那段注释都是逐字两份。代价不是行数，
// 是**会走样**：压暗系数一边 0.52 一边 0.55，谁也不知道哪个是对的。
//
// 分界线：这个文件管「怎么画、怎么动」，页面管「画什么」。页面把自己的数据投影
// 成一张 graphology 图，给每个节点一个类型色，再说清楚哪些该藏；其余的在这里。
//
// `graphVisuals.ts` 是上一轮抽出来的常量与绘制函数，这个文件建在它上面。

import type Graphology from "graphology";
import Sigma from "sigma";
import type { Settings } from "sigma/settings";
import { createNodeBorderProgram } from "@sigma/node-border";

import { NodeHoverVoidProgram } from "./hoverVoidProgram";
import { NodeSquareShellProgram } from "./squareShellProgram";
import {
  CANVAS_FONT,
  CANVAS_LABEL_SIZE,
  CANVAS_TEXT,
  CANVAS_TEXT_2,
  drawHoverCard,
  drawNodeLabel,
  HOVER_MUTE,
  lerpColor,
  mix,
  MUTED_SHELL,
  NODE_CORE_BASE,
  NODE_SHELL_BASE,
  RING_SELECT,
  STROKE_WHITE,
  TRANSPARENT,
} from "./graphVisuals";

/* ============ 节点程序 ============ */

/** 节点解剖（方向 A，规格 §2）：状态环 → 白描边 → 类型色实心。
 *
 * 两页同一份。从前默认那一种在 `/graph` 叫 `shell`、在本体页叫 `circle`，
 * 而它们是同一个 `createNodeBorderProgram`——两个名字说的是同一件事 */
export const NODE_TYPE_SHELL = "shell";
export const NODE_TYPE_SQUARE = "square";

/* ---------- 高亮的边画在最上层 ---------- */

/** 「最上层」那一批的类型名后缀 */
const TOP = "Top";

/** 一条边高亮时该用的类型名。
 *
 * **`zIndex` 在这里不管用。**sigma 一个程序一批绘制，`zIndex` 只在批内排序；
 * 跨程序谁先谁后，看的是 `edgeProgramClasses` 里键的顺序。于是选中一个节点
 * 之后，它那几条亮线照样会被别的程序里压暗的边切断——放大看就是白线被一条条
 * 细黑条切开。
 *
 * 解法是把同一个程序**注册两遍**（见 `withTopLayer`），高亮的边整批走后注册的
 * 那一份，最后画。两页都踩过这个坑：图谱页先解的，本体页后来又踩了一遍——
 * 所以它收在这里，不在任何一页里。 */
export function drawLast(type: string): string {
  return type.endsWith(TOP) ? type : type + TOP;
}

/** 给一份边程序表配上「最上层」的那一份：同样的程序，键排在后面。
 *  高亮时把 `res.type` 换成 `drawLast(res.type)` 就落进这一批。 */
export function withTopLayer<T>(programs: Record<string, T>): Record<string, T> {
  return {
    ...programs,
    ...Object.fromEntries(
      Object.entries(programs).map(([name, program]) => [drawLast(name), program]),
    ),
  };
}

export const NODE_PROGRAMS = {
  // 方向 A（docs/design/graph-node-edge-style.md §2）：状态环 → 白描边 → 类型色实心。
  // 环的宽度是程序级常量（@sigma/node-border 不支持按节点变宽），选中/hover
  // 的区分落在环色上（见状态表）；白描边 0.16 让实色圆在 #fcfcfd 底与白卡
  // 停靠面板上都立得住
  [NODE_TYPE_SHELL]: createNodeBorderProgram({
    borders: [
      { size: { value: 0.14 }, color: { attribute: "ringColor" } },
      { size: { value: 0.16 }, color: { attribute: "borderColor" } },
      { size: { fill: true }, color: { attribute: "color" } },
    ],
  }),
  [NODE_TYPE_SQUARE]: NodeSquareShellProgram,
};

/** 悬停层的节点 program（sigma 3 的 `nodeHoverProgramClasses`）：sigma 会把正被
 *  指着的节点用这块 WebGL 画布（sigma-hoverNodes，层级在 2D 悬停层之上）重画
 *  一遍，本意是别让圆点埋进标签底下。胶囊一轮之后悬停的反馈整体搬到了 2D
 *  悬停层（类型色胶囊、悬停时描边换主色 1.5px），这层重画反而把圆盘的白环白描边原样盖在
 *  胶囊正中——走查截图里那颗「白圈 + 中心绿点」就是这么来的（走查微调 2，
 *  真实浏览器复现定位）。胶囊永远把圆盘整个盖住（见 drawNodeLabel 的盖圆），
 *  所以悬停 program 画**空**：反馈只有胶囊自己（规格 graph-capsule-nodes.md §2）。
 *  节点的 type 名不带 drawLast 后缀（那是给边的高亮批次用的），直接同名 */
export const NODE_HOVER_PROGRAMS = Object.fromEntries(
  Object.keys(NODE_PROGRAMS).map((name) => [name, NodeHoverVoidProgram]),
) as Record<string, typeof NodeHoverVoidProgram>;

/* ============ 构造选项 ============ */

/** 两页真正不同的那几项。其余一律在 `sigmaOptions` 里定死——它们决定画布
 *  长什么样、怎么响应，两页长得不一样就是 bug 而不是特性 */
export interface CanvasOptions {
  /** 边的类型名到程序的映射。`/graph` 只有直线与弧，本体页还要箭头 */
  edgeProgramClasses: Settings["edgeProgramClasses"];
  defaultEdgeType: string;
  /** 相机能缩到多远、放到多近。两页的图规模差几个量级，取景范围本就不同 */
  minCameraRatio: number;
  maxCameraRatio: number;
  /** 边上写不写字。`/graph` 的边是谓词，要写；本体页只有关系边写 */
  renderEdgeLabels?: boolean;
  minEdgeThickness?: number;
}

/** `new Sigma(g, el, { ...sigmaOptions(...), nodeReducer, edgeReducer })`。
 *
 * **两个 reducer 不从这里过。** sigma 的 reducer 类型跟着它自己的泛型参数走，
 * 中转一手就把推断掐断了；而 reducer 的**内容**本就是两页各自的事，共用的是
 * 它们调用的那张状态表（下一节），不是函数本身。
 *
 * 标签按距离出没——离得远只看形状，走近了才认名字。**试过不按距离**
 * （阈值归零、只按拥挤程度筛）：缩远之后一百多个名字铺开互相压字，读不出也
 * 点不准。阈值 5、每 130px 见方留 0.8 个：只比原先松半档。**放宽到 3 / 1.2
 * 试过一轮，一屏上百个名字铺开，太吵**——这里要的是「远处认得出几个地标」，
 * 不是「每个点都报名字」。放大时 sigma 自己按 1/ratio² 放开这个上限
 * （见 `getLabelsToDisplay`），越走近露得越全，不封顶。
 *
 * 边的字与节点同一档（fine）、同一个次要色。从前是 9px/#a1a1a1——9 比界面里
 * 最小的字还小一半，而 #a1a1a1 是上一版的 ink-2。
 *
 * 边的悬停事件默认是关的，这里一律开：两页都靠 `enterEdge` 让边说话。 */
export function sigmaOptions(o: CanvasOptions) {
  return {
    /* **按层级画**。两个 reducer 一直在设 `res.zIndex`（压暗 0、邻居 2、
       选中 3、指到 4、高亮的边 5），可 sigma 默认不看它——绘制顺序就是缓冲区
       顺序，于是一条高亮的白边会被后画的暗边一段段切断，看着像被虚线打断。
       打开它，那些层级值才真正生效。代价是每帧多一次排序，几百条边这个量级
       可以忽略 */
    zIndex: true,
    allowInvalidContainer: true,
    /* **fit 边距**。sigma 相机的 reset/fit 是 ratio 1——整张图贴边填满视口
       （视口矩阵里最小边全幅、correctionRatio 保证 contain），落在包围盒边缘
       的节点正好压在卡缘上被裁半，图谱页底部还要再叠一层时间岛。sigma 没有
       带 padding 的 fitView，但 settings 里有这枚 `stagePadding`：视口矩阵
       按 `min(w,h) − 2×padding` 取景，fit 时四边各留这些像素的呼吸边——
       坐标换算（graphToViewport 等）与世界网格走同一矩阵，自动对齐。
       只在 fit（ratio 1）附近最显；缩放不受影响（minCameraRatio 语义未变）。
       40px：底缘节点完整离开卡缘，盖在时间岛后面的只是浮层叠加不是裁切 */
    stagePadding: 40,
    defaultNodeType: NODE_TYPE_SHELL,
    nodeProgramClasses: NODE_PROGRAMS,
    nodeHoverProgramClasses: NODE_HOVER_PROGRAMS,
    edgeProgramClasses: o.edgeProgramClasses,
    defaultEdgeType: o.defaultEdgeType,
    enableEdgeEvents: true,
    renderEdgeLabels: o.renderEdgeLabels ?? false,
    ...(o.minEdgeThickness === undefined
      ? {}
      : { minEdgeThickness: o.minEdgeThickness }),
    labelFont: CANVAS_FONT,
    labelSize: CANVAS_LABEL_SIZE,
    labelColor: { color: CANVAS_TEXT },
    labelRenderedSizeThreshold: 5,
    labelDensity: 0.8,
    labelGridCellSize: 130,
    minCameraRatio: o.minCameraRatio,
    maxCameraRatio: o.maxCameraRatio,
    edgeLabelSize: CANVAS_LABEL_SIZE,
    edgeLabelColor: { color: CANVAS_TEXT_2 },
    edgeLabelFont: CANVAS_FONT,
    defaultDrawNodeLabel: drawNodeLabel,
    defaultDrawNodeHover: drawHoverCard,
  };
}

/* ============ 状态表 ============ */

/** reducer 收到的属性袋与它要交回去的那一份。sigma 的类型在这一层是 `any`，
 *  两页各自的属性键也不一样，所以这里只认「一袋东西」 */
export type NodeAttrs = Record<string, unknown>;

/** 压到底：其余都退场，只留形状。
 *
 * 系数从前两页不一样，0.52 与 0.55，没有一处说明为什么。设计表里写的是 0.52，
 * 这里按它统一——那个 0.55 是抄的时候走的样，不是谁定的。
 * 白描边**留着**（规格 §2）：点缩成小暗点，靠那圈白才不至于沉进线网里 */
export const MUTE_SCALE = 0.52;

export function mutedNode(res: NodeAttrs, base: number): NodeAttrs {
  res.size = base * MUTE_SCALE;
  res.color = mix(MUTED_SHELL, NODE_CORE_BASE, 0.3);
  res.shellColor = MUTED_SHELL;
  res.ringColor = TRANSPARENT;
  res.label = "";
  res.zIndex = 0;
  return res;
}

/** 小库（≤100）聚焦态的"轻压暗"（走查微调 4）：颜色向灰靠一档，
 *  但**名字与大小都保留**——胶囊继续在场，聚焦对比交给选中环与高亮边。
 *  与 mutedNode/softMutedNode 的分工：那两个是大图的"退场"（清名缩点），
 *  这个是小图的"让路"（变灰不退场）。小库全显标签的闭环见 Graph.tsx 建图段。
 *  `k`（默认 1）是聚焦强度（走查微调 7）：进出聚焦时由调用方渐变传入，
 *  灰度按 k 插值——k=0 即原色 */
export function dimmedNode(
  res: NodeAttrs,
  attrs: NodeAttrs,
  k = 1,
): NodeAttrs {
  res.color = lerpColor(
    String(attrs.color ?? NODE_CORE_BASE),
    mix(MUTED_SHELL, NODE_CORE_BASE, 0.3),
    0.5 * k,
  );
  res.shellColor = lerpColor(
    String(attrs.shellColor ?? NODE_SHELL_BASE),
    MUTED_SHELL,
    0.5 * k,
  );
  res.forceLabel = true;
  res.zIndex = 0;
  return res;
}

/** 悬停时其余的按 `HOVER_MUTE` 压一档（选中是压到底）。
 *  邻居不压——悬停要回答的是「它连着谁」，把邻居也压掉就等于没回答 */
export function softMutedNode(
  res: NodeAttrs,
  attrs: NodeAttrs,
  base: number,
): NodeAttrs {
  res.size = base * (1 - (1 - MUTE_SCALE) * HOVER_MUTE);
  res.color = lerpColor(
    String(attrs.color ?? NODE_CORE_BASE),
    mix(MUTED_SHELL, NODE_CORE_BASE, 0.3),
    HOVER_MUTE,
  );
  res.shellColor = lerpColor(
    String(attrs.shellColor ?? NODE_SHELL_BASE),
    MUTED_SHELL,
    HOVER_MUTE,
  );
  res.ringColor = TRANSPARENT;
  res.label = "";
  res.zIndex = 0;
  return res;
}

/** 指着的那一个：**不放大**（走查微调轮第 1 项），尺寸与静止时一模一样。
 *  从前 ×1.08 的放大在近景会把圆点顶出胶囊上下沿——胶囊高不跟着长，
 *  看着就是"胶囊底下还藏着一个节点"。胶囊 v2（浅底彩边墨字）的悬停分量
 *  全交给描边：`labelLift` 让 drawNodeLabel 把胶囊边从类型色 1px 换成主色
 *  1.5px——状态在边上，不在底色与字上；节点本体不动（胶囊把圆盘整个盖住，
 *  ringColor 换白看不见，只是旧语言留下的写法）。从前的"牌子底亮一档并
 *  浮起来"随旁置药丸一起退场（选中那一档 ×1.02 保留——它小到读不出形变，
 *  见 selectedNode） */
export function hoveredNode(res: NodeAttrs): NodeAttrs {
  res.ringColor = STROKE_WHITE;
  res.forceLabel = true;
  res.labelLift = true;
  res.zIndex = 4;
  return deferToHoverLayer(res);
}

/** 交给高亮层画：标签层跳过这一个（见 `drawNodeLabel` 里那段说明）。
 *
 * 正被指着的节点，sigma 会在标签层和高亮层各画一遍名字。这个记号是给标签层
 * 让路用的，**只该落在此刻真被指着的那一个身上**——所以它不属于 `selectedNode`：
 * 选中的那一个没被指着时，画它的正是标签层。 */
export function deferToHoverLayer(res: NodeAttrs): NodeAttrs {
  res.hideBaseLabel = true;
  return res;
}

/** 选中的那一个：**胶囊边换主色 2px**。
 *
 * 换的是胶囊描边，不是节点自己。节点本体（类型色实心 + 白描边）一律不动
 * ——选中不改填充：一个更亮或更深的实心会把周围一圈邻居都压下去，而邻居
 * 正是选中之后最该读的东西。**大小也几乎不动（×1.02）**，一选中就胖一圈，
 * 整张图的疏密看着就变了。
 *
 * 于是"选中"落在胶囊边上（胶囊 v2 = 浅底彩边墨字）：`labelInvert` 让
 * drawNodeLabel 把描边从类型色 1px 换成**主色 2px**，悬停是同一副边换主色
 * 1.5px（见 hoveredNode）——状态在边上不在底/字，轻重两档的粗细就是"我正
 * 指着"和"我选中了"的分别，不必再添第三种记号。ringColor 换主色
 * （RING_SELECT，规格 §2）的写法保留，但胶囊盖住圆盘，说话的只剩那圈边 */
export function selectedNode(
  res: NodeAttrs,
  attrs: NodeAttrs,
  base: number,
): NodeAttrs {
  res.size = Math.max(base * 1.02, 9.2);
  res.ringColor = RING_SELECT;
  res.forceLabel = true;
  res.labelLift = true;
  // 选中的记号：labelInvert 让胶囊描边换主色 2px（悬停 labelLift 是同一副
  // 边换主色 1.5px）——粗细两档就是"指着"与"选中"的分别，状态在边上不在底/字
  res.labelInvert = true;
  res.zIndex = 3;
  return res;
}

/** 选中的那一个的邻居。
 *
 * `scale` 两页不同，这一处是真的设计差别不是走样：`/graph` 上千个节点，
 * 收到 0.76 是为了给选中的那一条路让地方；本体页几十个类，收了反而显得瘫。
 * `k`（默认 1）是聚焦强度（走查微调 7）：缩放按 k 渐变，k=0 时保持原尺寸 */
export function neighborNode(
  res: NodeAttrs,
  base: number,
  scale = 1,
  k = 1,
): NodeAttrs {
  if (scale !== 1)
    res.size = Math.max(base * (1 - (1 - scale) * k), 4);
  res.zIndex = 2;
  return res;
}

/* ============ 相机 ============ */

export interface Point {
  x: number;
  y: number;
}

export function nodePosition(sigma: Sigma, id: string): Point {
  const graph = sigma.getGraph();
  return {
    x: graph.getNodeAttribute(id, "x") as number,
    y: graph.getNodeAttribute(id, "y") as number,
  };
}

/** 节点此刻是否在视口里（按上一次渲染的相机算） */
export function nodeInView(sigma: Sigma, id: string, margin = 48): boolean {
  const { x, y } = sigma.graphToViewport(nodePosition(sigma, id));
  const { width, height } = sigma.getDimensions();
  return (
    x >= margin && x <= width - margin && y >= margin && y <= height - margin
  );
}

/** 相机推到一个节点上。sigma 的相机坐标是归一化到 [0,1] 的「框内」坐标，
 *  不是图坐标——直接喂图坐标（x 动辄几百）相机会飞出画面几万像素，画布
 *  一片空白。sigma 没有公开的图→框内换算，绕一趟视口：`graphToViewport` 用的
 *  是上一次渲染的矩阵，与 `viewportToFramedGraph` 用同一台相机，一来一回把
 *  相机抵消掉，剩下的就是框内坐标 */
export function focusNode(sigma: Sigma, id: string, maxRatio = 0.5): void {
  if (!sigma.getGraph().hasNode(id)) return;
  const framed = sigma.viewportToFramedGraph(
    sigma.graphToViewport(nodePosition(sigma, id)),
  );
  const ratio = Math.min(sigma.getCamera().ratio, maxRatio);
  sigma
    .getCamera()
    .animate({ x: framed.x, y: framed.y, ratio }, { duration: 300 });
}

/** 把一张刚算好的图**搬进**正在渲染的那一张。
 *
 * sigma 绑死在构造时给它的那个 graphology 实例上，所以"图变了"很容易被写成
 * "杀掉重建"。代价不小：GPU 缓冲、相机、悬停与选中态全部丢掉，还要再走一遍
 * 首帧——本体页为了把一个类揭进画面就走一遍这套，画面会明显顿一下。
 *
 * graphology 每一次增删改都发事件，sigma 听得见，所以**就地改本来就是它支持的
 * 路**。这里做的是最朴素的对账：多的删掉、少的补上、留下的属性覆盖一遍。
 * 几十上百个类的规模，一次全量覆盖比算精确差异更省事，也不容易错。
 *
 * 删点会连带删掉它的边，所以先取一份快照再遍历（`nodes()` 返回的是数组）。 */
export function syncGraph(live: Graphology, next: Graphology): void {
  for (const node of live.nodes()) if (!next.hasNode(node)) live.dropNode(node);
  next.forEachNode((node, attrs) => {
    if (live.hasNode(node)) live.replaceNodeAttributes(node, { ...attrs });
    else live.addNode(node, { ...attrs });
  });
  for (const edge of live.edges()) if (!next.hasEdge(edge)) live.dropEdge(edge);
  next.forEachEdge((edge, attrs, source, target) => {
    if (live.hasEdge(edge)) live.replaceEdgeAttributes(edge, { ...attrs });
    else live.addEdgeWithKey(edge, source, target, { ...attrs });
  });
}

/* ============ 拖拽 ============ */

/** 拖一个节点。**按下只记候选，位移超过阈值才升格成拖拽**——否则一次纯点击
 *  也会被当成拖了 0 像素的拖拽，鼠标松手的时机跟点选打架。
 *
 * 升格的那一刻冻住包围盒：拖着拖着节点飞出画面边缘时，相机不该跟着自动缩放去
 * 「适应」新的包围盒，那样一拖全图就跟着抖。松手再解开——「归位」要看得见刚
 * 挪过去的新位置，不能还按拖拽开始前的旧范围来算。
 *
 * 两页对「拖动过程中还要做什么」的回答不同：`/graph` 要把光标位置喂进力模拟，
 * 本体页要把位置记进它自己的坐标表。所以那两件事是回调，不在这里。 */
export function attachDrag(
  sigma: Sigma,
  o: {
    /** 升格成拖拽的那一刻。`/graph` 在这里唤醒力模拟 */
    onStart?: (node: string) => void;
    /** 每一帧的新位置。写进图的那两笔已经做过了 */
    onMove?: (node: string, pos: Point) => void;
    /** 松手。`/graph` 在这里安排力模拟停下 */
    onEnd?: (node: string) => void;
    /** 光标形状归谁管；不给就不动光标 */
    container?: () => HTMLElement | null;
    /** 悬停在节点上是不是「可以抓」的样子。本体页给，`/graph` 不给 */
    hovering?: () => boolean;
  } = {},
): void {
  const graph = sigma.getGraph();
  let dragCandidate: string | null = null;
  let downPoint: Point | null = null;
  let dragged: string | null = null;
  const cursor = (v: string) => {
    const el = o.container?.();
    if (el) el.style.cursor = v;
  };

  sigma.on("downNode", (e) => {
    dragCandidate = e.node;
    downPoint = { x: e.event.x, y: e.event.y };
  });
  sigma.getMouseCaptor().on("mousemovebody", (e) => {
    if (!dragCandidate) return;
    if (!dragged) {
      if (!downPoint || Math.hypot(e.x - downPoint.x, e.y - downPoint.y) < 4)
        return;
      dragged = dragCandidate;
      cursor("grabbing");
      if (!sigma.getCustomBBox()) sigma.setCustomBBox(sigma.getBBox());
      o.onStart?.(dragged);
    }
    const pos = sigma.viewportToGraph(e);
    graph.setNodeAttribute(dragged, "x", pos.x);
    graph.setNodeAttribute(dragged, "y", pos.y);
    o.onMove?.(dragged, pos);
    // 阻止相机跟着平移
    e.preventSigmaDefault();
    e.original.preventDefault();
    e.original.stopPropagation();
  });
  const endDrag = () => {
    dragCandidate = null;
    downPoint = null;
    if (!dragged) return;
    const node = dragged;
    dragged = null;
    cursor(o.hovering?.() ? "grab" : "");
    sigma.setCustomBBox(null);
    o.onEnd?.(node);
  };
  sigma.getMouseCaptor().on("mouseup", endDrag);

  // 悬停在节点上方给个「可以抓」的提示——发现得靠猜的交互等于没有
  if (o.hovering) {
    sigma.on("enterNode", () => {
      if (!dragged) cursor("grab");
    });
    sigma.on("leaveNode", () => {
      if (!dragged) cursor("");
    });
  }
}
