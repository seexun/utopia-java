package tech.seexun.jknow.contract;

/** 剧本执行失败（expect 不符 / 捕获路径缺失 / 请求异常）：Recorder 中止该剧本，黄金样本必须是绿的。 */
public class ScenarioFailure extends RuntimeException {

    public ScenarioFailure(String message) {
        super(message);
    }

    public ScenarioFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
