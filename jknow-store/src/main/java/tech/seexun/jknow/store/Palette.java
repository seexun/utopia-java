package tech.seexun.jknow.store;

import java.nio.charset.StandardCharsets;

/**
 * 实体类的配色与形状（palette.rs 逐字对等；M3 任务 10 随本体编辑器写端点落地——
 * create_entity_type 的「不给颜色就按 key 取一个」缺它）。
 *
 * <p><b>为什么是哈希而不是轮转</b>：轮转要维护计数器，而类是从好几条路建出来的
 * （手动、导入、消解、建库），计数器就得跨路共享——那是个会不同步的状态。按 key
 * 哈希不需要状态：<b>同一个 key 永远同一个颜色</b>，无论它是谁建的、第几个建的、
 * 重建过几次。重导一遍本体，颜色不会跳。
 *
 * <p><b>为什么手写 FNV-1a</b>：默认哈希的种子每个进程都不一样。拿它取色，服务器一重启
 * 同一个类就换颜色——而颜色是用户用来认东西的。所以这里定死的常量、定死的结果，
 * 跨进程跨机器都一样。
 */
public final class Palette {

    private Palette() {
    }

    /**
     * 实体类的色板（palette.rs:32）。<b>这一组是既有的，不许换</b>——换过一版莫兰迪，
     * 拿真实的图一看就否了：低饱和加中明度是为纸面调的，撞上近黑的画布会整片发灰。
     * 改这里就得同步改 web/src/palette.ts 的 ENTITY_PALETTE。
     */
    public static final String[] ENTITY_PALETTE = {
            "#7fd0ff", "#5fa8ff", "#5fd4d0", "#63e2b7", "#4cc38a", "#a8d878", "#ffd479",
            "#f2b66d", "#ff9d76", "#ff8a9e", "#ff9daf", "#e797d8", "#c4a5ff", "#9fa8ff",
            "#8ea5bd", "#b3b9c4",
    };

    /**
     * 类的形状（palette.rs:53）：<b>方 = 词表声明的，圆 = 语料里长出来的</b>——形状少而
     * 醒目，该承载一个真实区分；有没有 IRI 是确定的、当场就知道的。
     */
    public static String shapeFor(String iri) {
        return iri == null || iri.trim().isEmpty() ? "circle" : "square";
    }

    /**
     * palette.rs:66-80 color_for_key：FNV-1a + 雪崩混合再取模。<b>雪崩那步不能省</b>：
     * 光 FNV 再取模会聚集——实测 person/project/research_lab 撞进同一格，八个常见 key
     * 只散到五种颜色；把高位搅进低位后就散开了（色板长度不是质数，低位本身带不了
     * 多少信息）。
     */
    public static String colorForKey(String key) {
        long hash = 0xcbf2_9ce4_8422_2325L; // FNV offset basis
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= 0x0000_0100_0000_01b3L; // FNV prime
        }
        // 雪崩混合（murmur3 finalizer 的两步）：光 FNV 再取模会聚集
        hash ^= hash >>> 33;
        hash *= 0xff51_afd7_ed55_8ccdL;
        hash ^= hash >>> 33;
        int idx = (int) Long.remainderUnsigned(hash, ENTITY_PALETTE.length);
        return ENTITY_PALETTE[idx];
    }
}
