/* 来源设置弹窗（遗留 3 收口，对照旧仓 pages/Library.tsx 的 SourceEditModal）：
   改名/图标（仅 custom）/类型专属摄取配置/可视化日程 + 底部 danger zone 删除。
   与新建 SourceModal 同族（UDialog 直绘 + Field 布局 + SchedulePicker）：
   - 类型只读：换类型 = 换身份，应新建来源（旧仓注释原话）
   - 配置面只覆盖旧仓编辑窗有的四类（url/rss/custom/github_issues）；其余拉取型
     （jira/s3/azure_blob/gcs/webdav/notion）只给改名与日程——与旧仓同口径，
     摄取配置改动 = 文档集合要换，走「删了重建」更稳
   - auth_header 留空 = 后端保留库里原值（凭据只进不出，旧仓注释原话） */
import { computed, defineComponent, h, ref, type Component, type PropType } from "vue";
import { useMutation } from "@tanstack/vue-query";
import { updateSource } from "@/api/knowledge/sources";
import { UDialog, DangerConfirm } from "@/components/knowledge/dialog";
import { SOURCE_ICONS, KIND_ICON, SYNCING_KINDS } from "@/components/knowledge/sources-rail";
import { SchedulePicker, type ScheduleValue } from "./schedule-picker";
import { S } from "@/i18n";
import type { SourceView } from "@/api/knowledge/types";

type RssContentMode = "full_new_items" | "feed";

/** tsx 里动态组件统一走 h()（source-modal 同款） */
const kindIcon = (k: string): Component => KIND_ICON[k] || KIND_ICON.folder;
const iconVNode = (Ic: Component, size = 12, cls = "shrink-0 text-ink-2") =>
  h(Ic as never, { width: size, height: size, class: cls });

/** 注意用 div 而非 label：label 会把 :hover/click 转发给内部第一个可标记控件
    （source-modal 的 Field 同款） */
const Field = defineComponent({
  name: "USourceEditField",
  props: { label: { type: String, required: true } },
  setup(props, { slots }) {
    return () => (
      <div class="mb-3">
        <div class="mb-1 text-aux font-medium text-ink-2">{props.label}</div>
        {slots.default?.()}
      </div>
    );
  }
});

