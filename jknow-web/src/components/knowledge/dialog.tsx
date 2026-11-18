/* 对话框三件（W2 写路径）：UDialog / DangerConfirm / UFormDialog。
   对照旧仓 ui/dialog.tsx 逐行同构换框架——皮是 utopia-pages.css 的
   .dialog-panel 一族（白实底 + 12px 圆角 + 顶部/底部分隔行）：确认框要人
   读一句话然后做一个不可逆的决定，近实底而不是玻璃。按钮由调用方给
   （el-button——EP 纪律；本组件不内置按钮）。 */
import {
  Teleport,
  type PropType,
  type VNode,
  defineComponent,
  ref,
  computed,
  nextTick,
  onMounted,
  onScopeDispose
} from "vue";

export type DialogWidth = "sm" | "md" | "lg";
const WIDTH: Record<DialogWidth, string> = {
  sm: "w-96",
  md: "w-[32rem]",
  lg: "w-[44rem]"
};

const UDialog = defineComponent({
  name: "UDialog",
  props: {
    open: { type: Boolean, default: false },
    /** 右上角关闭按钮的无障碍名称——页面从 S 里取，组件不认识语言 */
    closeLabel: { type: String, required: true },
    width: {
      type: String as PropType<DialogWidth>,
      default: "md" as DialogWidth
    }
  },
  emits: ["update:open"],
  setup(props, { slots, emit }) {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && props.open) emit("update:open", false);
    };
    onMounted(() => window.addEventListener("keydown", onKey));
    onScopeDispose(() => window.removeEventListener("keydown", onKey));
    return () => {
      if (!props.open) return null;
      return (
        <Teleport to="body">
          <div
            class="modal-scrim fixed inset-0 z-50 grid place-items-center overflow-y-auto p-4"
            onMousedown={(e: MouseEvent) => {
              if (e.target === e.currentTarget) emit("update:open", false);
            }}
          >
            <div
              class={["dialog-panel modal-in max-w-full outline-none", WIDTH[props.width]]}
              role="dialog"
              aria-modal="true"
            >
              <div class="dialog-head">
                <div class="min-w-0 flex-1">
                  <div class="text-ink">{slots.title?.()}</div>
                  {slots.description ? (
                    <div class="mt-0.5 text-sub font-normal text-ink-2">
                      {slots.description?.()}
                    </div>
                  ) : null}
                </div>
                <button
                  type="button"
                  class="rounded-ctl p-1.5 text-ink-2 hover:bg-fill hover:text-ink"
                  aria-label={props.closeLabel}
                  title={props.closeLabel}
                  onClick={() => emit("update:open", false)}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M18 6 6 18M6 6l12 12" />
                  </svg>
                </button>
              </div>
              {slots.default ? <div class="dialog-body">{slots.default()}</div> : null}
              {slots.footer ? <div class="dialog-foot">{slots.footer()}</div> : null}
            </div>
          </div>
        </Teleport>
      );
    };
  }
});

/** 危险操作确认：可要求逐字输入指定文本解锁（如资源名称）；缺省直接可确认。 */
export const DangerConfirm = defineComponent({
  name: "UDangerConfirm",
  props: {
    title: { type: String, required: true },
    hint: { type: String, required: true },
    /** 要求逐字输入的解锁文本（如资源名称）；缺省则直接可确认 */
    requireText: { type: String, default: undefined },
    confirmLabel: { type: String, required: true },
    cancelLabel: { type: String, required: true },
    busy: { type: Boolean, default: false },
    width: {
      type: String as PropType<DialogWidth>,
      default: "sm" as DialogWidth
    }
  },
  emits: ["confirm", "cancel"],
  setup(props, { slots, emit }) {
    const text = ref("");
    const unlocked = computed(
      () => !props.requireText || text.value === props.requireText
    );
    // 逐字解锁输入框自动聚焦（W2 审查 I-2，同 W1 M-8 口径：autofocus 不是 EP
    // 的 API，得拿 ref 调 focus）——组件挂载即弹层已开，下一帧聚焦才拿得到
    const unlockInput = ref<{ focus: () => void } | null>(null);
    onMounted(() => nextTick(() => unlockInput.value?.focus()));
    return () => (
      <UDialog
        open
        {...{ "onUpdate:open": (o: boolean) => !o && emit("cancel") }}
        closeLabel={props.cancelLabel}
        width={props.width}
        v-slots={{
          title: () => <span class="text-danger">{props.title}</span>,
          description: () => props.hint,
          default: () => (
            <>
              {props.requireText && (
                <el-input
                  ref={unlockInput}
                  class="w-full"
                  placeholder={props.requireText}
                  modelValue={text.value}
                  {...{
                    "onUpdate:modelValue": (v: string) => (text.value = v)
                  }}
                />
              )}
              {slots.default?.()}
            </>
          ),
          footer: () => (
            <>
              <el-button
                size="small"
                onClick={() => emit("cancel")}
              >
                {props.cancelLabel}
              </el-button>
              <el-button
                type="danger"
                size="small"
                disabled={!unlocked.value || props.busy}
                onClick={() => emit("confirm")}
              >
                {props.confirmLabel}
              </el-button>
            </>
          )
        }}
      />
    );
  }
});

export type DangerAction = {
  label: string;
  disabled?: boolean;
  title?: string;
  onClick: () => void;
};

/** 编辑弹窗：面板只展示，改动开一扇窗。删除/停用这类破坏性动作放左下角、
 *  次要样式——它和保存不是一对，隔开才不会顺手点到。正文包在 form 里：
 *  输入框里按 Enter 就是保存。 */
export const UFormDialog = defineComponent({
  name: "UFormDialog",
  props: {
    title: { type: [String, Object] as PropType<string | VNode>, required: true },
    description: {
      type: [String, Object] as PropType<string | VNode | undefined>,
      default: undefined
    },
    closeLabel: { type: String, required: true },
    saveLabel: { type: String, required: true },
    cancelLabel: { type: String, required: true },
    width: {
      type: String as PropType<DialogWidth>,
      default: "md" as DialogWidth
    },
    canSave: { type: Boolean, default: true },
    busy: { type: Boolean, default: false }
  },
  emits: ["save", "cancel"],
  setup(props, { slots, emit }) {
    const submit = () => {
      if (props.canSave && !props.busy) emit("save");
    };
    return () => (
      <UDialog
        open
        {...{ "onUpdate:open": (o: boolean) => !o && emit("cancel") }}
        closeLabel={props.closeLabel}
        width={props.width}
        v-slots={{
          title: () =>
            typeof props.title === "string" ? <span>{props.title}</span> : props.title,
          description: () =>
            props.description == null
              ? null
              : typeof props.description === "string"
                ? <span>{props.description}</span>
                : props.description,
          default: () => (
            <form
              onSubmit={(e: Event) => {
                e.preventDefault();
                submit();
              }}
            >
              {slots.default?.()}
            </form>
          ),
          footer: () => (
            <>
              {slots.danger && (
                <div class="foot-danger">{slots.danger?.()}</div>
              )}
              <el-button size="small" onClick={() => emit("cancel")}>
                {props.cancelLabel}
              </el-button>
              <el-button
                type="primary"
                size="small"
                disabled={!props.canSave}
                loading={props.busy}
                onClick={submit}
              >
                {props.saveLabel}
              </el-button>
            </>
          )
        }}
      />
    );
  }
});

export { UDialog };
