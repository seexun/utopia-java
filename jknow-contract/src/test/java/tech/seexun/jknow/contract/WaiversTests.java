package tech.seexun.jknow.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 挂账清单：键规范化、命令行条目、WAIVERS.md 表格装载与合并（Diff/Replayer 的豁免口径必须有单测兜底）。 */
class WaiversTests {

    @Test void 键规范化等价形归一() {
        assertEquals("admin-users/tokens-list", Waivers.normalize("golden/admin-users/tokens-list.json"));
        assertEquals("admin-users/tokens-list", Waivers.normalize("admin-users/tokens-list.json"));
        assertEquals("admin-users/tokens-list", Waivers.normalize("admin-users/tokens-list"));
        assertEquals("admin-users/tokens-list", Waivers.normalize("golden\\admin-users\\tokens-list.json"));
        assertEquals("a/b", Waivers.normalize(" /a/b "));
    }

    @Test void 命令行条目解析路径与原因码() {
        Waivers w = Waivers.fromArgs(List.of(
                "golden/admin-users/tokens-list.json#pat-ratchet",
                "admin-users/users-list",
                "documents/doc-content.json#"));
        assertEquals("pat-ratchet", w.codeFor("admin-users/tokens-list.json"));
        assertEquals("adhoc", w.codeFor("golden/admin-users/users-list.json"));
        assertEquals("adhoc", w.codeFor("documents/doc-content")); // # 后空串按 adhoc
        assertNull(w.codeFor("admin-users/token-issue"));
        assertEquals(3, w.size());
    }

    @Test void 装载WAIVERS_MD表格() throws Exception {
        Path md = Files.createTempFile("waivers", ".md");
        Files.writeString(md, """
                # 挂账清单

                说明文字行不参与解析。

                | 步骤 | 原因码 | 说明 |
                |---|---|---|
                | admin-users/tokens-list | pat-ratchet | PAT 行打戳不删行，逐轮累积 |
                | golden/documents/doc-content.json | d9-upload-no-version-ledger | 参照实例上传路径不写版本台账 |
                """);
        Waivers w = Waivers.load(md);
        assertEquals("pat-ratchet", w.codeFor("admin-users/tokens-list.json"));
        assertEquals("d9-upload-no-version-ledger", w.codeFor("documents/doc-content"));
        assertNull(w.codeFor("other/step"));
        assertEquals(2, w.size());
    }

    @Test void 清单与命令行合并且命令行优先() {
        Waivers fromMd = Waivers.fromArgs(List.of("a/step#file-code"));
        Waivers fromCli = Waivers.fromArgs(List.of("a/step#cli-code", "b/step#cli-only"));
        Waivers merged = fromMd.merge(fromCli);
        assertEquals("cli-code", merged.codeFor("a/step"));
        assertEquals("cli-only", merged.codeFor("b/step"));
        assertEquals(2, merged.size());
    }

    @Test void 清单缺表头报错() throws Exception {
        Path md = Files.createTempFile("waivers", ".md");
        Files.writeString(md, "| a/step | code |\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Waivers.load(md));
        assertEquals(true, e.getMessage().contains("缺少表头"));
    }

    @Test void 已核销章节表不再激活() throws Exception {
        Path md = Files.createTempFile("waivers", ".md");
        Files.writeString(md, """
                # 挂账清单（WAIVERS）

                ## 生效清单

                | 步骤 | 原因码 | 说明 |
                |---|---|---|
                | admin-users/tokens-list | pat-ratchet | 行数棘轮 |

                ## 已核销

                | 步骤 | 原因码 | 核销记录 |
                |---|---|---|
                | admin-users/users-list | env-user-set | 2026-11 任务5 形状专项比对通过 |
                | documents/doc-content | d9-upload-no-version-ledger | 参照实例修复后重录核销 |
                """);
        Waivers w = Waivers.load(md);
        assertEquals("pat-ratchet", w.codeFor("admin-users/tokens-list.json"));
        // 已核销二级表的行（含表头行本身）不得再被登记为生效挂账
        assertNull(w.codeFor("admin-users/users-list"));
        assertNull(w.codeFor("documents/doc-content"));
        assertNull(w.codeFor("步骤"));
        assertEquals(1, w.size());
    }

    @Test void 无标题分隔的第二张同构表头同样截断() throws Exception {
        Path md = Files.createTempFile("waivers", ".md");
        Files.writeString(md, """
                # 挂账清单

                | 步骤 | 原因码 | 说明 |
                |---|---|---|
                | a/step | code1 | 生效 |

                | 步骤 | 原因码 | 核销记录 |
                |---|---|---|
                | b/step | code2 | 已核销历史行 |
                """);
        Waivers w = Waivers.load(md);
        assertEquals("code1", w.codeFor("a/step"));
        assertNull(w.codeFor("b/step"));
        assertEquals(1, w.size());
    }
}
