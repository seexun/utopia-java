/* Utopia 领域页 UI 原件（W1，任务 5）：对照旧仓 web/src/ui/index.tsx 的读面子集
   重写为 Vue（tsx 渲染函数，与旧仓 JSX 逐行同构）。只迁 W1 页族用到的原件；
   写弹窗/表单族原件随任务 6-7 补。
   皮（.badge-soft/.pill/.tool/.link-inline/.skel…）在 src/style/utopia-pages.css，
   设计令牌在 src/style/tailwind.css 的 @theme 段——两处的值都逐字取自旧仓
   styles.css，视觉对齐按构造成立。 */
import {
  type CSSProperties,
  type PropType,
  type VNode,
  defineComponent,
  computed
} from "vue";
import { RouterLink } from "vue-router";
import ChevronLeft from "~icons/lucide/chevron-left";
import ChevronRight from "~icons/lucide/chevron-right";
import ArrowUpRight from "~icons/lucide/arrow-up-right";
import { S } from "@/i18n";

export function cn(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(" ");
}

/** 旧 tone 名 → 新徽标类（旧仓 BADGE_TONE 逐字） */
const BADGE_TONE: Record<ChipTone, string> = {
  neutral: "badge-soft-info",
  success: "badge-soft-success",
  warn: "badge-soft-warning",
  danger: "badge-soft-danger",
  info: "badge-soft-primary",
  violet: "badge-soft-primary",
  contest: "badge-soft-warning"
};

export type ChipTone =
  | "neutral"
  | "info"
  | "success"
  | "warn"
  | "danger"
  | "violet"
  | "contest";

export function chipLike(tone: ChipTone = "neutral", className?: string): string {
  return cn("badge-soft", BADGE_TONE[tone], className);
}

/* ---------- Chip / Status ---------- */

export const UChip = defineComponent({
  name: "UChip",
  props: {
    tone: { type: String as PropType<ChipTone>, default: "neutral" },
    title: { type: String, default: undefined },
    clickable: { type: Boolean, default: false }
  },
  setup(props, { slots, attrs }) {
    return () => {
      const cls = cn(
        "badge-soft",
        BADGE_TONE[props.tone],
        props.clickable && "cursor-pointer",
        attrs.class as string
      );
      const body = slots.default?.();
      const rest = { ...attrs, class: undefined };
      if (props.clickable) {
        return (
          <button type="button" title={props.title} class={cls} {...rest}>
            {body}
          </button>
        );
      }
      return (
        <span title={props.title} class={cls} {...rest}>
          {body}
        </span>
      );
    };
  }
});

export const UStatus = defineComponent({
  name: "UStatus",
  props: {
    tone: { type: String as PropType<ChipTone>, default: "neutral" },
    pulse: { type: Boolean, default: false },
    title: { type: String, default: undefined }
  },
  setup(props, { slots, attrs }) {
    return () => (
      <span
        class={cn("badge-soft", BADGE_TONE[props.tone], attrs.class as string)}
        title={props.title}
      >
        <span
          class={cn(
            "h-1.5 w-1.5 shrink-0 rounded-full bg-current",
            props.pulse && "animate-pulse"
          )}
        />
        {slots.default?.()}
      </span>
    );
  }
});

/* ---------- LinkButton（长得像一句话的动作） ---------- */

export const LinkButton = defineComponent({
  name: "LinkButton",
  props: {
    tone: { type: String as PropType<"default" | "danger">, default: "default" },
    underline: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false }
  },
  emits: ["click"],
  setup(props, { slots, attrs, emit }) {
    return () => (
      <button
        type="button"
        disabled={props.disabled}
        class={cn(
          "link-inline text-sub",
          props.tone === "danger" && "is-danger",
          props.underline && "underline",
          attrs.class as string
        )}
        style={attrs.style as CSSProperties}
        title={attrs.title as string}
        onClick={(e: MouseEvent) => emit("click", e)}
      >
        {slots.default?.()}
      </button>
    );
  }
});

/* ---------- GroupLabel / PageHeader ---------- */

export const GroupLabel = defineComponent({
  name: "GroupLabel",
  props: {
    count: { type: [Number, String], default: undefined },
    tone: { type: String as PropType<"default" | "contest">, default: "default" }
  },
  setup(props, { slots, attrs }) {
    return () => (
      <div
        class={cn(
          "flex items-center gap-2 text-sub font-medium",
          props.tone === "contest" ? "text-warning" : "text-ink-2",
          attrs.class as string
        )}
      >
        {slots.icon?.()}
        <span class="min-w-0 truncate">{slots.default?.()}</span>
        {props.count !== undefined && (
          <span class="tabular-nums">{props.count}</span>
        )}
      </div>
    );
  }
});

