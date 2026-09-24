package io.github.yueyinxin.shortlink.user;

/**
 * 用户状态。
 *
 * <p>v1.0.0 没有提供修改用户状态的接口（见需求文档 §2.2 范围外），
 * 但状态列已经存在。这样做的理由是：禁用用户是一个几乎必然会被提出的需求，
 * 而如果那时才加这一列，需要一次表结构变更 + 数据迁移 + 全量代码回归。
 * 现在加上的成本只是一个枚举值。
 *
 * <p>这与"为不存在的需求写代码"不同：这里只预留了<b>数据模型</b>，
 * 没有提前写状态流转逻辑（没有 {@code disable()} 接口、没有权限模型）。
 * 预留数据结构与预留业务逻辑的代价差异很大，前者几乎为零。
 */
public enum UserStatus {

    /** 正常，可以登录并使用全部功能。 */
    ACTIVE,

    /** 已禁用。保留数据但拒绝登录。 */
    DISABLED;

    public boolean allowsLogin() {
        return this == ACTIVE;
    }
}
