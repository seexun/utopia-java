# 侧栏宽度对齐 gvdata-ui 证据汇总

- 日期：2026-11-25
- 参考：gvdata-ui layout/index.vue:102 `--base-sidebar-width: 240px`、折叠态 54px（layout/index.vue:133 calc(100% - 54px)）
- 改动：variables.module.scss $base-sidebar-width/$sideBarWidth 210→240px（导出链与 --base-sidebar-width var 全自动跟随）；折叠态 sidebar.scss 60→54px（main-container margin-left 54px 原本就与 54 对齐，修正了旧 60/54 错位）；kb-switcher popper 210→240px（teleported 到 body 不在 .app-wrapper var 域，需字面量）
- 实测：展开 240（main margin-left 240 同步）、折叠 54（margin-left 54 对齐）、本体页 240、kb-switcher 弹层 240；console 零 error
- 截图：sw-01 展开 240 / sw-02 折叠 54 / sw-03 本体
- 回归：425/425 + typecheck + build:prod ✓
- 不动：gvdesign 零改区（anivia.scss 210px 为其它组件宽，非侧栏）
