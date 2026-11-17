package tech.seexun.jknow.store.entity;

import java.util.UUID;

/**
 * 一次注册建出来的东西（对齐 Rust accounts::Registered）。
 * 首个用户连带建出 General 库（id 在此返回）；后续注册加入既有工作区，generalKb 为 null。
 */
public record Registered(UserRow user, WorkspaceRow workspace, UUID generalKb) {
}
