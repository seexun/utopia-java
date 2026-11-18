/* 新建来源弹窗（W3 走查收口补迁，对照旧仓 pages/Library.tsx 的 SourceModal：
   类型 → 名称/图标 → 类型专属配置 → 同步周期）。十三种类型一个下拉装下
   （旧仓注释原话：排成按钮要占四行）；内置类型图标固定，只有 custom 允许自选；
   folder/api 无同步语义，日程强制留空。经 UDialog 直绘（字段面大、脚注双钮，
   与 UFormDialog 的「正文=单表单」形状不合）。 */
import { computed, defineComponent, h, ref, type Component, type PropType } from "vue";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import { createSource } from "@/api/knowledge/sources";
import { UDialog } from "@/components/knowledge/dialog";
import { SOURCE_ICONS } from "@/components/knowledge/sources-rail";
import { SchedulePicker, type ScheduleValue } from "./schedule-picker";
import { S } from "@/i18n";
import { RouterLink } from "vue-router";
import {
  CREATABLE_SOURCE_KINDS,
  type CreatableSourceKind
} from "@/utils/sourceKinds";
import { KIND_ICON } from "@/components/knowledge/sources-rail";

type RssContentMode = "full_new_items" | "feed";

/** 注意用 div 而非 label：label 会把 :hover/click 转发给内部第一个可标记控件
    （旧仓注释原话，图标网格/日程选择器悬停常亮就是这么来的） */
