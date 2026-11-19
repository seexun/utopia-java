// 节点视觉：色彩数学 + 实色圆点/白描边配色 + 胶囊标签/悬浮卡 + 世界坐标网格。
// 从 Graph.tsx 抽出——这几样是「怎么画一个带类型色的节点」，不含任何实例图
// 语义（没有 derived/contested/temporal 这些概念），本体模式图原样复用，
// 图谱与模式图因此长得像同一个引擎画的，而不是各自一套视觉识别。
//
// **边的画法没有抽到这里**：/graph 的边携带派生/争议/时态这些实例图独有的
// 状态，模式图的边是 subClassOf/relation/disjoint 三种本体语义，两者的
// 「边意味着什么」根本不是同一件事，抽成共用只会把两套语义焊在一起。
import type Sigma from "sigma";

/* 画布调色板 —— 结构取自 Semantica GraphWorkspace 源码；基色已中性化：
   Semantica 原版是钢蓝系（#0B1320/#5A7A9E/#7A92AE），按"chrome 零色偏、
   彩色只属于数据"的既定原则换成同明度纯灰，类型色混入比例不变 */
export let NODE_SHELL_BASE = "#121212"; // muted/淡入渐变的兜底底色（原 #0B1320 的中性化）
export let NODE_CORE_BASE = "#767676"; // muted 配方的混色端（原 #5A7A9E 的中性化）
export let NODE_BORDER_BASE = "#909090"; // 淡入渐变的描边兜底（原 #7A92AE 的中性化）
/* 方向 A（规格 §2，docs/design/graph-node-edge-style.md）：节点是「类型色
   实心 + 白描边」，从前的壳/核心两层 mix 配方（14%/50% tint）随壳层一起
   退场——类型色直接填满，上面那几个底色只剩 muted 与播放淡入的渐变
   还把它们当兜底用 */
/** 白描边与 hover 白环的那一个白。不是令牌：这是画布节点语言自带的白，
 *  在 #f6f8fb 的纸底与白卡停靠面板上都立得住（规格 §2） */
export const STROKE_WHITE = "#ffffff";
/** 选中态的主色（规格 §2）。胶囊 v2 里它是悬停/选中那圈主色描边的色值
 *  （drawNodeLabel：1.5/2px 两档）；节点本体的环宽是程序级全局值，选中的
 *  分量由胶囊边的粗细扛。
 *  双蓝收口（reskin-b1）：初值 #1677ff 是旧 antd 蓝的兜底，refreshPalette
 *  里与 node-core/edge-focus 同法读 --color-primary——节点主色已随令牌层
 *  换 #2563eb 而选中环还停在旧蓝，就是这次要修的「双蓝」 */
export let RING_SELECT = "#1677ff";
export const TRANSPARENT = "rgba(0,0,0,0)";
export let MUTED_SHELL = "#151515";
/* 悬停时其余的压暗程度。**比选中轻**（选中是压到底）：悬停是随鼠标走的、
   每划过一个节点就换一次，压到底会让整张画布不停明灭 */
export const HOVER_MUTE = 0.78;
/* 药丸牌那一代的底色（PILL_* 五色与 LABEL_HALO）随旁置标签一起退场
   （胶囊一轮，规格 graph-capsule-nodes.md §2）：名字住进节点里之后，
   标签层不再有"一块牌子"要配色 */
/** 浮层的面，抄 `.pop`（styles.css 的浮层面；tooltip / toast 用的那一档近实底） */
export let POP_BG = "rgba(16,16,16,0.98)";

/* 画布上的字与界面同一套刻度。**canvas 读不到 CSS 变量**，所以这里镜像一份
   `styles.css` 的值——它是源头，改那边记得回来改这里。
   从前这几个数是自己长出来的（节点 11、边 9、类型行 10、字色 #e5e5e5 /
   #a1a1a1）：字号整体上移一档之后，画布成了全站唯一还在用旧刻度的地方，
   而 9px 比界面里最小的字还小一半 */
/* 字体栈镜像 --font-sans（旧栈引用已废除的 webfont，第 3 波清零补漏） */
export const CANVAS_FONT =
  '"Plus Jakarta Sans", "PingFang SC", "Microsoft YaHei", "HarmonyOS Sans SC", sans-serif';
