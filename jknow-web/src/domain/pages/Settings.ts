/* 【任务3抽离】自旧仓 web/src/pages/Settings.tsx 取纯逻辑：模型卡状态优先级表
   （modelCardStatus，Settings.test 随迁）与引擎连接串拼装（dsSpecs，dsSpecs 两个
   用例文件随迁）；设置页组件面留在旧仓（Ⅲ类-重写）。除本头注与 import 外逐字原样。 */
import { S } from "../i18n";


/** 两张模型卡的备注只讲一件事，且只讲最新的那件（#698）。
 *
 * 从前备注是"有测试结果就说测试，否则说保存"——测完再改键，旧结果还贴在卡上；
 * 测一次是两套一起测，两个按钮却一起转"Testing…"；嵌入卡的报错还是正文字色，
 * 聊天卡的报错却是危险色。于是"Saved" 说的是哪次操作、这次测的又是哪张卡，
 * 全得靠猜。
 *
 * 三条规矩，显示层只管最后一条，前两条在调用处用 reset 保证：
 * 1. 改任何一格、按任何一次保存，旧结论（测试结果、Saved、报错）全部过期；
 * 2. "Testing…" 只亮在按下的那张卡上（`testCard`），因为测的虽然是两套，
 *    人按的是这一张；
 * 3. 新测到的结果（含测挂了）盖掉旧保存态——刚按了"测试"，卡上就该说测试的事。
 *
 * 传纯数据、回纯结论（含用哪个色调），字串仍在调用处配 i18n；
 * 纯函数方便 vitest 直接钉住这张优先级表。 */
export type ModelCardTest = { ok: boolean; message: string } | null;
/** `dirty`：这张卡有改过、还没保存的格子 */
export type ModelCardSave = { error: string | null; saved: boolean; dirty: boolean };
export type ModelCardStatus =
  | { kind: "note"; tone: "text-success" | "text-danger"; text: string }
  | { kind: "unsaved" }
  | { kind: "saved" }
  | { kind: "idle" };

export function modelCardStatus(
  test: ModelCardTest,
  testError: string | null,
  save: ModelCardSave,
): ModelCardStatus {
  /* 测试测的永远是**已保存**的那份配置。卡上有没存的修改时，任何测试结果说的都不是
     表单里这一份——从前改了密钥直接点测试，看到的是旧密钥的「已连通」，或者格子都填着
     却说 Not configured（#698 的第 2、3 条）。保存失败的报错仍然先说 */
  if (save.error)
    return { kind: "note", tone: "text-danger", text: save.error };
  if (save.dirty) return { kind: "unsaved" };
  if (test)
    return test.ok
      ? { kind: "note", tone: "text-success", text: test.message }
      : { kind: "note", tone: "text-danger", text: test.message };
  if (testError) return { kind: "note", tone: "text-danger", text: testError };
  if (save.saved) return { kind: "saved" };
  return { kind: "idle" };
}

/** 系统层数据源注册（问数）：凭据只进不出，列表只显示 host:port/db 摘要。 */
/* ---- 每种引擎问什么，以及那些答案怎么拼成一条连接串 ----

   **人填的是字段，存的仍然是连接串。** 服务端一直按 scheme 认引擎（见
   `query_engine::engine_from_conn`），所以这里只是把「写一条 URL」这件事
   换成「填几格」——密码里的 @ 和 / 也不用人自己转义了。

   最后一档是原样的连接串：sslmode、附加参数这类写法拼不出来，得给行家留门。 */
type DsField = {
  key: string;
  label: string;
  placeholder?: string;
  optional?: boolean;
  secret?: boolean;
};
type EngineSpec = {
  id: string;
  /** 引擎名是专有名词，不翻译 */
  label: string;
  fields: DsField[];
  /** 从填好的几格拼出连接串。
   *
   * **拿到的是一张残缺的表**：`values` 里只有人真填过的那几格，刚换到一档
   * 引擎时它可能是空的。所以每个 build 都必须容忍缺键——
   * 类型写成 `Partial` 而不是 `Record<string, string>`，是因为后者是句假话：
   * 索引签名声称取哪个键都是 string，于是 `v.conn.trim()` 编译得过、
   * 一选中「连接串」就抛，整页换成错误屏（#573）。
   * 其余几档当时没炸，只因为模板串把 `undefined` 安静地拼成了 "undefined"。 */
  build: (v: Partial<Record<string, string>>) => string;
};

/** 这两个助手**都收得下缺席的值**，因为表单本来就是一格一格填起来的：
 *  刚换到一档引擎时一格都没有。它们的函数体早就按这个写了（`enc` 缺值当空串、
 *  `auth` 用真值判断），只是签名一直写成 `string`，没把这件事说出来。
 *  #573 就是从这个缝里掉下去的。 */