const Field = defineComponent({
  name: "USourceField",
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

/** tsx 里动态组件统一走 h()（railIcon 同款——Component 类型没有裸 JSX 签名） */
const kindIcon = (k: string): Component => KIND_ICON[k] || KIND_ICON.folder;
const iconVNode = (Ic: Component, size = 12, cls = "shrink-0 text-ink-2") =>
  h(Ic as never, { width: size, height: size, class: cls });

export const SourceModal = defineComponent({
  name: "USourceModal",
  props: {
    kbId: { type: String, required: true },
    /** isApi=true 时父级紧接着打开密钥弹窗；第二参数：推送来源的 kind
        （`api`/`statements`），别的不给（旧仓同口径） */
    onDone: {
      type: Function as PropType<(id?: string, pushKind?: string) => void>,
      required: true
    }
  },
  setup(props) {
    const queryClient = useQueryClient();
    const kind = ref<CreatableSourceKind>("folder");
    const name = ref("");
    const icon = ref<string | null>(null);
    const urls = ref("");
    const feedUrl = ref("");
    const rssContentMode = ref<RssContentMode>("feed");
    const endpoint = ref("");
    const authHeader = ref("");
    const repo = ref("");
    const jiraUrl = ref("");
    const jiraProject = ref("");
    const s3Bucket = ref("");
    const s3Prefix = ref("");
    const s3Endpoint = ref("");
    const s3Region = ref("");
    const s3Key = ref("");
    const s3Secret = ref("");
    const azAccount = ref("");
    const azKey = ref("");
    const gcsKey = ref("");
    const davUrl = ref("");
    const davPath = ref("");
    const davUser = ref("");
    const davPass = ref("");
    const notionToken = ref("");
    const notionQuery = ref("");
    // PR 在 GitHub 的模型里也是工单。默认不收——问「工单」要的是工单；
    // 但有些仓库的决策记录实际写在 PR 描述里，所以给个开关（旧仓注释原话）
    const includePrs = ref(false);
    const schedule = ref<ScheduleValue>({
      sync_interval_minutes: null,
      sync_cron: null
    });
    // folder = 纯容器、api = 推送型：都没有同步日程
    const syncing = computed(() =>
      [
        "url",
        "rss",
        "custom",
        "github_issues",
        "jira_issues",
        "s3",
        "azure_blob",
        "gcs",
        "webdav",
        "notion"
      ].includes(kind.value)
    );

    const create = useMutation({
      mutationFn: () => {
        const k = kind.value;
        const config =
          k === "url"
            ? { urls: urls.value.split("\n").map(u => u.trim()).filter(Boolean) }
            : k === "rss"
              ? { feed_url: feedUrl.value.trim(), content_mode: rssContentMode.value }
              : k === "custom"
                ? {
                    endpoint: endpoint.value.trim(),
                    ...(authHeader.value.trim()
                      ? { auth_header: authHeader.value.trim() }
                      : {})
                  }
                : k === "github_issues"
                  ? {
                      repo: repo.value.trim(),
                      ...(authHeader.value.trim()
                        ? { auth_header: authHeader.value.trim() }
                        : {}),
                      ...(includePrs.value ? { include_pull_requests: true } : {})
                    }
                  : k === "jira_issues"
                    ? {
                        base_url: jiraUrl.value.trim(),
                        project: jiraProject.value.trim(),
                        ...(authHeader.value.trim()
                          ? { auth_header: authHeader.value.trim() }
                          : {})
                      }
                    : k === "s3"
                      ? {
                          bucket: s3Bucket.value.trim(),
                          ...(s3Prefix.value.trim()
                            ? { prefix: s3Prefix.value.trim() }
                            : {}),
                          // 端点留空 = 公有云 S3；填了就是自建，后端据此走 path-style
                          ...(s3Endpoint.value.trim()
                            ? { endpoint: s3Endpoint.value.trim() }
                            : {}),
                          ...(s3Region.value.trim()
                            ? { region: s3Region.value.trim() }
                            : {}),
                          ...(s3Key.value.trim()
                            ? { access_key_id: s3Key.value.trim() }
                            : {}),
                          ...(s3Secret.value.trim()
                            ? { secret_access_key: s3Secret.value.trim() }
                            : {})
                        }
                      : k === "azure_blob"
                        ? {
                            bucket: s3Bucket.value.trim(),
                            ...(s3Prefix.value.trim()
                              ? { prefix: s3Prefix.value.trim() }
                              : {}),
                            ...(s3Endpoint.value.trim()
                              ? { endpoint: s3Endpoint.value.trim() }
                              : {}),
                            ...(azAccount.value.trim()
                              ? { account_name: azAccount.value.trim() }
                              : {}),
                            ...(azKey.value.trim()
                              ? { account_key: azKey.value.trim() }
                              : {})
                          }
                        : k === "gcs"
                          ? {
                              bucket: s3Bucket.value.trim(),
                              ...(s3Prefix.value.trim()
                                ? { prefix: s3Prefix.value.trim() }
                                : {}),
                              ...(s3Endpoint.value.trim()
                                ? { endpoint: s3Endpoint.value.trim() }
                                : {}),
                              ...(gcsKey.value.trim()
                                ? { service_account_key: gcsKey.value.trim() }
                                : {})
                            }
                          : k === "webdav"
                            ? {
                                base_url: davUrl.value.trim(),
                                ...(davPath.value.trim()
                                  ? { path: davPath.value.trim() }
                                  : {}),
                                ...(davUser.value.trim()
                                  ? { username: davUser.value.trim() }
                                  : {}),
                                ...(davPass.value ? { password: davPass.value } : {})
                              }
                            : k === "notion"
                              ? {
                                  token: notionToken.value.trim(),
                                  ...(notionQuery.value.trim()
                                    ? { query: notionQuery.value.trim() }
                                    : {})
                                }
                              : {};
        return createSource(props.kbId, {
          kind: k,
          name: name.value.trim(),
          config,
          // 内置类型图标固定，只有 custom 允许自选
          icon: k === "custom" ? icon.value : null,
          // folder/api 无同步语义：日程强制留空
          ...(syncing.value
            ? schedule.value
            : { sync_interval_minutes: null, sync_cron: null })
        });
      },
      onSuccess: (data: { source: { id: string } }) =>
        props.onDone(
          data.source.id,
          kind.value === "api" || kind.value === "statements" ? kind.value : undefined
        )
    });

    const valid = computed(() => {
      const k = kind.value;
      return (
        name.value.trim() &&
        (k === "url"
          ? urls.value.trim()
          : k === "rss"
            ? feedUrl.value.trim()
            : k === "custom"
              ? endpoint.value.trim()
              : k === "notion"
                ? notionToken.value.trim()
                : k === "webdav"
                  ? davUrl.value.trim()
                  : k === "s3" || k === "azure_blob" || k === "gcs"
                    ? s3Bucket.value.trim()
                    : true)
      );
    });

    const hints = S.library.sourceKindHints as Record<string, string>;
    const kindLabels = S.library.sourceKinds as Record<string, string>;
    const iconKeys = Object.keys(SOURCE_ICONS);

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

    return () => {
      const k = kind.value;
      const Icon = kindIcon(k);
      return (
        <UDialog
          open
          {...{ "onUpdate:open": (o: boolean) => !o && props.onDone() }}
          closeLabel={(S.library.close as string)}
          width="md"
          v-slots={{
            title: () => S.library.newSourceTitle,
            default: () => (
              <div>
                {/* 类型：十三种，一个下拉装下——排成按钮要占四行，还没等填名字弹窗
                    已经满了。图标只在选项的标签里：触发器显示的就是选中那项的标签，
                    再给触发器一个 icon 就画两遍（旧仓注释原话） */}
                <Field label={(S.library.sourceType as string)}>
                  <el-select
                    class="w-full"
                    modelValue={k}
                    {...{
                      "onUpdate:modelValue": (v: CreatableSourceKind) =>
                        (kind.value = v)
                    }}
                  >
                    {CREATABLE_SOURCE_KINDS.map(kk => {
                      const KIcon = kindIcon(kk);
                      return ( 
                        <el-option key={kk} value={kk} label={kindLabels[kk]}>
                          <span class="flex items-center gap-2">
                            {iconVNode(KIcon)}
                            {kindLabels[kk]}
                          </span>
                        </el-option>
                      );
                    })}
                  </el-select>
                </Field>
                {/* 类型自解释：一行说明；接口细节移入内置文档，弹窗只留链接 */}
                <p class="mb-4 text-aux leading-relaxed text-ink-2">
                  {hints[k]}
                  {k === "custom" && (
                    <RouterLink
                      to="/docs/ingest"
                      target="_blank"
                      class="link-inline ml-1"
                    >
                      {(S.library.ingestGuide as string)}
                    </RouterLink>
                  )}
                </p>

                <Field label={(S.library.sourceName as string)}>
                  {input(name)}
                </Field>

                {/* 内置类型图标固定；只有 custom 开放图标选择 */}
                {k === "custom" && (
                  <Field label={(S.library.iconLabel as string)}>
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

                {k === "custom" && (
                  <>
                    <Field label={(S.library.endpointField as string)}>
                      {input(endpoint, {
                        mono: true,
                        placeholder: "https://your-service/utopia-feed"
                      })}
                    </Field>
                    <Field label={(S.library.authHeaderField as string)}>
                      {input(authHeader, {
                        mono: true,
                        password: true,
                        placeholder: "Bearer sk-…"
                      })}
                    </Field>
                  </>
                )}

                {k === "url" && (
                  <Field label={(S.library.urlsField as string)}>
                    <el-input
                      class="w-full font-mono"
                      type="textarea"
                      autosize={{ minRows: 3 }}
                      modelValue={urls.value}
                      {...{ "onUpdate:modelValue": (v: string) => (urls.value = v) }}
                    />
                  </Field>
                )}
                {k === "rss" && (
                  <>
                    <Field label={(S.library.feedUrl as string)}>
                      {input(feedUrl, { mono: true })}
                    </Field>
                    <Field label={(S.library.rssContentMode as string)}>
                      <el-select
                        class="w-full"
                        modelValue={rssContentMode.value}
                        {...{
                          "onUpdate:modelValue": (v: RssContentMode) =>
                            (rssContentMode.value = v)
                        }}
                      >
                        <el-option value="full_new_items" label={(S.library.rssModeFull as string)} />
                        <el-option value="feed" label={(S.library.rssModeFeed as string)} />
                      </el-select>
                    </Field>
                    <p class="-mt-1 mb-3 text-aux leading-relaxed text-ink-2">
                      {rssContentMode.value === "full_new_items"
                        ? S.library.rssFullModeHint
                        : S.library.rssFeedModeHint}
                    </p>
                  </>
                )}
                {k === "jira_issues" && (
                  <>
                    <Field label={(S.library.jiraUrlField as string)}>
                      {input(jiraUrl, {
                        mono: true,
                        placeholder: "https://jira.example.com"
                      })}
                    </Field>
                    <Field label={(S.library.jiraProjectField as string)}>
                      {input(jiraProject, { mono: true, placeholder: "KAFKA" })}
                    </Field>
                    <Field label={(S.library.tokenField as string)}>
                      {input(authHeader, {
                        mono: true,
                        password: true,
                        placeholder: "Basic dXNlcjp0b2tlbg=="
                      })}
                    </Field>
                  </>
                )}
                {(k === "s3" || k === "azure_blob" || k === "gcs") && (
                  <>
                    <Field label={(S.library.s3BucketField as string)}>
                      {input(s3Bucket, { mono: true, placeholder: "documents" })}
                    </Field>
                    <Field label={(S.library.s3PrefixField as string)}>
                      {input(s3Prefix, { mono: true, placeholder: "reports/2026/" })}
                    </Field>
                    <Field label={(S.library.s3EndpointField as string)}>
                      {input(s3Endpoint, {
                        mono: true,
                        placeholder: "http://minio.internal:9000"
                      })}
                    </Field>
                    <Field label={(S.library.s3RegionField as string)}>
                      {input(s3Region, { mono: true, placeholder: "us-east-1" })}
                    </Field>
                    {k === "s3" && (
                      <>
                        <Field label={(S.library.s3KeyField as string)}>
                          {input(s3Key, { mono: true })}
                        </Field>
                        <Field label={(S.library.s3SecretField as string)}>
                          {input(s3Secret, { mono: true, password: true })}
                        </Field>
                      </>
                    )}
                    {k === "azure_blob" && (
                      <>
                        <Field label={(S.library.azAccountField as string)}>
                          {input(azAccount, { mono: true })}
                        </Field>
                        <Field label={(S.library.azKeyField as string)}>
                          {input(azKey, { mono: true, password: true })}
                        </Field>
                      </>
                    )}
                    {k === "gcs" && (
                      <Field label={(S.library.gcsKeyField as string)}>
                        <el-input
                          class="w-full font-mono"
                          type="textarea"
                          autosize={{ minRows: 3 }}
                          placeholder='{"type":"service_account",...}'
                          modelValue={gcsKey.value}
                          {...{
                            "onUpdate:modelValue": (v: string) => (gcsKey.value = v)
                          }}
                        />
                      </Field>
                    )}
                  </>
                )}
                {k === "webdav" && (
                  <>
                    <Field label={(S.library.davUrlField as string)}>
                      {input(davUrl, {
                        mono: true,
                        placeholder:
                          "https://cloud.example.com/remote.php/dav/files/alice"
                      })}
                    </Field>
                    <Field label={(S.library.davPathField as string)}>
                      {input(davPath, { mono: true, placeholder: "/Documents" })}
                    </Field>
                    <Field label={(S.library.davUserField as string)}>
                      {input(davUser, { mono: true })}
                    </Field>
                    <Field label={(S.library.davPassField as string)}>
                      {input(davPass, { mono: true, password: true })}
                    </Field>
                  </>
                )}
                {k === "notion" && (
                  <>
                    <Field label={(S.library.notionTokenField as string)}>
                      {input(notionToken, {
                        mono: true,
                        password: true,
                        placeholder: "ntn_..."
                      })}
                    </Field>
                    <Field label={(S.library.notionQueryField as string)}>
                      {input(notionQuery, { mono: true })}
                    </Field>
                  </>
                )}
                {k === "github_issues" && (
                  <>
                    <Field label={(S.library.repoField as string)}>
                      {input(repo, { mono: true, placeholder: "owner/name" })}
                    </Field>
                    <Field label={(S.library.tokenField as string)}>
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
                        "onUpdate:modelValue": (v: boolean) =>
                          (includePrs.value = v)
                      }}
                      label={(S.library.includePullRequests as string)}
                    />
                  </>
                )}

                {syncing.value && (
                  <Field label={(S.library.interval as string)}>
                    <SchedulePicker
                      onChange={(v: ScheduleValue) => (schedule.value = v)}
                    />
                  </Field>
                )}

                {create.isError.value && (
                  <p class="mb-2 text-sub text-danger">
                    {String((create.error.value as Error)?.message ?? "")}
                  </p>
                )}
              </div>
            ),
            footer: () => (
              <>
                <el-button size="small" onClick={() => props.onDone()}>
                  {(S.library.cancel as string)}
                </el-button>
                <el-button
                  type="primary"
                  size="small"
                  disabled={!valid.value || create.isPending.value}
                  onClick={() => create.mutate()}
                >
                  {(S.library.createSource as string)}
                </el-button>
              </>
            )
          }}
        />
      );
    };
  }
});

/* 来源令牌弹窗（对照旧仓 TokenModal，W3 补迁）：推送来源建成的下一拍就要用——
   查询现令牌点开即显；没有就「生成」，轮换立即换显（旧仓 onSuccess 同款
   setQueryData）。端点与用法块照旧仓逐值：点击复制走 domain/clipboard。 */
import KeyRoundIcon from "~icons/lucide/key-round";
import RefreshCwIcon from "~icons/lucide/refresh-cw";
import { useQuery } from "@tanstack/vue-query";
import { sourceToken, rotateSourceToken } from "@/api/knowledge/sources";
import { pushEndpoint } from "@/domain/pushEndpoint";
import { copyAndSay } from "@/domain/clipboard";
import { toast } from "@/domain/toast";

export const SourceTokenModal = defineComponent({
  name: "USourceTokenModal",
  props: {
    kbId: { type: String, required: true },
    sourceId: { type: String, required: true },
    kind: { type: String, required: true },
    onClose: { type: Function as PropType<() => void>, required: true }
  },
  setup(props) {
    const queryClient = useQueryClient();
    const tokenQuery = useQuery({
      queryKey: ["sourceToken", props.kbId, props.sourceId],
      queryFn: () => sourceToken(props.kbId, props.sourceId)
    });
    const rotate = useMutation({
      mutationFn: () => rotateSourceToken(props.kbId, props.sourceId),
      onSuccess: (r: { ingest_token: string }) => {
        queryClient.setQueryData(["sourceToken", props.kbId, props.sourceId], {
          ingest_token: r.ingest_token
        });
        toast.success(S.toast.saved);
      },
      onError: (e: Error) => toast.error(e.message)
    });
    const token = computed(() => tokenQuery.data.value?.ingest_token ?? null);
    const endpoint = pushEndpoint(window.location.origin, props.sourceId, props.kind);

    const blockBtn =
      "h-auto w-full justify-start whitespace-normal break-all rounded-ctl border border-line bg-card px-3 py-3 text-left font-mono text-body text-ink hover:bg-fill";

    return () => (
      <UDialog
        open
        {...{ "onUpdate:open": (o: boolean) => !o && props.onClose() }}
        closeLabel={(S.library.close as string)}
        width="md"
        v-slots={{
          title: () => (
            <span class="flex items-center gap-2">
              <KeyRoundIcon width="14" height="14" class="text-ink-2" />
              {S.library.tokenTitle}
            </span>
          ),
          default: () => (
            <div class="space-y-3">
              {tokenQuery.isPending.value ? (
                <p class="text-body text-ink-2">{S.nav.loading}</p>
              ) : token.value ? (
                <>
                  <button
                    type="button"
                    class={blockBtn}
                    title={(S.library.copyEndpoint as string)}
                    onClick={() => copyAndSay(token.value!, (S.library.tokenCopied as string))}
                  >
                    {token.value}
                  </button>
                  <p class="text-aux leading-relaxed text-ink-2">
                    {S.library.tokenWarning}
                  </p>
                  <div>
                    <p class="mb-1 text-aux text-ink-2">{S.library.tokenUsage}</p>
                    <button
                      type="button"
                      class={[blockBtn, "whitespace-pre-wrap py-2 text-ink-2"]}
                      title={(S.library.copyEndpoint as string)}
                      onClick={() => copyAndSay(endpoint, (S.library.endpointCopied as string))}
                    >
                      {"POST " + endpoint + "\nAuthorization: Bearer <token>"}
                    </button>
                  </div>
                </>
              ) : (
                <p class="text-body text-ink-2">{S.library.noToken}</p>
              )}
            </div>
          ),
          footer: () => (
            <>
              <el-button
                size="small"
                class="flex items-center gap-2"
                disabled={rotate.isPending.value || tokenQuery.isPending.value}
                onClick={() => rotate.mutate()}
              >
                <RefreshCwIcon
                  width="11"
                  height="11"
                  class={rotate.isPending.value ? "animate-spin" : ""}
                />
                {token.value ? S.library.rotateToken : S.library.generateToken}
              </el-button>
              <el-button type="primary" size="small" onClick={() => props.onClose()}>
                {(S.library.close as string)}
              </el-button>
            </>
          )
        }}
      />
    );
  }
});