export let CANVAS_TEXT = "#ededed"; // --color-ink
export let CANVAS_TEXT_2 = "#a8a8a8"; // --color-ink-2
/* 画在节点与连线之间的字比界面的正文小一档（12，规格 §2 提档）。**画布不是
   界面**：这些字压在一片线和点上，与它们比邻的是 9–18px 的节点，不是页面
   上的正文；旧档的 11 是跟着旧的小点一起定的。浮在画布之上的悬浮卡不算
   ——那是 tooltip，走界面的刻度（见 CANVAS_TITLE_SIZE / CANVAS_META_SIZE） */
export const CANVAS_LABEL_SIZE = 12;
export const CANVAS_TITLE_SIZE = 14; // --text-body
export const CANVAS_META_SIZE = 12; // --text-fine

/* 胶囊节点（规格 graph-capsule-v2.md §1）：够格显示名字的节点
   以"类型色浅底胶囊+彩边墨字"呈现（类型色 15% 染白做底、1px 类型色发丝
   边、统一墨字）——名字在节点里，宽度随文字伸缩，
   高度随圆点长（走查微调轮第 1 项：恒高 26 会在近景露出胶囊上下的圆）。
   不够格（远/小/压暗）的节点仍是圆点，由 sigma 标签三通道自然切换 */
export const CAPSULE_H = 26; // **最小**高：圆点直径 + 4 超过它才长高（见 drawNodeLabel）
export const CAPSULE_PAD_X = 24; // 左右内距合计（文字宽 + 24）
export const CAPSULE_FONT_SIZE = 12;

/** `#rgb` / `#rgba` / `#rrggbb` / `#rrggbbaa` 都收。
 *
 * **短写法是必须收的，不是顺手**：这些值是从 CSS 令牌读回来的，而构建时
 * Lightning CSS 会把 `#ffffff` 压成 `#fff`。从前这里只认六位，于是浅色主题
 * 的白色令牌全部落到下面那句兜底上——静静地变成中灰 128,128,128。画布上
 * 看到的就是「浅色下每个节点都是一块灰疙瘩」：节点外壳的底色本该是纸白，
   * 读成了中灰，四层配方一层塌了（彼时还是四层壳配方；现为两层实色语言）。
   * dev 下 CSS 不压缩，所以只有打包产物发作。 */
export function hexToRgb(hex: string): [number, number, number] {
  const [r, g, b] = hexToRgba(hex);
  return [r, g, b];
}

/** 同上，连 alpha 一起。八位写法的最后两位是 alpha；没写就是 1 */
export function hexToRgba(hex: string): [number, number, number, number] {
  const m = /^#?([0-9a-f]{3,8})$/i.exec(hex.trim());
  if (!m) return [128, 128, 128, 1];
  let h = m[1];
  // 短写法每一位翻倍：#1a2 → #11aa22，#1a2f → #11aa22ff
  if (h.length === 3 || h.length === 4) h = [...h].map((c) => c + c).join("");
  if (h.length !== 6 && h.length !== 8) return [128, 128, 128, 1];
  const v = parseInt(h.slice(0, 6), 16);
  const a = h.length === 8 ? parseInt(h.slice(6), 16) / 255 : 1;
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255, a];
}

/** c1 向 c2 按 t 比例混色 */
/** 两色按比例混。**两端都走 `parseRgba`，不是 `hexToRgb`**：令牌读回来的值
 *  是 `rgb(23,23,23)` 这种写法，用只认 `#rrggbb` 的解析器会静静地退回中灰
 *  （`hexToRgb` 认不出就返回 128,128,128），于是 `mix(类型色, INK, t)` 混的
 *  不是墨色而是一团灰——选中与悬停的那圈环因此在两个主题里都发闷。
 *  从前 `INK` 是字面量 "#ffffff" 才没露馅，0038 把它改成从令牌读之后才显出来。 */
export function mix(c1: string, c2: string, t: number): string {
  const [r1, g1, b1] = parseRgba(c1);
  const [r2, g2, b2] = parseRgba(c2);
  const f = (a: number, b: number) => Math.round(a + (b - a) * t);
  return `rgb(${f(r1, r2)},${f(g1, g2)},${f(b1, b2)})`;
}

