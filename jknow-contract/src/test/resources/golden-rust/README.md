# golden-rust 权威对照面（288 样本 / 19 族）

P3 golden 复活批随仓的 rust 权威快照（T1 拷入，commit `8c64746`）：源仓 utopia-contract
双打时代的 rust 侧录制面（`target=both` 168 + `target=rust` 存照 120）。对表形态与命令序列见
仓库根 [README.md「golden 契约对表」](../../../../../README.md)；账目口径见
[../scenarios/WAIVERS.md](../scenarios/WAIVERS.md) 与
[../scenarios/COVERAGE.md §0](../scenarios/COVERAGE.md)。

## 权威面编辑规则（冻结面唯一允许的改动形态）

- **仅产品身身份字段可随 jknow 产品版本对齐**（如 `health.json` 的 `version`）——该类字段是
  服务器产品标识而非行为契约；行为契约字段（状态码、错误形状、业务字段集/值）**禁改**。
- 改动必须**单 commit + 当期任务报告留痕**（改了哪个文件哪个字段、为何非改不可、为何是
  最小改）。首例：`health/health.json` version 0.1.0→1.0.0（2026-11-23，T3，
  [golden-revival report §3.2](../../../../../../docs/superpowers/plans/golden-revival/report.md)）。
- 其余一切差异走 [../scenarios/WAIVERS.md](../scenarios/WAIVERS.md) 挂账（一行一键+原因码），
  不改权威面。
