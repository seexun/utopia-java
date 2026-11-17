package tech.seexun.jknow.ingest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 对齐 fixture 生成器（默认禁用；`mvn test -Dalign.gen=1` 重生成 src/test/resources/alignment/inputs
 * 下的 csv/docx/html 三件）。三个中文 md 是共享库真实文档（blob 按 sha256 取）。生成后用
 * Rust harness（parse+chunk_with_budget(300)）双打，结果存 alignment/rust/*.json 供
 * {@link ChunkAlignmentTests} 对照。
 */
@EnabledIfSystemProperty(named = "align.gen", matches = ".+")
class AlignmentFixtureGenTests {

    private static final Path OUT =
            Path.of("src", "test", "resources", "alignment", "inputs");

    @Test
    void generateFixtures() throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve("align-financials.csv"), csv().getBytes(StandardCharsets.UTF_8));
        Files.write(OUT.resolve("align-report.docx"), docx());
        Files.write(OUT.resolve("align-article.html"), html().getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.exists(OUT.resolve("align-financials.csv")));
    }

    /** 中文财务 csv：45 行记录，表超 300 token → 必然按行切、续块带表头。 */
    private static String csv() {
        StringBuilder sb = new StringBuilder("科目,2025年上半年,2024年上半年,同比变动\n");
        String[] items = {"营业收入", "营业成本", "销售费用", "管理费用", "研发投入", "财务费用",
            "投资收益", "营业利润", "利润总额", "净利润", "扣非净利润", "经营现金流", "投资现金流",
            "筹资现金流", "存货余额", "应收账款", "应付账款", "合同负债", "在建工程", "固定资产"};
        for (int i = 0; i < items.length * 2 + 5; i++) {
            String item = items[i % items.length];
            long h1 = 12000 + i * 137L;
            long h2 = 9000 + i * 121L;
            sb.append(item).append(',').append(h1).append(',').append(h2).append(',')
                    .append(String.format("%.1f%%", (h1 - h2) * 100.0 / h2)).append('\n');
        }
        return sb.toString();
    }

    /** 中文 docx：标题（Heading1/Heading2 样式，含中文 Word 式 id）+ 段落 + 32 行表。 */
    private static byte[] docx() {
        try (XWPFDocument doc = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var ctStyles = org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyles.Factory
                    .newInstance();
            var h1 = ctStyles.addNewStyle();
            h1.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType.PARAGRAPH);
            h1.setStyleId("Heading1");
            h1.addNewName().setVal("heading 1");
            h1.addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.ZERO);
            var h2 = ctStyles.addNewStyle();
            h2.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType.PARAGRAPH);
            h2.setStyleId("2");
            h2.addNewName().setVal("heading 2");
            doc.createStyles().setStyles(ctStyles);

            heading(doc, "Heading1", "巡星科技二〇二五年半年度报告");
            heading(doc, "2", "一、公司基本情况");
            paragraph(doc, "巡星科技（北京）股份有限公司成立于2021年5月20日，注册地位于北京市海淀区，"
                    + "主营业务为商业航天地面站设备与测控系统的研发、制造与销售。报告期内，公司围绕"
                    + "「地面站即服务」战略推进产品迭代，陆续交付了多个海外客户的固定站与便携站项目，"
                    + "并完成了对上下游两家配套企业的战略投资。公司治理方面，报告期内完成了董事会换届，"
                    + "陈北望继续担任董事长兼总经理，原董事李梅因任期届满离任。");
            paragraph(doc, "截至2025年6月30日，公司员工总数为486人，其中研发人员占比约41%，"
                    + "较上年同期提高三个百分点。公司在境内拥有发明专利37项、实用新型专利84项，"
                    + "软件著作权112项；在境外通过PCT途径进入国家阶段的专利申请9项。");
            heading(doc, "2", "二、主要财务数据");
            paragraph(doc, "公司2025年上半年主要财务数据及同比变动情况如下：");
            XWPFTable table = doc.createTable(33, 4);
            String[] head = {"科目", "金额（万元）", "上年同期（万元）", "变动幅度"};
            for (int c = 0; c < 4; c++) {
                table.getRow(0).getCell(c).setText(head[c]);
            }
            String[] items = {"营业收入", "营业成本", "销售费用", "管理费用", "研发投入", "财务费用",
                "投资收益", "营业利润", "利润总额", "净利润", "扣非净利润", "经营现金流", "投资现金流",
                "筹资现金流", "存货余额", "应收账款", "应付账款", "合同负债", "在建工程", "固定资产",
                "无形资产", "商誉", "短期借款", "长期借款", "货币资金", "交易性金融资产", "其他应收款",
                "其他应付款", "递延收益", "未分配利润", "少数股东权益", "所有者权益合计"};
            for (int r = 0; r < items.length; r++) {
                XWPFTableRow row = table.getRow(r + 1);
                row.getCell(0).setText(items[r]);
                row.getCell(1).setText(String.valueOf(12800 + r * 231));
                row.getCell(2).setText(String.valueOf(9600 + r * 197));
                row.getCell(3).setText(String.format("%.1f%%", 8.0 + r * 1.7));
            }
            heading(doc, "2", "三、风险因素");
            paragraph(doc, "报告期内，公司面临的主要风险包括：上游射频器件供应集中度较高，"
                    + "若主要供应商产能受限，可能影响交付节奏；海外市场收入占比上升带来的汇率波动风险；"
                    + "以及商业航天行业政策与频率资源分配的不确定性。管理层已通过多元化采购、"
                    + "远期结汇与合规体系建设对上述风险进行对冲。");
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void heading(XWPFDocument doc, String styleId, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle(styleId);
        XWPFRun run = p.createRun();
        run.setText(text);
    }

    private static void paragraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
    }

    /** 中文 html：article + 多级标题 + 段落 + 表（要过 substantive 检查，不做 interstitial）。 */
    private static String html() {
        List<String> rows = new ArrayList<>();
        String[] cities = {"北京", "上海", "广州", "深圳", "成都", "西安", "乌鲁木齐", "喀什",
            "三亚", "佳木斯", "青岛", "厦门"};
        for (int i = 0; i < cities.length; i++) {
            rows.add("<tr><td>" + cities[i] + "站</td><td>" + (12 + i) + "米</td><td>"
                    + (2021 + i / 5) + "年</td><td>" + (3 + i) + "副天线</td></tr>");
        }
        return """
                <!DOCTYPE html><html><head><title>巡星科技地面站网络年度盘点</title></head>
                <body>
                <nav>首页 产品 新闻 关于我们 登录</nav>
                <article>
                <h1>巡星科技地面站网络年度盘点</h1>
                <p>过去一年，商业航天地面站运营商巡星科技把自建与托管站点的总数从二十一座扩展到三十九座，\
                覆盖范围从东部沿海延伸到西部边境。本文根据公司年度报告与公开访谈，梳理这张网络的建设节奏、\
                技术选型与商业逻辑，并对照行业同类玩家的布局做一些观察。</p>
                <h2>建设节奏：从沿海到边境</h2>
                <p>公司年初提出「百日三站」计划，先后在佳木斯、青岛与喀什完成固定站并网。其中喀什站因\
                冬季施工窗口短，实际工期压缩到七十四天，刷新了公司的纪录。下半年重点转向改扩建，三亚站\
                新增两副十二米天线，乌鲁木齐站完成上行链路扩容。管理层在业绩说明会上表示，明年将优先\
                补齐西南地区覆盖，昆明与拉萨站已进入选址阶段。</p>
                <h2>主要站点一览</h2>
                <table>
                <tr><th>站点</th><th>天线口径</th><th>并网年份</th><th>天线数量</th></tr>
                %s
                </table>
                <h2>技术选型与供应链</h2>
                <p>巡星科技的固定站普遍采用十二米与七点三米两种口径的天线，配套自研的基带设备支持\
                多频段同时接收。供应链方面，射频器件仍以进口为主，公司已与两家国内厂商开展替代验证，\
                预计明年完成首批国产化批次交付。便携站产品线则走集成路线，单人可在两小时内完成架设。</p>
                <p>行业观察人士指出，地面站网络的重资产属性决定了扩张节奏与融资能力强相关。巡星科技\
                今年完成的B轮融资为其提供了约十八个月的资本跑道，竞争对手则更多采用托管与合作建站\
                的轻资产模式。两种路线的优劣目前尚无定论，但客户对「一站多星」能力的诉求正在把行业\
                技术门槛推向更高处。</p>
                </article>
                <footer>联系方式 | 招聘 | 隐私政策</footer>
                </body></html>
                """.formatted(String.join("\n", rows));
    }
}