/** rgb / rgba / #hex 都收，按 t 从 from 渐到 to，**alpha 也一起渐**。
 *
 * 与 `mix` 分工：那个只吃 hex、只管把类型色按比例调进壳色（节点的配方）；
 * 这个要处理边的 `rgba(...)` 与淡入淡出，两边都得能解析、alpha 不能丢 */
function parseRgba(c: string): [number, number, number, number] {
  if (c.startsWith("#")) return hexToRgba(c);
  const m = c.match(
    /rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?/,
  );
  if (!m) return [128, 128, 128, 1];
  return [+m[1], +m[2], +m[3], m[4] !== undefined ? +m[4] : 1];
}

export function lerpColor(from: string, to: string, t: number): string {
  const a = parseRgba(from);
  const b = parseRgba(to);
  const f = (i: number) => a[i] + (b[i] - a[i]) * t;
  return `rgba(${Math.round(f(0))},${Math.round(f(1))},${Math.round(f(2))},${f(3).toFixed(3)})`;
}

/* 节点标签：**名字内嵌**——够格显名的节点是一颗类型色浅底胶囊，墨字住在里面
   （规格 graph-capsule-v2.md §1）。从前名字是挂在节点右上方的旁置药丸牌
   （白底细边、随选中/悬停换底换字），名字与节点是两个东西；现在胶囊居中
   盖在节点坐标上，宽随文字伸缩（文字宽 + 24）、高随圆点长（最小 26，
   近景不再露出胶囊上下的圆），名字就是节点本身。
   **谁够格不是这里决定的**：sigma 的标签三通道（pinned 常显 / 渲染尺寸
   阈值 / 走近密度）决定哪个名字上画布——够格 = 胶囊，不够格 = 实色圆点；
   muted 与播放淡入清 label 的既有逻辑原样生效，没有名字就没有胶囊，
   天然退回圆点，不需要胶囊自己的降级逻辑 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function drawNodeLabel(
  ctx: CanvasRenderingContext2D,
  data: any,
  _settings: any,
): void {
  // muted/淡入清了 label：这一层什么都不画（见上——天然退化圆点）
  if (!data.label) return;
  /* 正被指着的那一个不在这一层画。sigma 每帧走两趟：`renderLabels` 铺标签层，
     `renderHighlightedNodes` 把 hoveredNode 交给 drawHoverCard 铺在上面的高亮层，
     **而前者并不排除后者**。两趟都画，同一颗胶囊就在两张叠着的画布上各来一遍
     ——半透明才看得出来，实色也会多描一圈边。让开的是这一层，因为高亮层
     画在上面 */
  if (data.hideBaseLabel) return;
  ctx.save();
  /* 胶囊里的名字 12px/700（规格 v2 §1）：底是类型色 15% 染白的浅底，墨字
     靠字重立在浅彩底上，16 类全一致（v2 废除亮度自适应的墨/白混排）。从前
     的 600/700 两档并成 700 一档——胶囊语言里状态的分量由描边扛（见下），
     字重不再分档 */
  ctx.font = `700 ${CAPSULE_FONT_SIZE}px ${CANVAS_FONT}`;
  ctx.textBaseline = "middle";
  ctx.textAlign = "center";
  /* 超长名截断兜底：胶囊宽随文字伸缩，极端长名会把胶囊撑出半张画布、
     还把邻近的名字全盖掉。>120px 就从尾部截到装得下，补一个省略号 */
  const MAX_W = 120;
  let text = String(data.label);
  if (ctx.measureText(text).width > MAX_W) {
    let keep = text.length;
    while (keep > 0 && ctx.measureText(text.slice(0, keep) + "…").width > MAX_W)
      keep--;
    text = text.slice(0, keep) + "…";
  }
  /* 胶囊高度自适应盖圆（走查微调轮第 1 项"双节点残留"）。`data.size` 是
     sigma 交给标签/高亮层的**屏幕半径**：两条通路（renderLabels /
     renderHighlightedNodes）都先 `scaleSize(原始 size)`——原始 size ÷
     √cameraRatio，节点程序再 `gl_PointSize = size ÷ sizeRatio × pixelRatio
     × 2`（sigma 3.0.3 node.vert 注释原话 "x 2 to correct the formulae"），
     即圆点的 CSS 像素**直径 = 2 × data.size**。近景拉大时它轻松超过 26，
     恒高胶囊的上下沿就露出圆来——"胶囊底下还有一个节点"。于是高取
     max(最小高 26, 圆径 + 4)，屏幕上随圆同步长，远看圆小则退回 26；
     圆角取高的一半（26 高时即规格 §2 的 13），任何高度下都是全圆胶囊。
     宽仍是文字宽 + 24，只有圆径把胶囊挤得比高还窄时才抬高下限
     （宽 ≥ 高是全圆的几何前提），文字宽度不受影响 */
  const dot = 2 * data.size;
  const h = Math.max(CAPSULE_H, dot + 4);
  const r = h / 2;
  const w = Math.max(ctx.measureText(text).width + CAPSULE_PAD_X, h);
  const x = data.x;
  const y = data.y;
  /* 胶囊本体（规格 graph-capsule-v2.md §1）：类型色 15% 染白做底、1px 类型色
     发丝边、统一墨字——16 个类型色全是浅粉彩（palette.ts，与 Rust 逐字节耦合
     不可改），实底白字先天对比不足且亮度分支墨白混排不统一，v2 回到全站
     badge-soft 的同一语言。悬停/选中不再用外扩环，描边整体换主色（轻/重两档）。
     `typeColor` 是构图时烤进属性的实体/分类色（两画布同键，缺省主色）——
     胶囊因此自带类型辨识；压暗/淡入时 typeColor 退灰，染白底与灰边随 mix
     公式自动成立 */
  const typeColor = data.typeColor || NODE_CORE_BASE;
  const tint = mix(typeColor, "#ffffff", 0.15); // mix 在本文件：c1 向 c2 按 t 入白（签名 mix(c1,c2,t)）
  /* 边：labelInvert(选中) → 主色 2px；labelLift(悬停) → 主色 1.5px；默认类型
     色 1px。selectedNode 本就同时落 labelLift+labelInvert——invert 优先不是
     兜底而是常态路径：选中正是靠它从悬停的 1.5px 顶到 2px 重档（「选中压过
     指到」由 reducer 的 deferToHoverLayer 保证，同一节点不会同时画 hover 与
     selected 两副胶囊）。状态记号全在描边上，节点本体底色不被状态改写 */
  const ring = !!data.labelInvert || !!data.labelLift;
  ctx.lineWidth = data.labelInvert ? 2 : data.labelLift ? 1.5 : 1;
  ctx.strokeStyle = ring ? RING_SELECT : typeColor;
  ctx.beginPath();
  ctx.roundRect(x - w / 2, y - h / 2, w, h, r);
  ctx.fillStyle = tint;
  ctx.fill();
  ctx.stroke();
  ctx.fillStyle = CANVAS_TEXT; // 统一墨字（--color-ink），废除亮度分支（微调 9）
  ctx.fillText(text, x, y);
  ctx.restore();
}

