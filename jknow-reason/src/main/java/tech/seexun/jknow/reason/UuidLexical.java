package tech.seexun.jknow.reason;

import java.util.UUID;

/**
 * UUID 的字节字典序（Rust uuid crate 的 Ord 语义）。{@link UUID#compareTo} 把两个 long
 * 当<b>有符号</b>数比——最高位为 1 的（时间戳毫秒最高位、测试造的 {@code [i;16]} 形状）会被
 * 排到最前，与 Rust 的无符号逐字节比较不是同一个序。环的 canonical 旋转（#618）、互斥组
 * 的首尾（#624）、Tarjan 的出发序都依赖「最小的 id」——序错一位，同一份数据就报出不同的
 * 行，人裁过的那行就被绕开。这里按 16 字节逐个无符号比较。
 */
public final class UuidLexical {

    private UuidLexical() {
    }

    /** Rust 的 {@code Uuid::nil()}：互斥组键里占「没有第二位」的那个槽。 */
    public static final UUID NIL = new UUID(0L, 0L);

    /** 与 Rust {@code uuid1 < uuid2} 等价的比较（负/零/正）。 */
    public static int compare(UUID a, UUID b) {
        byte[] x = toBytes(a);
        byte[] y = toBytes(b);
        for (int i = 0; i < 16; i++) {
            int d = (x[i] & 0xFF) - (y[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    }

    public static UUID min(UUID a, UUID b) {
        return compare(a, b) <= 0 ? a : b;
    }

    public static UUID max(UUID a, UUID b) {
        return compare(a, b) >= 0 ? a : b;
    }

    private static byte[] toBytes(UUID id) {
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (msb >>> (56 - 8 * i));
            out[8 + i] = (byte) (lsb >>> (56 - 8 * i));
        }
        return out;
    }
}