export const PageHeader = defineComponent({
  name: "PageHeader",
  props: {
    className: { type: String, default: "mb-6" }
  },
  setup(props, { slots }) {
    return () => (
      <div class={props.className}>
        <div class="flex items-baseline justify-between gap-4">
          <h1 class="min-w-0 text-page font-extrabold text-ink break-words">
            {slots.title?.()}
          </h1>
          {slots.actions && (
            <div class="flex shrink-0 items-center gap-2">
              {slots.actions()}
            </div>
          )}
        </div>
        {slots.sub && <p class="mt-1 text-sub text-ink-2">{slots.sub()}</p>}
      </div>
    );
  }
});

/* ---------- Row（行）与 RailItem（左栏导航行） ---------- */

export type RowDensity = "list" | "nav" | "menu";

export function rowClass(
  active?: boolean,
  density: RowDensity = "list",
  tone?: "danger" | "warn",
  sub?: boolean
): string {
  return cn(
    "group flex w-full items-center gap-2 text-left transition-colors",
    density === "menu" ? "rounded-none" : "rounded-ctl",
    sub && "pl-[34px]",
    density === "nav"
      ? "px-3 py-1.5 text-body font-medium"
      : density === "menu"
        ? "px-3 py-2 text-sub"
        : "px-2 py-1 text-body",
    active
      ? "bg-primary-soft text-primary font-bold"
      : tone === "danger"
        ? "text-danger hover:bg-fill"
        : tone === "warn"
          ? "text-warning hover:bg-fill"
          : "text-ink-2 hover:bg-fill hover:text-ink"
  );
}

export const ROW_TRAILING =
  "ml-auto shrink-0 text-aux text-ink-2 group-hover:text-ink";
export const ROW_VALUE =
  "min-w-0 flex-1 truncate text-aux text-ink-2 group-hover:text-ink";
export const ROW_HOVER = "rounded-ctl transition-colors hover:bg-fill";
export const HOVER_ROW = "group -mx-2 flex items-start gap-2 rounded-ctl px-2 py-1 transition-colors hover:bg-fill";
export const REVEAL = "reveal";

export const URow = defineComponent({
  name: "URow",
  props: {
    active: { type: Boolean, default: false },
    tone: {
      type: String as PropType<"danger" | "warn" | undefined>,
      default: undefined
    },
    density: { type: String as PropType<RowDensity>, default: "list" },
    disabled: { type: Boolean, default: false },
    title: { type: String, default: undefined },
    /** 树形缩进的层级（旧仓 Row indent 逐字） */
    indent: { type: Number, default: 0 },
    /** 收掉图标那一格：整组行都没有图标时省出对齐空位 */
    flush: { type: Boolean, default: false },
    /** 图标：prop 传 VNode（tsx 调用方）或 #icon 插槽（SFC 调用方），prop 优先 */
    icon: { type: Object as PropType<VNode>, default: undefined }
  },
  emits: ["click"],
  setup(props, { slots, attrs, emit }) {
    return () => {
      const iconNode = props.icon ?? slots.icon?.();
      const body = (
        <>
          {iconNode ? (
            <span class="shrink-0">{iconNode}</span>
          ) : props.density === "nav" && !props.flush ? (
            <span class="w-3.5 shrink-0" aria-hidden />
          ) : null}
          <span class="min-w-0 flex-1 truncate">{slots.default?.()}</span>
        </>
      );
      return (
        <button
          type="button"
          disabled={props.disabled}
          title={props.title}
          aria-current={props.active ? "true" : undefined}
          style={[
            props.indent
              ? { paddingLeft: `${8 + props.indent * 14}px` }
              : undefined,
            attrs.style as CSSProperties
          ]}
          class={cn(
            rowClass(props.active, props.density, props.tone),
            attrs.class as string
          )}
          onClick={() => emit("click")}
        >
          {body}
          {slots.trailing && (
            <span class={ROW_TRAILING}>{slots.trailing()}</span>
          )}
        </button>
      );
    };
  }
});

/** 行样子的 RouterLink（实例列表/来源行这类「整行可点去别处」的读面） */
export const RouterRowLink = defineComponent({
  name: "RouterRowLink",
  props: {
    to: { type: [String, Object] as PropType<unknown>, required: true }
  },
  setup(props, { slots }) {
    return () => (
      <RouterLink
        to={props.to as never}
        class="group flex w-full items-center gap-2 rounded-ctl px-2 py-1 text-left text-body text-ink-2 transition-colors hover:bg-fill hover:text-ink"
      >
        {slots.default?.()}
      </RouterLink>
    );
  }
});