/* 指到一个节点时画什么。**就是那颗胶囊**（`drawNodeLabel`），不是另一张卡。
   从前这里是一张浮起来的两行卡片——名字一行、类型一行，位置在节点右上方——
   于是"指着"和"选中"这两个相邻的状态长成了两种完全不同的东西：一个浮层，
   一颗贴着节点的标签。类型那一行的信息在右边面板里说得更清楚，代价是每划过
   一个节点画面上就多一张卡。
   保留这个函数名是因为 sigma 的 `defaultDrawNodeHover` 认它 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function drawHoverCard(
  ctx: CanvasRenderingContext2D,
  data: any,
  settings: any,
): void {
  // 这一层不认 `hideBaseLabel`：那道闸门说的正是"这一个交给高亮层画"，
  // 高亮层自己再让开就没人画了
  drawNodeLabel(
    ctx,
    data.hideBaseLabel ? { ...data, hideBaseLabel: false } : data,
    settings,
  );
}

/* 世界坐标网格：随相机缩放分级淡入淡出 */
const GRID_BASE_WORLD = 24; // 基准世界格距（匹配 ~300 尺度的布局）
const GRID_FADE_IN_PX = 13;
const GRID_FULL_PX = 52;
const GRID_MAX_LEVEL_PX = 480;
const GRID_MAX_ALPHA = 0.03; // 可读性轮调淡：静观无感、拖动仍有反馈

