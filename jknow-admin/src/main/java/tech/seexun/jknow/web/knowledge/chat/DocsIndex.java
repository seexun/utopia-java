package tech.seexun.jknow.web.knowledge.chat;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Charter 语料（产品手册）的内存索引（docs_corpus.rs + docs.rs 的 Java 载体）。
 *
 * <p><b>语料载体（M5 recon §八风险四）</b>：Rust 用 include_str! 把 web/src/docs/*.md
 * 编进二进制；Java 侧改为 classpath 资源（utopia-server/src/main/resources/docs/，从旧仓
 * web/src/docs 只读拷入——单一事实来源仍是那批 markdown，随二进制一起打包）。
 *
 * <p>检索按「词面 + 覆盖率」打分（Tantivy+jieba 的语义等价集口径：小语料、BM25 词面
 * 命中即可用，结果按命中词数与覆盖排序）；按 h2 切节，一节一条索引记录，命中返回具体
 * 小节而不是整篇——slug 必须与前端 DOCS 清单一致（引用链接 /docs/{slug} 才对得上）。
 */
@Component
public class DocsIndex {

    /** 一节文档（按 h2 切分；anchor 与前端 Docs 页的标题锚点同法生成）。 */
    public record DocsSection(String slug, String title, String heading, String anchor,
                              String body) {
    }

    private final List<DocsSection> sections = new ArrayList<>();

    @PostConstruct
    void load() {
        // ARTICLES 等价物：classpath docs/*.md，文件名即 slug。多一份文件就多一份索引
        // （Rust 的 every_corpus_md_file_is_indexed 守卫在拷贝清单里以剧本核对）。
        Map<String, String> articles = new LinkedHashMap<>();
        articles.put("ingest", "Ingest interfaces");
        articles.put("mcp", "Agents over MCP");
        for (Map.Entry<String, String> article : articles.entrySet()) {
            String body = readResource("docs/" + article.getKey() + ".md");
            splitIntoSections(article.getKey(), article.getValue(), body);
        }
    }

    private static String readResource(String path) {
        try (InputStream in = DocsIndex.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Charter 语料缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Charter 语料读不出来: " + path, e);
        }
    }

    /** 按 h2 切节；h2 之前的引言归入「标题节」（anchor 为空，链接落到文章顶部）。 */
    private void splitIntoSections(String slug, String title, String body) {
        String heading = title;
        String anchor = "";
        List<String> buf = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("## ")) {
                flush(slug, title, heading, anchor, buf);
                // 与前端 tocOf 同法清洗：去掉行内 code/强调符号
                heading = line.substring(3).replace("`", "").replace("*", "").trim();
                anchor = slugify(heading);
            } else if (!line.startsWith("# ")) {
                buf.add(line);
            }
        }
        flush(slug, title, heading, anchor, buf);
    }

    private void flush(String slug, String title, String heading, String anchor,
                       List<String> buf) {
        String text = String.join("\n", buf).trim();
        buf.clear();
        if (!text.isEmpty()) {
            sections.add(new DocsSection(slug, title, heading, anchor, text));
        }
    }

    /**
     * 与前端 Docs.tsx 的 slugify 逐字对齐（锚点跳转依赖两边一致）：小写后，
     * [a-z0-9一-龥] 之外的连续串折成单个 '-'，再去掉首尾 '-'。
     */
    static String slugify(String s) {
        StringBuilder out = new StringBuilder();
        boolean dash = false;
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || (c >= '\u4e00' && c <= '\u9fa5');
            if (keep) {
                if (dash && out.length() > 0) {
                    out.append('-');
                }
                dash = false;
                out.append(c);
            } else {
                dash = true;
            }
        }
        return out.toString();
    }

    /** 检索：词面命中（小写、按词切），按覆盖的词数排序，同覆盖按语料序。 */
    public List<DocsSection> search(String query, int limit) {
        List<String> words = words(query);
        if (words.isEmpty()) {
            return List.of();
        }
        record Hit(DocsSection section, int score, int index) {
        }
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            DocsSection s = sections.get(i);
            String text = (s.heading() + "\n" + s.body()).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String w : words) {
                if (text.contains(w)) {
                    score++;
                }
            }
            if (score > 0) {
                hits.add(new Hit(s, score, i));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparingInt(Hit::index));
        List<DocsSection> out = new ArrayList<>();
        for (Hit h : hits) {
            if (out.size() >= limit) {
                break;
            }
            out.add(h.section());
        }
        return out;
    }

    private static List<String> words(String query) {
        List<String> out = new ArrayList<>();
        for (String w : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isBlank()) {
                out.add(w);
            }
        }
        return out;
    }

    /** 测试面：切出的全部节（Rust ingest_splits_into_sections 的断言入口）。 */
    public List<DocsSection> allSections() {
        return sections;
    }
}
