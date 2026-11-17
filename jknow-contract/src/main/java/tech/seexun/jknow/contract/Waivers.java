package tech.seexun.jknow.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 挂账清单（waiver）：显式豁免的步骤——在 Diff/Replayer 中<b>跳过比对且单列报告</b>
 * （不算差异也不算通过），把「已知不比」从沉默变成账面。
 *
 * <p>两个来源，可叠加：
 * <ul>
 * <li>声明式清单 {@code scenarios/WAIVERS.md}——Markdown 表格（{@code --waivers <file>} 装载）：
 * <pre>
 * | 步骤 | 原因码 | 说明 |
 * |---|---|---|
 * | admin-users/tokens-list | pat-ratchet | PAT 行打戳不删行，逐轮累积 |
 * </pre>
 * 装载要求表头含「步骤」「原因码」两列（第 1/2 列），数据行按列取值；
 * <b>只装载首个此类表（生效清单）</b>——遇后续章节标题（如「## 已核销」）或第二张
 * 同构表头即截断，二级表里的历史行不再激活（核销即失效）。</li>
 * <li>命令行 {@code --waive &lt;剧本/&lt;步骤&gt;.json#&lt;原因码&gt;}——可重复，临时挂账
 * （格式同 Diff 报告里的相对路径 + {@code #} + 原因码；{@code #} 缺省原因码 adhoc）。</li>
 * </ul>
 * 键规范化：去掉可选的 {@code golden/} 前缀与 {@code .json} 后缀——
 * {@code golden/admin-users/tokens-list.json}、{@code admin-users/tokens-list.json}、
 * {@code admin-users/tokens-list} 是同一个键。
 */
public final class Waivers {

    private final Map<String, String> codes = new LinkedHashMap<>();

    /** 命令行 --waive 条目（可重复）：{@code 路径#原因码}。 */
    public static Waivers fromArgs(List<String> waiveArgs) {
        Waivers w = new Waivers();
        for (String arg : waiveArgs) {
            int hash = arg.lastIndexOf('#');
            String path = hash >= 0 ? arg.substring(0, hash) : arg;
            String code = hash >= 0 ? arg.substring(hash + 1) : "adhoc";
            if (code.isBlank()) {
                code = "adhoc";
            }
            w.put(path, code);
        }
        return w;
    }

    /**
     * 装载 WAIVERS.md 声明式清单（表头两列：步骤 / 原因码；第三列说明仅文档用途）。
     * 只读首个「步骤/原因码」表（生效清单）：遇章节标题（如「## 已核销」）或第二张同构表头
     * 即截断——核销流程把行移入二级表后必须随之失效，不得再被登记为生效挂账。
     */
    public static Waivers load(Path md) throws IOException {
        Waivers w = new Waivers();
        boolean headerSeen = false;
        for (String line : Files.readAllLines(md, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (headerSeen && trimmed.startsWith("#")) {
                // 截断（章节）：生效表之后的任何标题（「## 已核销」等）即装载终点
                break;
            }
            if (!trimmed.startsWith("|")) {
                continue;
            }
            String[] cells = splitRow(trimmed);
            boolean headerRow = cells.length >= 2
                    && cells[0].contains("步骤") && cells[1].contains("原因码");
            if (headerRow) {
                if (!headerSeen) {
                    headerSeen = true; // 首个表头：生效清单开始
                } else {
                    break; // 截断（二级表头）：无标题分隔的第二张同构表头同样终止
                }
                continue;
            }
            if (headerSeen && cells.length >= 2 && !cells[0].isBlank()
                    && !cells[0].startsWith("---") && !cells[1].startsWith("---")) {
                w.put(cells[0], cells[1]);
            }
        }
        if (!headerSeen) {
            throw new IllegalArgumentException("挂账清单缺少表头（需要 | 步骤 | 原因码 | 两列）: " + md);
        }
        return w;
    }

    /** 合并（后者覆盖同键）：清单文件 + 命令行临时条目。 */
    public Waivers merge(Waivers other) {
        Waivers merged = new Waivers();
        merged.codes.putAll(this.codes);
        merged.codes.putAll(other.codes);
        return merged;
    }

    public boolean isEmpty() {
        return codes.isEmpty();
    }

    public int size() {
        return codes.size();
    }

    /** 挂账键（任意形）→ 规范键登记。 */
    private void put(String rawKey, String code) {
        codes.put(normalize(rawKey), code);
    }

    /** 相对路径（golden 目录内，如 {@code admin-users/tokens-list.json}）是否挂账；命中返回原因码。 */
    public String codeFor(String relPath) {
        return codes.get(normalize(relPath));
    }

    /** 全部条目（规范键 → 原因码），报告单列用。 */
    public Map<String, String> entries() {
        return codes;
    }

    /** 键规范化：去 golden/ 前缀、去 .json 后缀、反斜杠转正斜杠。 */
    static String normalize(String key) {
        String k = key.trim().replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        if (k.startsWith("golden/")) {
            k = k.substring("golden/".length());
        }
        if (k.endsWith(".json")) {
            k = k.substring(0, k.length() - ".json".length());
        }
        return k;
    }

    /** 拆 Markdown 表格行（首尾竖线去掉，按 | 切，单元格 trim）。 */
    private static String[] splitRow(String row) {
        String body = row.startsWith("|") ? row.substring(1) : row;
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] parts = body.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
