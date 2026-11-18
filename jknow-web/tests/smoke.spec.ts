import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import { buildHierarchyTree } from "@/utils/tree";
import ReCol from "@/components/ReCol";

describe("W0 冒烟：基座在 vitest 下可用", () => {
  it("纯工具函数 buildHierarchyTree（router 机制使用）", () => {
    const tree = buildHierarchyTree([
      {
        path: "/a",
        children: [{ path: "/a/b" }]
      }
    ]);
    expect(Array.isArray(tree)).toBe(true);
    // buildHierarchyTree 会为节点补上 id/parentId/pathList 层级信息
    expect(tree[0].id).toBe(0);
    expect(tree[0].parentId).toBe(null);
    expect(tree[0].children[0].id).toBe(0);
    expect(tree[0].children[0].parentId).toBe(0);
    expect(tree[0].children[0].pathList).toEqual([0, 0]);
  });

  it("@vue/test-utils 可挂载基座组件 ReCol（Element Plus 封装）", () => {
    const wrapper = mount(ReCol, {
      slots: { default: "<span>utopia</span>" }
    });
    expect(wrapper.find(".el-col").exists()).toBe(true);
    expect(wrapper.text()).toContain("utopia");
  });
});