const enc = (v?: string) => encodeURIComponent(v ?? "");
/** `user:pass@` 那一段：两处都可能为空（Trino 允许无密码） */
const auth = (user?: string, pass?: string) =>
  pass ? `${enc(user)}:${enc(pass)}@` : user ? `${enc(user)}@` : "";

export function dsSpecs(): EngineSpec[] {
  const D = S.settings.datasources;
  return [
    {
      id: "postgres",
      label: "Postgres",
      fields: [
        { key: "host", label: D.fHost, placeholder: "db.internal" },
        { key: "port", label: D.fPort, placeholder: "5432" },
        { key: "database", label: D.fDatabase, placeholder: "analytics" },
        { key: "user", label: D.fUser },
        { key: "password", label: D.fPassword, secret: true },
      ],
      build: (v) =>
        `postgres://${auth(v.user, v.password)}${v.host}:${v.port || "5432"}/${enc(v.database)}`,
    },
    {
      id: "mysql",
      label: "MySQL",
      fields: [
        { key: "host", label: D.fHost, placeholder: "db.internal" },
        { key: "port", label: D.fPort, placeholder: "3306" },
        { key: "database", label: D.fDatabase },
        { key: "user", label: D.fUser },
        { key: "password", label: D.fPassword, secret: true },
      ],
      build: (v) =>
        `mysql://${auth(v.user, v.password)}${v.host}:${v.port || "3306"}/${enc(v.database)}`,
    },
    {
      id: "clickhouse",
      label: "ClickHouse",
      // 用户与库都可以不填：不写用户就是服务器的 default，不写库就读所有非系统库。
      // 端口 8443 / 443 服务端自己走 https，表单里不用再问一次
      fields: [
        { key: "host", label: D.fHost, placeholder: "ch.internal" },
        { key: "port", label: D.fPort, placeholder: "8123" },
        { key: "database", label: D.fDatabase, optional: true },
        { key: "user", label: D.fUser, optional: true, placeholder: "default" },
        { key: "password", label: D.fPassword, optional: true, secret: true },
      ],
      // 只填了密码就是 default 用户的密码：没有用户名，服务端不会把密码发出去
      build: (v) =>
        `clickhouse://${auth(v.user || (v.password ? "default" : ""), v.password)}` +
        `${v.host}:${v.port || "8123"}` +
        (v.database ? `/${enc(v.database)}` : ""),
    },
    {
      id: "trino",
      label: "Trino",
      fields: [
        { key: "host", label: D.fHost },
        { key: "port", label: D.fPort, placeholder: "8080" },
        { key: "catalog", label: D.fCatalog, placeholder: "iceberg" },
        { key: "schema", label: D.fSchema, optional: true },
        { key: "user", label: D.fUser },
        { key: "password", label: D.fPassword, optional: true, secret: true },
      ],
      build: (v) =>
        `trino://${auth(v.user, v.password)}${v.host}:${v.port || "8080"}/${enc(v.catalog)}` +
        (v.schema ? `/${enc(v.schema)}` : ""),
    },
    {
      id: "databricks",
      label: "Databricks",
      fields: [
        { key: "host", label: D.fHost, placeholder: "dbc-1234.cloud.databricks.com" },
        { key: "token", label: D.fToken, secret: true },
        { key: "warehouse", label: D.fWarehouseId, placeholder: "abc123def456" },
        { key: "catalog", label: D.fCatalog, optional: true, placeholder: "main" },
      ],
      build: (v) =>
        `databricks://:${enc(v.token)}@${v.host}/sql/1.0/warehouses/${v.warehouse}` +
        (v.catalog ? `?catalog=${enc(v.catalog)}` : ""),
    },
    {
      id: "snowflake",
      label: "Snowflake",
      fields: [
        {
          key: "account",
          label: D.fAccount,
          placeholder: "acme-xy12345.snowflakecomputing.com",
        },
        { key: "token", label: D.fToken, secret: true },
        { key: "database", label: D.fDatabase },
        { key: "schema", label: D.fSchema },
        { key: "warehouse", label: D.fWarehouse },
      ],
      build: (v) =>
        `snowflake://:${enc(v.token)}@${v.account}/${enc(v.database)}/${enc(v.schema)}` +
        `?warehouse=${enc(v.warehouse)}`,
    },
    {
      id: "raw",
      label: D.engineRaw,
      fields: [{ key: "conn", label: D.connString }],
      build: (v) => (v.conn ?? "").trim(),
    },
  ];
}
