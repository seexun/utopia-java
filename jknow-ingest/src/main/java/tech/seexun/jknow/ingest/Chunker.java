package tech.seexun.jknow.ingest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 分块器（chunker.rs 逐契约移植）。**块的职责是「抽取一次能看见的全部」**，块边界是抽取
 * 契约的一部分：
 *
 * <ul>
 *   <li>一行不拆；表头与表体不拆；说明句（表上那句「结果如下：」≤200B 或冒号结尾，连续
 *       ≤5 段）与它的表（隔着水平线也算，引出一串表时每一张）不拆；标题贴下面第一段；</li>
 *   <li>一张表放得下就整张放；放不下按行切，<b>每个续块重复面包屑、说明句和表头</b>；</li>
 *   <li>预算 300 token 按 {@link TokenEstimator} 近似计数（1 CJK 字=1，≈4 拉丁字符=1）；</li>
 *   <li>水平线不是切点；不重叠；一块只装一种出处；</li>
 *   <li>正文是原文切片一字不改；前缀（面包屑/说明句/表头）从别处抄来，
 *       charStart/charEnd 只指正文。</li>
 * </ul>
 */
public final class Chunker {

    /** 一块的预算（token）。300 不是 1000——模型一次调用写出的facts数不随输入变长而变多。 */
    public static final int BUDGET_TOKENS = 300;

    /** 说明句最长多少字节还算说明句（chunker.rs CAPTION_MAX_BYTES）。 */
    static final int CAPTION_MAX_BYTES = 200;

    /** 表前最多几段短说明一起当说明句。 */
    static final int CAPTION_RUN_MAX = 5;

    /** 不到这么多 token 的一段（页码、脚注标记）不单独成块，并进上一块。 */
    static final int TINY_TOKENS = 8;

    private Chunker() {
    }

    public static List<ChunkPiece> chunkText(String text) {
        return chunkWithBudget(text, BUDGET_TOKENS);
    }

    public static List<ChunkPiece> chunkWithBudget(String text, int budget) {
        List<Segment> whole =
                List.of(new Segment(0, text.getBytes(StandardCharsets.UTF_8).length,
                        Provenance.stated()));
        return chunkSegments(text, whole, budget);
    }