export function drawWorldGrid(canvas: HTMLCanvasElement, sigma: Sigma): void {
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const { width, height } = sigma.getDimensions();
  const dpr = window.devicePixelRatio || 1;
  const pw = Math.round(width * dpr);
  const ph = Math.round(height * dpr);
  if (canvas.width !== pw || canvas.height !== ph) {
    canvas.width = pw;
    canvas.height = ph;
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, width, height);
  if (width <= 0 || height <= 0) return;

  // 世界→屏幕：两个探针点求每世界单位像素数与原点位置（无相机旋转场景）
  const p0 = sigma.graphToViewport({ x: 0, y: 0 });
  const p1 = sigma.graphToViewport({ x: 1, y: 0 });
  const ppw = p1.x - p0.x;
  if (!Number.isFinite(ppw) || ppw <= 0) return;

  // 最细可见层级：屏幕间距 ≥ 淡入阈值的最小 4 幂格距
  let spacing = GRID_BASE_WORLD;
  while (spacing * ppw < GRID_FADE_IN_PX) spacing *= 4;
  while (spacing * ppw >= GRID_FADE_IN_PX * 4) spacing /= 4;

  for (let sp = spacing; sp * ppw < GRID_MAX_LEVEL_PX; sp *= 4) {
    const ss = sp * ppw;
    const t = Math.min(
      1,
      (ss - GRID_FADE_IN_PX) / (GRID_FULL_PX - GRID_FADE_IN_PX),
    );
    if (t <= 0) continue;
    // 网格是墨色不是白色：暗底上是白，纸底上是近黑（0038）。透明度不变
    ctx.strokeStyle = `rgba(${INK_RGB},${(GRID_MAX_ALPHA * t).toFixed(4)})`;
    ctx.lineWidth = 1;
    ctx.beginPath();
    const startX = ((p0.x % ss) + ss) % ss;
    for (let x = startX; x <= width; x += ss) {
      const px = Math.round(x) + 0.5;
      ctx.moveTo(px, 0);
      ctx.lineTo(px, height);
    }
    const startY = ((p0.y % ss) + ss) % ss;
    for (let y = startY; y <= height; y += ss) {
      const py = Math.round(y) + 0.5;
      ctx.moveTo(0, py);
      ctx.lineTo(width, py);
    }
    ctx.stroke();
  }
}

/* ---------- 令牌的读者（0038 浅色版） ----------
 * canvas 读不到 var()，所以画布用的每一个颜色都从 <html> 上算好的令牌里读一遍。
 * 上面那些 `let` 的初值是暗色，只在第一帧前、以及没有 DOM 的测试里生效；
 * `refreshPalette()` 在启动和每次切主题时把它们全部换成当前主题的值。
 * ES 模块的导出是活绑定：这里赋值，import 的那一侧读到的就是新值。 */
export let EDGE = "rgba(163,163,163,0.2)";
export let EDGE_INFERRED = "rgba(163,163,163,0.1)";
export let EDGE_DIM = "#141414";
export let EDGE_FOCUS = "rgba(255,255,255,0.55)";
export let EDGE_DERIVED = "rgba(231,197,124,0.42)";
export let EDGE_DERIVED_DIM = "rgba(231,197,124,0.14)";
export let EDGE_FOCUS_DERIVED = "rgba(255,214,140,0.95)";
export let EDGE_CONTEST = "rgba(255,106,61,0.55)";
export let EDGE_FOCUS_CONTEST = "rgba(255,106,61,1)";
export let EDGE_SUBCLASS = "rgba(235,235,235,0.55)";
export let EDGE_SUBCLASS_FOCUS = "rgba(255,255,255,0.95)";
export let EDGE_RELATION = "rgba(128,128,128,0.3)";
export let EDGE_RELATION_FOCUS = "rgba(255,255,255,0.6)";
export let EDGE_DISJOINT = "rgba(255,157,175,0.45)";
export let EDGE_DISJOINT_FOCUS = "rgba(255,157,175,0.9)";
export let EDGE_RULE = "rgba(196,165,255,0.5)";
export let EDGE_RULE_FOCUS = "rgba(196,165,255,0.95)";
export let EDGE_SCHEMA_DIM = "rgba(48,48,48,0.4)";
/* 图例=线，同源不另配：色样就是画布上那条边本身（refreshPalette 尾部直接
   取摊平后的边色，单一真源），初值随对应边的暗色初值——图例与线从此
   不可能是两套色 */
