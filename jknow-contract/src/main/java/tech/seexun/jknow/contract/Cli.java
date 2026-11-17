package tech.seexun.jknow.contract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 极简 CLI 参数解析：{@code --key value}，无外部依赖（纯 JDK 模块）。 */
final class Cli {

    private final Map<String, List<String>> values = new HashMap<>();

    static Cli parse(String[] args) {
        Cli cli = new Cli();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数: " + arg);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("参数 " + arg + " 缺值");
            }
            cli.values.computeIfAbsent(arg.substring(2), k -> new ArrayList<>()).add(args[++i]);
        }
        return cli;
    }

    String require(String key) {
        List<String> v = values.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("缺少必填参数 --" + key);
        }
        return v.get(0);
    }

    List<String> all(String key) {
        return values.getOrDefault(key, List.of());
    }

    String opt(String key, String def) {
        List<String> v = values.get(key);
        return v == null || v.isEmpty() ? def : v.get(0);
    }
}