    /**
     * 按出处分段的正文分块：块不跨段（0040 决定 2），面包屑跨段照传。
     * segments 按 UTF-8 字节区间排好序、覆盖整份正文；空的时候当整份都是原文。
     */
    public static List<ChunkPiece> chunkSegments(String text, List<Segment> segments, int budget) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        List<Segment> segs = segments == null || segments.isEmpty() ? List.of(
                new Segment(0, bytes.length, Provenance.stated())) : segments;
        Packer p = new Packer(bytes, budget, segs);
        for (Unit unit : units(bytes, Blocks.blocks(text))) {
            int start = switch (unit) {
                case UHeading h -> h.range().start();
                case UText t -> t.range().start();
                case UTable t ->
                    t.caption() != null ? t.caption().start() : t.head().start();
            };
            // 标题不切段：它贴着下一项走，由下一项决定进哪一段
            if (!(unit instanceof UHeading)) {
                p.enterSegment(p.segmentOf(start));
            }
            switch (unit) {
                case UHeading h -> p.heading(h.level(), h.range());
                case UText t -> p.textUnit(t.range());
                case UTable t -> p.tableUnit(t.caption(), t.head(), t.rows());
            }
        }
        // 文档以标题收尾：攒着的正文先收成一块，标题再自己成一块，总好过丢掉
        if (!p.pending.isEmpty()) {
            p.flush();
            p.prefix = p.prefixNow();
            p.body = new ArrayList<>(p.pending.stream().map(PText::new).toList());
            p.pending.clear();
        }
        p.flush();
        return p.out;
    }

    // ---- 打包单元 ----

    sealed interface Unit permits UHeading, UText, UTable {
    }

    record UHeading(int level, Blocks.Range range) implements Unit {
    }

    record UText(Blocks.Range range) implements Unit {
    }

    record UTable(Blocks.Range caption, Blocks.Range head, List<Blocks.Range> rows) implements Unit {
    }

    /** 标题贴着下一块走；表带着自己的说明句（可能一串）。 */
    private static List<Unit> units(byte[] bytes, List<Blocks.Block> blocks) {
        List<Unit> out = new ArrayList<>(blocks.size());
        int i = 0;
        while (i < blocks.size()) {
            Blocks.Block b = blocks.get(i);
            switch (b.kind()) {
                case Blocks.Heading level -> {
                    out.add(new UHeading(level.level(), b.range()));
                    i++;
                }
                case Blocks.Rule ignored -> i++;
                case Blocks.Other ignored -> {
                    out.add(new UText(b.range()));
                    i++;
                }
                case Blocks.Table t -> {
                    out.add(new UTable(null, t.head(), t.rows()));
                    i++;
                }
                case Blocks.Paragraph ignored -> {
                    // 紧跟着表的短段落是说明句，跟表走：短(≤200B)或冒号结尾；连续几段一起算
                    // （最多五段，中间不隔别的东西）；一句说明引出的是紧跟其后的一串表；
                    // 说明句和表之间隔着水平线也算紧跟（水平线是分页符）
                    java.util.function.Predicate<Blocks.Block> captionLike = blk -> blk.kind()
                            instanceof Blocks.Paragraph
                            && (blk.range().len() <= CAPTION_MAX_BYTES
                                    || slice(bytes, blk.range()).stripTrailing().endsWith(":"));
                    int k = i + 1;
                    while (k - i < CAPTION_RUN_MAX && k < blocks.size()
                            && captionLike.test(blocks.get(k))) {
                        k++;
                    }
                    Blocks.Range caption =
                            new Blocks.Range(b.range().start(), blocks.get(k - 1).range().end());
                    int j = k;
                    int attached = 0;
                    if (captionLike.test(b)) {
                        while (true) {
                            while (j < blocks.size() && blocks.get(j).kind() instanceof Blocks.Rule) {
                                j++;
                            }
                            if (j < blocks.size()
                                    && blocks.get(j).kind() instanceof Blocks.Table table) {
                                out.add(new UTable(caption, table.head(), table.rows()));
                                attached++;
                                j++;
                            } else {
                                break;
                            }
                        }
                    }
                    if (attached == 0) {
                        out.add(new UText(b.range()));
                        i++;
                    } else {
                        i = j;
                    }
                }
            }
        }
        return out;
    }

    private static String slice(byte[] bytes, Blocks.Range r) {
        return new String(bytes, r.start(), r.end() - r.start(), StandardCharsets.UTF_8);
    }

    // ---- 正文里的项 ----

    sealed interface Piece permits PText, PTable {
    }

    record PText(Blocks.Range range) implements Piece {
    }

    record PTable(Blocks.Range caption, Blocks.Range head, List<Blocks.Range> rows) implements Piece {
    }

    private static final class Packer {
        private final byte[] bytes;
        private final int budget;
        private final List<ChunkPiece> out = new ArrayList<>();
        private final List<PathEntry> path = new ArrayList<>();
        private final List<Blocks.Range> pending = new ArrayList<>();
        private List<PathEntry> beforePending = new ArrayList<>();
        private List<Blocks.Range> prefix = new ArrayList<>();
        private List<Piece> body = new ArrayList<>();
        private final List<Segment> segments;
        private int current = 0;

        private record PathEntry(int level, Blocks.Range range) {
        }

        Packer(byte[] bytes, int budget, List<Segment> segments) {
            this.bytes = bytes;
            this.budget = budget;
            this.segments = segments;
        }

        private String slice(Blocks.Range r) {
            return new String(bytes, r.start(), r.end() - r.start(), StandardCharsets.UTF_8);
        }

        /** 位置 at 落在哪一段（段按位置排好序）。 */
        int segmentOf(int at) {
            int found = 0;
            for (int i = 0; i < segments.size(); i++) {
                if (segments.get(i).start() <= at) {
                    found = i;
                } else {
                    break;
                }
            }
            return found;
        }

        /** 下一项要进 seg 段：跟正在攒的块不是同一段，先把那块收掉（标题不跟着收）。 */
        void enterSegment(int seg) {
            if (seg != current) {
                flush();
                current = seg;
            }
        }

        String render(List<Blocks.Range> prefixRanges, List<Piece> bodyPieces) {
            List<String> parts = new ArrayList<>();
            for (Blocks.Range r : prefixRanges) {
                parts.add(slice(r));
            }
            // 同一块里一串表共用一句说明：说明只在第一张前面出现一次
            Blocks.Range lastCaption = null;
            for (Piece p : bodyPieces) {
                if (p instanceof PTable t) {
                    if (t.caption() != null && t.caption().equals(lastCaption)) {
                        parts.add(renderTable(new PTable(null, t.head(), t.rows())));
                    } else {
                        lastCaption = t.caption();
                        parts.add(renderTable(t));
                    }
                } else if (p instanceof PText t) {
                    lastCaption = null;
                    parts.add(slice(t.range()));
                }
            }
            return String.join("\n\n", parts);
        }

        private String renderTable(PTable t) {
            StringBuilder sb = new StringBuilder();
            if (t.caption() != null) {
                sb.append(slice(t.caption())).append("\n\n");
            }
            sb.append(slice(t.head()));
            for (Blocks.Range row : t.rows()) {
                sb.append('\n').append(slice(row));
            }
            return sb.toString();
        }

        /** 新块的前缀：现在这一章的标题路径，去掉马上要作为正文出现的那几条。 */
        List<Blocks.Range> prefixNow() {
            List<Blocks.Range> out = new ArrayList<>();
            for (PathEntry e : path) {
                if (!pending.contains(e.range())) {
                    out.add(e.range());
                }
            }
            return out;
        }

        boolean fits(List<Blocks.Range> prefixRanges, List<Piece> bodyPieces) {
            return TokenEstimator.tokens(render(prefixRanges, bodyPieces)) <= budget;
        }

        /** 试着把一项放进当前块；放不下就先收当前块再试一次；返回放不下的那一项。 */
        Piece place(Piece piece) {
            if (pending.isEmpty() && piece instanceof PText r
                    && TokenEstimator.tokens(slice(r.range())) <= TINY_TOKENS) {
                if (!body.isEmpty()) {
                    body.add(piece);
                    return null;
                }
                // 上一块已经发出去了：接到它尾上。只接同一种出处的——
                // 一行转写不能混进原文那一块里借它的可信度
                Provenance here = segments.get(current).provenance();
                if (!out.isEmpty()
                        && out.get(out.size() - 1).provenance().equals(here)) {
                    ChunkPiece last = out.get(out.size() - 1);
                    String text = last.text() + "\n\n" + slice(r.range());
                    out.set(out.size() - 1, new ChunkPiece(last.seq(), text, last.charStart(),
                            Math.max(last.charEnd(), r.range().end()), last.heading(),
                            last.provenance()));
                    return null;
                }
            }
            List<Piece> withHeadings = new ArrayList<>();
            for (Blocks.Range r : pending) {
                withHeadings.add(new PText(r));
            }
            withHeadings.add(piece);
            if (body.isEmpty()) {
                prefix = prefixNow();
            }
            List<Piece> candidate = new ArrayList<>(body);
            candidate.addAll(withHeadings);
            if (fits(new ArrayList<>(prefix), candidate)) {
                body = candidate;
                pending.clear();
                return null;
            }
            // 放不下：把刚加的拆回来
            int n = candidate.size() - (pending.size() + 1);
            List<Piece> rest = candidate.subList(n, candidate.size());
            Piece thePiece = rest.get(rest.size() - 1);
            body = new ArrayList<>(candidate.subList(0, n));
            if (!body.isEmpty()) {
                flush();
                return place(piece);
            }
            return thePiece;
        }

        void flush() {
            if (body.isEmpty()) {
                return;
            }
            List<Piece> flushedBody = body;
            body = new ArrayList<>();
            List<Blocks.Range> flushedPrefix = prefix;
            prefix = new ArrayList<>();
            String text = render(flushedPrefix, flushedBody);
            int start = Integer.MAX_VALUE;
            int end = 0;
            for (Piece p : flushedBody) {
                Blocks.Range span = spanOf(p);
                start = Math.min(start, span.start());
                end = Math.max(end, span.end());
            }
            // 收块时还有没贴上正文的标题，是下一章的第一项放不下才收的：
            // 那几条标题跟着下一块走，这一块按换章之前的路径起名
            List<PathEntry> namedBy = pending.isEmpty() ? path : beforePending;
            out.add(new ChunkPiece(out.size(), text, start, end, breadcrumb(namedBy),
                    segments.get(current).provenance()));
        }

        private Blocks.Range spanOf(Piece p) {
            if (p instanceof PText t) {
                return t.range();
            }
            PTable t = (PTable) p;
            int start = t.caption() != null ? t.caption().start() : t.head().start();
            int end = t.rows().isEmpty() ? t.head().end() : t.rows().get(t.rows().size() - 1).end();
            return new Blocks.Range(start, end);
        }

        String breadcrumb(List<PathEntry> namedBy) {
            if (namedBy.isEmpty()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            for (PathEntry e : namedBy) {
                String heading = slice(e.range());
                while (heading.startsWith("#")) {
                    heading = heading.substring(1);
                }
                parts.add(heading.strip());
            }
            return String.join(" › ", parts);
        }

        void heading(int level, Blocks.Range range) {
            if (pending.isEmpty()) {
                beforePending = new ArrayList<>(path);
            }
            while (!path.isEmpty() && path.get(path.size() - 1).level() >= level) {
                path.remove(path.size() - 1);
            }
            path.add(new PathEntry(level, range));
            pending.add(range);
        }

        void textUnit(Blocks.Range range) {
            if (place(new PText(range)) == null) {
                return;
            }
            // 一段就超预算：退回按句切，每一小段自成一块，前缀照给
            prefix = prefixNow();
            int prefixCost = TokenEstimator.tokens(render(new ArrayList<>(prefix), List.of()));
            List<Piece> headings = new ArrayList<>();
            for (Blocks.Range r : pending) {
                headings.add(new PText(r));
            }
            pending.clear();
            int headingCost = TokenEstimator.tokens(render(List.of(), headings));
            int capacity = Math.max(1, budget - prefixCost - headingCost);
            List<Blocks.Range> subs = splitBySentence(bytes, range, capacity);
            boolean first = true;
            for (Blocks.Range sub : subs) {
                if (first) {
                    body = new ArrayList<>(headings);
                    first = false;
                }
                body.add(new PText(sub));
                prefix = prefixNow();
                flush();
            }
        }

        void tableUnit(Blocks.Range caption, Blocks.Range head, List<Blocks.Range> rows) {
            if (place(new PTable(caption, head, rows)) == null) {
                return;
            }
            // 整张放不下：按行切，每一块都带说明句和表头。一行不拆；
            // 说明句+表头+一行还超预算的，照样出一块——没有更好的切法
            int i = 0;
            while (i < rows.size()) {
                List<Blocks.Range> taken = new ArrayList<>();
                taken.add(rows.get(i));
                i++;
                while (i < rows.size()) {
                    List<Blocks.Range> trial = new ArrayList<>(taken);
                    trial.add(rows.get(i));
                    PTable piece = new PTable(caption, head, trial);
                    List<Piece> body = new ArrayList<>();
                    for (Blocks.Range r : pending) {
                        body.add(new PText(r));
                    }
                    body.add(piece);
                    if (fits(prefixNow(), body)) {
                        taken.add(rows.get(i));
                        i++;
                    } else {
                        break;
                    }
                }
                prefix = prefixNow();
                body = new ArrayList<>();
                for (Blocks.Range r : pending) {
                    body.add(new PText(r));
                }
                pending.clear();
                body.add(new PTable(caption, head, taken));
                flush();
            }
        }

        /**
         * 长段落的退路切分（text_splitter 的对等层级：<b>行</b>→句→词→字符）。
         * 行不拆——一行放得下就整行走；一行单独超预算才退句界，句超退词界、词超退字符。
         * 覆盖整段、无重叠；行边界对齐（块末不含尾随换行，续行完整保留）。
         */
        private static List<Blocks.Range> splitBySentence(byte[] bytes, Blocks.Range range,
                int capacity) {
            String block = new String(bytes, range.start(), range.end() - range.start(),
                    StandardCharsets.UTF_8);
            // 原子件：行（超容量的行退句/词/字符）。行区间不含尾随换行，覆盖不重叠
            List<int[]> atoms = new ArrayList<>();
            int lineStart = 0;
            for (int i = 0; i <= block.length(); i++) {
                if (i == block.length() || block.charAt(i) == '\n') {
                    if (i > lineStart) {
                        atoms.add(new int[] {lineStart, i});
                    }
                    lineStart = i + 1;
                }
            }
            List<int[]> refined = new ArrayList<>();
            for (int[] line : atoms) {
                if (TokenEstimator.tokens(block.substring(line[0], line[1])) <= capacity) {
                    refined.add(line);
                } else {
                    refined.addAll(splitSentences(block, line, capacity));
                }
            }
            // 相邻贪心合并（合并后含块内换行，逐块 flush 时一块一 piece——文本不变形）
            List<int[]> packed = new ArrayList<>();
            int[] run = null;
            for (int[] p : refined) {
                if (run == null) {
                    run = p;
                } else {
                    String joined = block.substring(run[0], p[1]);
                    if (TokenEstimator.tokens(joined) <= capacity) {
                        run = new int[] {run[0], p[1]};
                    } else {
                        packed.add(run);
                        run = p;
                    }
                }
            }
            if (run != null) {
                packed.add(run);
            }
            // 字符区间 → 字节区间（块按 UTF-8 切片，逐件累计字节长）
            List<Blocks.Range> out = new ArrayList<>();
            for (int[] p : packed) {
                byte[] head = block.substring(0, p[0]).getBytes(StandardCharsets.UTF_8);
                byte[] piece = block.substring(p[0], p[1]).getBytes(StandardCharsets.UTF_8);
                out.add(new Blocks.Range(range.start() + head.length,
                        range.start() + head.length + piece.length));
            }
            if (out.isEmpty()) {
                out.add(range);
            }
            return out;
        }

        /** 句界切（一行内部）：句超容量退词界、词也超退字符（splitHard）。 */
        private static List<int[]> splitSentences(String block, int[] line, int capacity) {
            List<int[]> sentences = new ArrayList<>();
            int sentStart = line[0];
            int i = line[0];
            while (i < line[1]) {
                char ch = block.charAt(i);
                if (".!?…。！？".indexOf(ch) >= 0) {
                    boolean cjkTerminator = "。！？…".indexOf(ch) >= 0;
                    int end = i + 1;
                    while (end < line[1] && ")」』”’\"".indexOf(block.charAt(end)) >= 0) {
                        end++;
                    }
                    if (cjkTerminator || end >= line[1]
                            || Character.isWhitespace(block.charAt(end))
                            || ".!?…。！？".indexOf(block.charAt(end)) >= 0) {
                        // 句末空白并进上一句：下一句从实字起头
                        while (end < line[1] && Character.isWhitespace(block.charAt(end))) {
                            end++;
                        }
                        sentences.add(new int[] {sentStart, end});
                        sentStart = end;
                    }
                    i = end;
                } else {
                    i++;
                }
            }
            if (sentStart < line[1]) {
                sentences.add(new int[] {sentStart, line[1]});
            }
            List<int[]> out = new ArrayList<>();
            for (int[] s : sentences) {
                if (TokenEstimator.tokens(block.substring(s[0], s[1])) > capacity) {
                    out.addAll(splitHard(block, s, capacity));
                } else {
                    out.add(s);
                }
            }
            if (out.isEmpty()) {
                out.add(line);
            }
            return out;
        }

        private static List<int[]> splitHard(String block, int[] piece, int capacity) {
            List<int[]> out = new ArrayList<>();
            int start = piece[0];
            int end = piece[1];
            while (start < end) {
                int cut = end;
                while (cut > start + 1
                        && TokenEstimator.tokens(block.substring(start, cut)) > capacity) {
                    // 先退到词界，再退字符
                    int space = block.lastIndexOf(' ', cut - 1);
                    int newCut = space > start ? space : cut - 1;
                    if (newCut >= cut) {
                        newCut = cut - 1;
                    }
                    cut = newCut;
                }
                if (cut <= start) {
                    cut = start + 1;
                }
                out.add(new int[] {start, cut});
                start = cut;
                while (start < end && Character.isWhitespace(block.charAt(start))) {
                    start++;
                }
            }
            return out;
        }
    }
}