export let LEGEND_SUBCLASS = "rgba(235,235,235,0.55)"; // = EDGE_SUBCLASS
export let LEGEND_RELATION = "rgba(128,128,128,0.3)"; // = EDGE_RELATION
export let LEGEND_DISJOINT = "rgba(255,157,175,0.45)"; // = EDGE_DISJOINT
export let LEGEND_RULE = "rgba(196,165,255,0.5)"; // = EDGE_RULE
export let SCRUB_PAST = "rgba(255,255,255,0.32)";
export let SCRUB_PLAY = "rgba(255,255,255,0.62)";
export let SCRUB_FUTURE = "rgba(255,255,255,0.04)";
/** 墨色最亮的那一档（暗底白、浅底近黑）：环往它混、登录场景的粒子用它 */
export let INK = "#ffffff";
/** 墨的三元组，给需要自己调透明度的地方 */
export let INK_RGB = "255,255,255";

/* ---------- 画布专属常量（第 3 波任务 2，自过渡垫片 B 段搬家） ----------
 * 这几个值在新令牌体系里没有可读的对应：新令牌全是 hex 实色，而画布要的
 * 是「三元组 + 自己调 alpha」；个别 hex（胶囊悬停底、派生淡边、两种分类
 * 边色）要么无同值令牌，要么同值的分类令牌（--cat-*）只在 @theme 里、
 * 没被 utility 用到就不进产物，getComputedStyle 读不到。于是按垫片 B 段
 * 的亮色字面量定为常量（原变量名去前缀逐条注明），**只换读法不换色**——
 * 画布色值仍是第 2 波已走查过的那一份。 */
const LIGHT_GROUND_RGB = "246,248,251"; // 原 ground-rgb（= --color-bg #f6f8fb 的三元组；摊平边色用的纸底）
const LIGHT_INK_RGB = "15,23,42"; // 原 ink-rgb（= --color-ink #0f172a）
const LIGHT_CONTEST_RGB = "180,83,9"; // 原 contest-rgb（= #b45309，与 --color-warning-text 同值）
/* 原 violet-rgb（= #6d28d9）。**不等于 --color-violet #7c3aed**：图例那档
   读令牌（浅一档），边色用这档深的——两个值在垫片里本就不同，逐值照搬 */
const LIGHT_VIOLET_RGB = "109,40,217";
const LIGHT_EDGE_DERIVED_DIM = "#ddd6fe"; // 原 edge-derived-dim（派生淡边，无同值令牌）
const LIGHT_EDGE_SUBCLASS = "#0d9488"; // 原 edge-subclass(-focus)（= --cat-3，但 @theme 未用变量不进产物）
/* 橙红分离：关系边原 #ea580c（--cat-4）与互斥边的 danger 系红挤在同一色区，
   纸底上难辨。关系改琥珀（更黄更亮）、互斥改深红（下一条），拉开色阶；
   focus 与常态同色、只提不透明度（沿用本文件这几条边的既有 focus 惯例） */
const LIGHT_EDGE_RELATION = "#f59e0b"; // 琥珀（原 edge-relation(-focus) #ea580c = --cat-4，@theme 未用变量不进产物故仍为常量）
const LIGHT_EDGE_DISJOINT = "#db2777"; // 玫红（走查二轮：深红 #be123c 摊平后仍与琥珀同区）——带蓝调与琥珀隔半色轮，警示语义保留；α 提档防洗成淡粉

function token(name: string, fallback: string): string {
  if (typeof document === "undefined") return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}
function rgbOf(triplet: string, alpha: number): string {
  return `rgba(${triplet.replace(/\s+/g, "")},${alpha})`;
}

/** 整色（hex/rgb/rgba 都收——令牌读回来的写法不保证）换 alpha 分量。
 *  与 `rgbOf` 分工：那个收现成三元组（网格/墨色那几处），这个收一整个
 *  颜色再合成——普通边退网就是「令牌实色 + 0.55」这一路 */
function withAlpha(color: string, alpha: number): string {
  const [r, g, b] = parseRgba(color);
  return `rgba(${r},${g},${b},${alpha})`;
}