export const SourceEditModal = defineComponent({
  name: "USourceEditModal",
  props: {
    kbId: { type: String, required: true },
    source: { type: Object as PropType<SourceView>, required: true },
    /** 保存成功（父级失效查询并关窗） */
    onDone: { type: Function as PropType<() => void>, required: true },
    /** 删除确认后（父级关窗并调删除 mutation——与旧仓 onDelete 同分工） */
    onDelete: { type: Function as PropType<() => void>, required: true }
  },
  setup(props) {
    const kind = props.source.kind;
    const cfg = props.source.config ?? {};
    const name = ref(props.source.name);
    const icon = ref<string | null>(props.source.icon);
    const urls = ref((cfg.urls ?? []).join("\n"));
    const feedUrl = ref(cfg.feed_url ?? "");
    const rssContentMode = ref<RssContentMode>(
      cfg.content_mode === "full_new_items" ? "full_new_items" : "feed"
    );
    const endpoint = ref(cfg.endpoint ?? "");
    const authHeader = ref("");
    const repo = ref(cfg.repo ?? "");
    const includePrs = ref(Boolean(cfg.include_pull_requests));
    const confirmingDelete = ref(false);
    const schedule = ref<ScheduleValue>({
      sync_interval_minutes: props.source.sync_interval_minutes,
      sync_cron: props.source.sync_cron
    });
    const syncing = SYNCING_KINDS.has(kind);
    const KindIcon = kindIcon(kind);
    const kindLabels = S.library.sourceKinds as Record<string, string>;
    const iconKeys = Object.keys(SOURCE_ICONS);

    // 摄取配置是否有改动——有则展示「旧文档去留」说明（旧仓 ingestChanged 同款）
    const ingestChanged = computed(
      () =>
        (kind === "url" &&
          urls.value.trim() !== (cfg.urls ?? []).join("\n").trim()) ||
        (kind === "rss" &&
          (feedUrl.value.trim() !== (cfg.feed_url ?? "") ||
            rssContentMode.value !==
              (cfg.content_mode === "full_new_items" ? "full_new_items" : "feed"))) ||
        (kind === "custom" && endpoint.value.trim() !== (cfg.endpoint ?? "")) ||
        // 换仓库或改收不收 PR，两者都会换掉这个来源里文档的集合
        (kind === "github_issues" &&
          (repo.value.trim() !== (cfg.repo ?? "") ||
            includePrs.value !== Boolean(cfg.include_pull_requests)))
    );

    // 保存成功只上报 onDone——失效面（sources/documents 键）归父级（旧仓同分工）
    const save = useMutation({
      mutationFn: () => {
        const config =
          kind === "url"
            ? {
                urls: urls.value
                  .split("\n")
                  .map(u => u.trim())
                  .filter(Boolean)
              }
            : kind === "rss"
              ? { feed_url: feedUrl.value.trim(), content_mode: rssContentMode.value }
              : kind === "custom"
                ? {
                    endpoint: endpoint.value.trim(),
                    // 留空 = 后端保留库里原值（凭据只进不出）
                    ...(authHeader.value.trim()
                      ? { auth_header: authHeader.value.trim() }
                      : {})
                  }
                : kind === "github_issues"
                  ? {
                      repo: repo.value.trim(),
                      // 留空 = 后端保留库里原值（凭据只进不出）
                      ...(authHeader.value.trim()
                        ? { auth_header: authHeader.value.trim() }
                        : {}),
                      include_pull_requests: includePrs.value
                    }
                  : undefined;
        return updateSource(props.kbId, props.source.id, {
          name: name.value.trim(),
          ...(kind === "custom" && icon.value ? { icon: icon.value } : {}),
          ...(config ? { config } : {}),
          ...(syncing ? { ...schedule.value } : {})
        });
      },
      onSuccess: () => props.onDone()
    });

    const valid = computed(() => {
      return (
        name.value.trim() &&
        (kind === "url"
          ? urls.value.trim()
          : kind === "rss"
            ? feedUrl.value.trim()
            : kind === "custom"
              ? endpoint.value.trim()
              : true)
      );
    });

    const input = (model: { value: string }, opts: Record<string, unknown> = {}) => (
      <el-input
        class={["w-full", opts.mono ? "font-mono" : undefined]}
        type={opts.password ? "password" : undefined}
        placeholder={opts.placeholder as string | undefined}
        showPassword={opts.password ? true : undefined}
        modelValue={model.value}
        {...{ "onUpdate:modelValue": (v: string) => (model.value = v) }}
      />
    );

    return () => (
      <UDialog
        open
        {...{ "onUpdate:open": (o: boolean) => !o && props.onDone() }}
        closeLabel={S.library.close as string}
        width="md"
        v-slots={{
          title: () => S.library.editSourceTitle,
          default: () => (
            <div>
              {/* 类型只读：换类型 = 换身份，应新建来源 */}
              <div class="mb-4 flex items-center gap-2 text-sub text-ink-2">
                {iconVNode(KindIcon, 13)}
                {kindLabels[kind] ?? kind}
              </div>

              <Field label={S.library.sourceName as string}>{input(name)}</Field>

              {kind === "custom" && (
                <Field label={S.library.iconLabel as string}>
                  <div class="grid grid-cols-10 gap-1">
                    {iconKeys.map(key => {
                      const I = SOURCE_ICONS[key];
                      return (
                        <button
                          key={key}
                          type="button"
                          title={key}
                          class={[
                            "grid place-items-center rounded-ctl border p-1.5",
                            icon.value === key
                              ? "border-primary bg-primary-soft text-primary"
                              : "border-line bg-card text-ink-2 hover:bg-fill hover:text-ink"
                          ]}
                          onClick={() =>
                            (icon.value = icon.value === key ? null : key)
                          }
                        >
                          {iconVNode(I as Component, 14, "")}
                        </button>
                      );
                    })}
                  </div>
                </Field>
              )}

              {kind === "url" && (
                <Field label={S.library.urlsField as string}>
                  <el-input
                    class="w-full font-mono"
                    type="textarea"
                    autosize={{ minRows: 3 }}
                    modelValue={urls.value}
                    {...{
                      "onUpdate:modelValue": (v: string) => (urls.value = v)
                    }}
                  />
                </Field>
              )}
              {kind === "rss" && (
                <>
                  <Field label={S.library.feedUrl as string}>
                    {input(feedUrl, { mono: true })}
                  </Field>
                  <Field label={S.library.rssContentMode as string}>
                    <el-select
                      class="w-full"
                      modelValue={rssContentMode.value}
                      {...{
                        "onUpdate:modelValue": (v: RssContentMode) =>
                          (rssContentMode.value = v)
                      }}
                    >
                      <el-option
                        value="full_new_items"
                        label={S.library.rssModeFull as string}
                      />
                      <el-option value="feed" label={S.library.rssModeFeed as string} />
                    </el-select>
                  </Field>
                  <p class="-mt-1 mb-3 text-aux leading-relaxed text-ink-2">
                    {rssContentMode.value === "full_new_items"
                      ? S.library.rssFullModeHint
                      : S.library.rssFeedModeHint}
                  </p>
                </>
              )}
              {kind === "github_issues" && (
                <>
                  <Field label={S.library.repoField as string}>
                    {input(repo, { mono: true, placeholder: "owner/name" })}
                  </Field>
                  <Field label={S.library.tokenField as string}>
                    {input(authHeader, {
                      mono: true,
                      password: true,
                      placeholder: "Bearer ghp_…"
                    })}
                  </Field>
                  <el-checkbox
                    class="mb-4"
                    modelValue={includePrs.value}
                    {...{
                      "onUpdate:modelValue": (v: boolean) => (includePrs.value = v)
                    }}
                    label={S.library.includePullRequests as string}
                  />
                </>
              )}
              {kind === "custom" && (
                <>
                  <Field label={S.library.endpointField as string}>
                    {input(endpoint, { mono: true })}
                  </Field>
                  <Field label={S.library.authHeaderEditField as string}>
                    {input(authHeader, {
                      mono: true,
                      password: true,
                      placeholder: S.library.authKeepHint as string
                    })}
                  </Field>
                </>
              )}

              {ingestChanged.value && (
                <p class="mb-3 text-aux leading-relaxed text-ink-2">
                  {S.library.editKeepNote}
                </p>
              )}

              {syncing && (
                <Field label={S.library.interval as string}>
                  <SchedulePicker
                    initial={{
                      sync_interval_minutes: props.source.sync_interval_minutes,
                      sync_cron: props.source.sync_cron
                    }}
                    onChange={(v: ScheduleValue) => (schedule.value = v)}
                  />
                </Field>
              )}

              {save.isError.value && (
                <p class="mb-2 text-sub text-danger">
                  {String((save.error.value as Error)?.message ?? "")}
                </p>
              )}

              {/* Danger zone：删除来源（文档保留，落回 Uploads） */}
              <div class="mt-4 border-t border-line pt-3">
                <div class="mb-1 text-aux font-medium text-ink-2">
                  {S.library.dangerZone}
                </div>
                <div class="flex items-center justify-between gap-3">
                  <span class="text-aux text-ink-2">{S.library.deleteSourceHint}</span>
                  {/* 这里只打开确认框，红色在确认框里（旧仓注释原话） */}
                  <el-button size="small" class="shrink-0" onClick={() => (confirmingDelete.value = true)}>
                    {S.library.deleteSource}
                  </el-button>
                </div>
              </div>

              {/* 危险确认叠在设置弹窗之上：层叠只让最上面那层响应 Esc 与遮罩。
                  DangerConfirm 自带 Teleport，嵌在 default 槽尾即可（v-slots 与
                  JSX children 不能并存——children 会被 v-slots 吞掉）。 */}
              {confirmingDelete.value && (
                <DangerConfirm
                  title={S.library.deleteSourceTitle}
                  hint={S.library.deleteSourceBody(props.source.name)}
                  requireText={props.source.name}
                  confirmLabel={S.library.deleteSource}
                  cancelLabel={S.library.cancel}
                  busy={false}
                  onConfirm={() => props.onDelete()}
                  onCancel={() => (confirmingDelete.value = false)}
                />
              )}
            </div>
          ),
          footer: () => (
            <>
              <el-button size="small" onClick={() => props.onDone()}>
                {S.library.cancel as string}
              </el-button>
              <el-button
                type="primary"
                size="small"
                disabled={!valid.value || save.isPending.value}
                onClick={() => save.mutate()}
              >
                {S.library.saveChanges as string}
              </el-button>
            </>
          )
        }}
      />
    );
  }
});