export const RailItem = defineComponent({
  name: "RailItem",
  props: {
    active: { type: Boolean, default: false },
    count: { type: Number, default: undefined },
    dot: { type: String, default: undefined },
    external: { type: Boolean, default: false }
  },
  emits: ["click"],
  setup(props, { slots, attrs, emit }) {
    return () => (
      <URow
        density="nav"
        active={props.active}
        class={attrs.class as string}
        onClick={() => emit("click")}
      >
        {{
          icon: () => slots.icon?.(),
          default: () => (
            <span class="flex min-w-0 items-center gap-2">
              <span class="truncate">{slots.default?.()}</span>
              {props.dot && (
                <span
                  class={cn("h-1.5 w-1.5 shrink-0 rounded-full", props.dot)}
                />
              )}
              {props.external && <ArrowUpRight class="shrink-0 opacity-50" />}
            </span>
          ),
          trailing: () =>
            props.count !== undefined && props.count > 0 ? (
              <span class={chipLike("neutral", "tabular-nums")}>
                {props.count}
              </span>
            ) : undefined
        }}
      </URow>
    );
  }
});

/* ---------- Segmented（EP 分段：灰槽 + 白色选中片） ---------- */

export interface SegmentedOption<T extends string> {
  value: T;
  label: VNode | string;
  count?: number;
  title?: string;
}

export const USegmented = defineComponent({
  name: "USegmented",
  props: {
    value: { type: String, required: true },
    options: {
      type: Array as PropType<SegmentedOption<string>[]>,
      required: true
    },
    size: { type: String as PropType<"sm" | "md">, default: "md" },
    fill: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false }
  },
  emits: ["change"],
  setup(props, { emit, attrs }) {
    return () => (
      <div
        role="tablist"
        class={cn(
          "flex gap-1 rounded-ctl bg-fill p-1",
          props.fill && "w-full",
          props.disabled && "opacity-40",
          attrs.class as string
        )}
      >
        {props.options.map(o => {
          const active = o.value === props.value;
          return (
            <button
              key={o.value}
              type="button"
              role="tab"
              aria-selected={active}
              title={o.title}
              disabled={props.disabled}
              onClick={() => emit("change", o.value)}
              class={cn(
                "flex items-center justify-center gap-1 rounded-ctl font-medium transition-colors",
                props.size === "sm" ? "px-2 py-1 text-aux" : "px-3 py-1 text-sub",
                props.fill && "flex-1",
                active
                  ? "bg-card text-primary shadow-card"
                  : "text-ink-2 hover:text-ink"
              )}
            >
              {o.label}
              {o.count !== undefined && o.count > 0 && (
                <span class="tabular-nums">{o.count}</span>
              )}
            </button>
          );
        })}
      </div>
    );
  }
});

/* ---------- Pill（画布上的玻璃药丸） ---------- */

export const UPill = defineComponent({
  name: "UPill",
  props: {
    active: { type: Boolean, default: false },
    dim: { type: Boolean, default: false },
    static: { type: Boolean, default: false },
    title: { type: String, default: undefined }
  },
  emits: ["click"],
  setup(props, { slots, attrs, emit }) {
    return () => (
      <button
        type="button"
        title={props.title}
        class={cn(
          "pill",
          props.active && "is-on",
          props.dim && "is-dim",
          props.static && "is-static",
          attrs.class as string
        )}
        onClick={() => emit("click")}
      >
        {slots.default?.()}
      </button>
    );
  }
});

/* ---------- 工具塔（图谱左下控件） ---------- */

export const ToolTower = defineComponent({
  name: "ToolTower",
  setup(_, { slots, attrs }) {
    return () => (
      <div
        class={cn(
          "tower bg-card border border-line shadow-pop flex flex-col overflow-hidden rounded-card",
          attrs.class as string
        )}
      >
        {slots.default?.()}
      </div>
    );
  }
});

export const ToolButton = defineComponent({
  name: "ToolButton",
  props: {
    active: { type: Boolean, default: false },
    label: { type: String, required: true },
    disabled: { type: Boolean, default: false }
  },
  emits: ["click"],
  setup(props, { slots, attrs, emit }) {
    return () => (
      <button
        type="button"
        disabled={props.disabled}
        title={attrs.title as string}
        aria-label={props.label}
        class={cn("tool", props.active && "is-on", attrs.class as string)}
        style={attrs.style as CSSProperties}
        onClick={() => emit("click")}
      >
        {slots.icon?.()}
        <span class="tower-label">{props.label}</span>
      </button>
    );
  }
});

export const ToolDivider = defineComponent({
  name: "ToolDivider",
  setup() {
    return () => <div class="mx-2 h-px bg-line-strong" />;
  }
});

/* ---------- 分页 ---------- */

/** 分页切片辅助：返回当前页数据与安全页号（旧仓 pageSlice 逐字）。 */
export function pageSlice<T>(
  items: T[],
  page: number,
  pageSize: number
): { rows: T[]; safe: number } {
  const pageCount = Math.max(1, Math.ceil(items.length / pageSize));
  const safe = Math.min(page, pageCount - 1);
  return {
    rows: items.slice(safe * pageSize, (safe + 1) * pageSize),
    safe
  };
}