/** 浅色下把一个 rgba 边色按底色摊平成不透明的 rgb。
 *
 * sigma 的边着色器用预乘混合（ONE, ONE_MINUS_SRC_ALPHA）却不预乘 RGB，
 * 于是透明度压不暗一条边，只会把它**加**到底上：黑底上加一点灰正好是一条淡线，
 * 纸底上同一个 rgba(90,90,90,0.25) 加上去就溢出成白——「没选中时所有线都是白的」
 * 就是这么来的。暗度必须编码进 RGB（Graph.tsx 里 EDGE_DIM 那条注释说的同一件事）。
 * 把 α 在这里就混掉。 */
export function flattenOnLight(color: string): string {
  if (typeof document === "undefined") {
    return color;
  }
  /* **两种写法都要认。**令牌里写的是 `rgba(176,120,20,0.6)`，打包时 Lightning CSS
     把它压成 `#b0781499` —— 同一个颜色，十六进制带 alpha。从前这里只有一条
     `rgba(...)` 的正则，压缩之后的写法整个漏过去，半透明没摊平就交给了 sigma，
     于是那条派生边在纸底上被**加**成一道亮黄。（它没早点炸，是因为另一个 bug
     兜住了：只认六位的 `hexToRgb` 把 `#b0781499` 读成不透明的中灰，线画成了灰的。
     两个错凑成一个看着还行的结果，修好一个另一个就露出来。）
     走 `parseRgba` 就不用管写法；alpha 已经是 1 的原样退回，省一次无谓的改写。 */
  const [r, g, b, a] = parseRgba(color);
  if (a >= 1) return color;
  const ground = LIGHT_GROUND_RGB.split(",")
    .map((v) => Number(v.trim()));
  const ch = (v: number, i: number) => Math.round(v * a + ground[i] * (1 - a));
  return `rgb(${ch(r, 0)},${ch(g, 1)},${ch(b, 2)})`;
}

/** 启动时调一次，然后 `sigma.refresh()`。
 *
 * 读名 = 新令牌（styles.css 的 @theme），右侧注释是垫片 B 段的原变量名与
 * 亮色值，逐值核对过同值才换（第 3 波任务 2）；无同值令牌可读的那几个走
 * 上面的模块常量。 */
