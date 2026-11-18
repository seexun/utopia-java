/* 本体树分页（遗留 4 复核钉）：新侧本体页左栏树自 W1 起就带「1–10 / 共 13」
   式分页（treePaged/propPaged + UPager，页长随列表区高度自适应）——报告 §2
   「全量平铺」判定与代码不符，本文件钉住分页契约本身：页长切片、越界页号
   收敛、不足一页自动隐藏（旧仓 Pager 三语义逐一对照）。 */
import { describe, it, expect, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { UPager, pageSlice } from "@/components/knowledge/kit";
import { S } from "@/i18n";

describe("本体左栏 UPager：旧仓 Pager 三语义", () => {
  it("13 条每页 10：第一页显「1–10 / 共 13」，上一页禁用、下一页可用", () => {
    const w = mount(UPager, {
      props: { total: 13, pageSize: 10, page: 0 }
    });
    expect(w.text()).toContain(S.library.pageOf(1, 10, 13));
    const btns = w.findAll("button");
    expect(btns[0].attributes("disabled")).toBeDefined(); // prev
    expect(btns[1].attributes("disabled")).toBeUndefined(); // next
  });

  it("翻到第二页：显「11–13 / 共 13」，下一页禁用；emit page=1", async () => {
    const w = mount(UPager, {
      props: { total: 13, pageSize: 10, page: 0 }
    });
    await w.findAll("button")[1].trigger("click");
    expect(w.emitted("page")![0]).toEqual([1]);
    await w.setProps({ page: 1 });
    expect(w.text()).toContain(S.library.pageOf(11, 13, 13));
    const btns = w.findAll("button");
    expect(btns[0].attributes("disabled")).toBeUndefined();
    expect(btns[1].attributes("disabled")).toBeDefined();
  });

  it("越界页号收敛到安全页（数据缩水后不空白）", () => {
    const w = mount(UPager, {
      props: { total: 13, pageSize: 10, page: 9 }
    });
    expect(w.text()).toContain(S.library.pageOf(11, 13, 13));
  });

  it("不足一页自动隐藏（total<=pageSize 时组件不渲染）", () => {
    const w = mount(UPager, {
      props: { total: 8, pageSize: 10, page: 0 }
    });
    expect(w.find("div").exists()).toBe(false);
  });
});

describe("pageSlice：树/关系两节的切片语义（railRows 自适应页长下的行为）", () => {
  const rows = Array.from({ length: 13 }, (_, i) => i);

  it("第一页取前 10，safe=0", () => {
    expect(pageSlice(rows, 0, 10)).toEqual({
      rows: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
      safe: 0
    });
  });

  it("末页取余 3 条，safe 收敛", () => {
    expect(pageSlice(rows, 1, 10).rows).toEqual([10, 11, 12]);
    expect(pageSlice(rows, 5, 10)).toEqual({ rows: [10, 11, 12], safe: 1 });
  });

  it("过滤后（旧仓 filter 模式拍平）每页照切、页码回零由 watch(filter) 承担", () => {
    const filtered = rows.filter(n => n % 2 === 0); // 7 条
    const r = pageSlice(filtered, 0, 5);
    expect(r.rows).toEqual([0, 2, 4, 6, 8]);
    expect(pageSlice(filtered, 1, 5)).toEqual({ rows: [10, 12], safe: 1 });
    // 页号越界收敛：7 条每页 10 只有一页
    expect(pageSlice(filtered, 9, 10)).toEqual({
      rows: [0, 2, 4, 6, 8, 10, 12],
      safe: 0
    });
  });
});