export const UPager = defineComponent({
  name: "UPager",
  props: {
    total: { type: Number, required: true },
    pageSize: { type: Number, required: true },
    /** 0 基页号（旧仓口径） */
    page: { type: Number, required: true },
    always: { type: Boolean, default: false },
    className: { type: String, default: "mt-3" }
  },
  emits: ["page"],
  setup(props, { emit }) {
    const pageCount = computed(() =>
      Math.max(1, Math.ceil(props.total / props.pageSize))
    );
    const safe = computed(() => Math.min(props.page, pageCount.value - 1));
    return () => {
      if (props.total <= props.pageSize && !props.always) return null;
      return (
        <div
          class={cn(
            "flex items-center justify-end gap-2 text-sub text-ink-2",
            props.className
          )}
        >
          <span class="tabular-nums">
            {S.library.pageOf(
              safe.value * props.pageSize + 1,
              Math.min((safe.value + 1) * props.pageSize, props.total),
              props.total
            )}
          </span>
          <button
            type="button"
            onClick={() => emit("page", safe.value - 1)}
            disabled={safe.value === 0}
            class="btn-ghost h-7 w-7"
          >
            <ChevronLeft />
          </button>
          <button
            type="button"
            onClick={() => emit("page", safe.value + 1)}
            disabled={safe.value >= pageCount.value - 1}
            class="btn-ghost h-7 w-7"
          >
            <ChevronRight />
          </button>
        </div>
      );
    };
  }
});

/* ---------- IconButton（方寸动作：一行文档的「再跑一遍」、弹窗关闭） ---------- */

export const UIconButton = defineComponent({
  name: "UIconButton",
  props: {
    label: { type: String, required: true },
    disabled: { type: Boolean, default: false }
  },
  emits: ["click"],
  setup(props, { slots, attrs, emit }) {
    return () => (
      <button
        type="button"
        disabled={props.disabled}
        title={attrs.title as string}
        aria-label={props.label}
        class={cn(
          "inline-flex h-7 w-7 items-center justify-center rounded-ctl text-ink-2 transition-colors hover:bg-fill hover:text-ink",
          attrs.class as string
        )}
        onClick={(e: MouseEvent) => emit("click", e)}
      >
        {slots.default?.()}
      </button>
    );
  }
});

/* ---------- 等待原件 ---------- */

export const Loading = defineComponent({
  name: "ULoading",
  props: { text: { type: String, default: undefined } },
  setup(props) {
    return () => (
      <div class="p-8 text-body text-ink-2">{props.text ?? S.nav.loading}</div>
    );
  }
});

export const USkeleton = defineComponent({
  name: "USkeleton",
  props: {
    className: { type: String, default: "" },
    style: { type: Object as PropType<CSSProperties>, default: undefined }
  },
  setup(props) {
    return () => (
      <div class={cn("skel rounded-ctl", props.className)} style={props.style} />
    );
  }
});

export const SkeletonRows = defineComponent({
  name: "SkeletonRows",
  props: {
    rows: { type: Number, default: 8 },
    density: { type: String as PropType<RowDensity>, default: "list" }
  },
  setup(props) {
    return () => (
      <div class="flex flex-col gap-0.5" aria-hidden>
        {Array.from({ length: props.rows }, (_, i) => (
          <URow key={i} density={props.density} disabled>
            <USkeleton class="h-4 w-full" />
          </URow>
        ))}
      </div>
    );
  }
});

export const Spinner = defineComponent({
  name: "USpinner",
  props: {
    size: { type: Number, default: 14 },
    label: { type: String, default: undefined }
  },
  setup(props) {
    return () => (
      <span
        class="inline-flex items-center gap-2 text-sub text-ink-2"
        role="status"
      >
        <span
          class="inline-block animate-spin rounded-full border-2 border-current border-t-transparent"
          style={{ width: props.size, height: props.size }}
          aria-hidden
        />
        {props.label && <span>{props.label}</span>}
      </span>
    );
  }
});

export const CanvasLoading = defineComponent({
  name: "CanvasLoading",
  props: { size: { type: Number, default: 28 } },
  setup(props) {
    return () => (
      <div class="pointer-events-none absolute inset-0 grid place-items-center">
        <Spinner size={props.size} label={S.nav.loading} />
      </div>
    );
  }
});

export const EmptyState = defineComponent({
  name: "UEmptyState",
  props: {
    title: { type: String, required: true },
    hint: { type: String, default: undefined }
  },
  setup(props) {
    return () => (
      <div class="text-center text-body text-ink-2">
        <p>{props.title}</p>
        {props.hint && <p class="mt-2 text-sub text-ink-2">{props.hint}</p>}
      </div>
    );
  }
});
