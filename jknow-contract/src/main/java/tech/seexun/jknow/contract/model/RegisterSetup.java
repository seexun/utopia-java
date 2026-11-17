package tech.seexun.jknow.contract.model;

/**
 * 剧本前置（setup.register）：确保录制账号存在。
 * 引擎语义：先注册拿会话；409（邮箱已注册）转登录拿会话——两次连录、干净环境首录都成立。
 * setup 本身不进黄金样本（前置，不是被测契约）。
 *
 * @param email       录制账号邮箱（如 contract0@test.local）
 * @param password    密码（如 Contract0!2026）
 * @param displayName 展示名，缺省 "Contract"（注册接口必填）
 */
public record RegisterSetup(String email, String password, String displayName) {

    public String displayNameOrDefault() {
        return displayName == null || displayName.isBlank() ? "Contract" : displayName;
    }
}