export function refreshPalette() {
  NODE_SHELL_BASE = token("--color-card", NODE_SHELL_BASE); // 原 node-shell：#ffffff
  NODE_CORE_BASE = token("--color-primary", NODE_CORE_BASE); // 原 node-core：#1677ff
  NODE_BORDER_BASE = token("--color-line-strong", NODE_BORDER_BASE); // 原 node-border：#cbd5e1
  MUTED_SHELL = token("--color-ink-3", MUTED_SHELL); // 原 node-muted：#94a3b8
  POP_BG = token("--color-card", POP_BG); // 原 pop-surface：#ffffff（styles.css 的 .pop 同源）
  CANVAS_TEXT = token("--color-ink", CANVAS_TEXT); // 原 text：#0f172a
  CANVAS_TEXT_2 = token("--color-ink-2", CANVAS_TEXT_2); // 原 text-2：#475569
  // 普通边退为结构网（规格 §2.2）：α0.55——远看是安静的灰网，
  // 选中/hover 的 boost 亮线才跳出来。禁用/推断/派生/争议边保持原值
  EDGE = withAlpha(token("--color-ink-3", "#94a3b8"), 0.55);
  // 方向 A（规格 §3）：推断边升一档墨（#475569），与普通边（ink-3）拉开。
  // 原计划虚线 8/5，sigma 3 无 dash 支持，走「实线+色档」备选
  EDGE_INFERRED = token("--color-ink-2", EDGE_INFERRED); // 原 edge-inferred：#cbd5e1
  EDGE_DIM = token("--color-border", EDGE_DIM); // 原 edge-dim：#e5eaf1
  EDGE_FOCUS = token("--color-primary", EDGE_FOCUS); // 原 edge-focus：#1677ff
  RING_SELECT = token("--color-primary", RING_SELECT); // 选中环与节点主色同源（双蓝收口，reskin-b1）
  EDGE_DERIVED = token("--color-violet", EDGE_DERIVED); // 原 edge-derived：#7c3aed
  EDGE_DERIVED_DIM = LIGHT_EDGE_DERIVED_DIM; // 原 edge-derived-dim：#ddd6fe（常量）
  EDGE_FOCUS_DERIVED = token("--color-violet", EDGE_FOCUS_DERIVED); // 原 edge-derived-focus：#7c3aed
  EDGE_SUBCLASS = LIGHT_EDGE_SUBCLASS; // 原 edge-subclass：#0d9488（常量）
  EDGE_SUBCLASS_FOCUS = LIGHT_EDGE_SUBCLASS; // 原 edge-subclass-focus：#0d9488
  EDGE_RELATION = LIGHT_EDGE_RELATION; // 原 edge-relation #ea580c → 琥珀（橙红分离，常量）
  EDGE_RELATION_FOCUS = LIGHT_EDGE_RELATION; // focus 与常态同色（这两条边的既有惯例）
  EDGE_SCHEMA_DIM = token("--color-line-strong", EDGE_SCHEMA_DIM); // 原 edge-schema-dim：#cbd5e1
  SCRUB_PAST = token("--color-ink-3", SCRUB_PAST); // 原 scrub-past：#94a3b8
  SCRUB_PLAY = token("--color-primary", SCRUB_PLAY); // 原 scrub-play：#1677ff
  SCRUB_FUTURE = token("--color-line-strong", SCRUB_FUTURE); // 原 scrub-future：#cbd5e1
  INK_RGB = LIGHT_INK_RGB; // 原 ink-rgb：15,23,42（常量）
  INK = rgbOf(INK_RGB, 1);
  // 语义色按透明度分档：争议边、规则边走三元组常量；不相交边走深红常量
  // （橙红分离，见 LIGHT_EDGE_DISJOINT），focus 与常态同色、只提不透明度
  const contest = LIGHT_CONTEST_RGB;
  const violet = LIGHT_VIOLET_RGB;
  EDGE_CONTEST = rgbOf(contest, 0.55);
  EDGE_FOCUS_CONTEST = rgbOf(contest, 1);
  EDGE_DISJOINT = withAlpha(LIGHT_EDGE_DISJOINT, 0.6);
  EDGE_DISJOINT_FOCUS = withAlpha(LIGHT_EDGE_DISJOINT, 0.95);
  EDGE_RULE = rgbOf(violet, 0.5);
  EDGE_RULE_FOCUS = rgbOf(violet, 0.95);
  // 画布上的边：浅色下按底色摊平（见 flattenOnLight）
  EDGE = flattenOnLight(EDGE);
  EDGE_INFERRED = flattenOnLight(EDGE_INFERRED);
  EDGE_FOCUS = flattenOnLight(EDGE_FOCUS);
  EDGE_DERIVED = flattenOnLight(EDGE_DERIVED);
  EDGE_DERIVED_DIM = flattenOnLight(EDGE_DERIVED_DIM);
  EDGE_FOCUS_DERIVED = flattenOnLight(EDGE_FOCUS_DERIVED);
  EDGE_CONTEST = flattenOnLight(EDGE_CONTEST);
  EDGE_FOCUS_CONTEST = flattenOnLight(EDGE_FOCUS_CONTEST);
  EDGE_SUBCLASS = flattenOnLight(EDGE_SUBCLASS);
  EDGE_SUBCLASS_FOCUS = flattenOnLight(EDGE_SUBCLASS_FOCUS);
  EDGE_RELATION = flattenOnLight(EDGE_RELATION);
  EDGE_RELATION_FOCUS = flattenOnLight(EDGE_RELATION_FOCUS);
  EDGE_DISJOINT = flattenOnLight(EDGE_DISJOINT);
  EDGE_DISJOINT_FOCUS = flattenOnLight(EDGE_DISJOINT_FOCUS);
  EDGE_RULE = flattenOnLight(EDGE_RULE);
  EDGE_RULE_FOCUS = flattenOnLight(EDGE_RULE_FOCUS);
  EDGE_SCHEMA_DIM = flattenOnLight(EDGE_SCHEMA_DIM);
  /* 图例=线，同源不另配（单一真源）：第三波亮色迁移时边色换新四色、图例
     却被指到墨色令牌（ink/ink-2），图例色样（近黑）与实际边色对不上。
     现直接取上面摊平后的边色——边的色档怎么调图例自动跟随，
     OntologySchemaGraph 的图例渲染零改动 */
  LEGEND_SUBCLASS = EDGE_SUBCLASS;
  LEGEND_RELATION = EDGE_RELATION;
  LEGEND_DISJOINT = EDGE_DISJOINT;
  LEGEND_RULE = EDGE_RULE;
}
