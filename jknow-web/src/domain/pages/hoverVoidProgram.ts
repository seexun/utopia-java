// 悬停层的「空」节点 program —— sigma 3 会把正被指着的节点用同一个节点
// program 在 sigma-hoverNodes 这块 WebGL 画布上**再画一遍**，层级压在
// sigma-hovers（2D 悬停层）之上：本意是别让悬停的圆点埋进标签底下。
// 胶囊一轮之后这个重画成了 bug 的源头（走查微调 2，真实浏览器定位）：
// 悬停的反馈整体搬到了 2D 悬停层（类型色胶囊、描边换主色 1.5px，见 drawNodeLabel），
// 而 WebGL 圆盘（白环 0.14 + 白描边 0.16 + 类型色核心）原样盖在胶囊正中——
// 白环压着绿底就是走查截图里那颗「白圈」，核心还把中间的字抹掉一块。
// 圆盘与胶囊同心同色，胶囊永远把它整个盖住（高 ≥ 圆径 + 4），于是这层
// 对胶囊节点纯属多余；对无字的节点（仅在播放淡入的半秒窗口里可能出现）
// 顶多失去圆点上的白环反馈——可接受，不为此留一条会盖字的画法。
// 所以悬停 program 画**空**：光标反馈只有胶囊自己（规格 §2）。

import { NodeProgram } from "sigma/rendering";
import type { ProgramInfo } from "sigma/rendering";
import type { NodeDisplayData, RenderParams } from "sigma/types";

const UNIFORMS = ["u_matrix"] as const;

// 顶点落在裁剪面之外（NDC 里 z 越界），fragment 永远不会被光栅化到画布上
const VERTEX_SHADER_SOURCE = /*glsl*/ `
attribute vec2 a_position;
uniform mat3 u_matrix;
void main(void) {
  gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}
`;

const FRAGMENT_SHADER_SOURCE = /*glsl*/ `
precision highp float;
void main(void) {
  gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
}
`;

export class NodeHoverVoidProgram extends NodeProgram<(typeof UNIFORMS)[number]> {
  getDefinition() {
    return {
      VERTICES: 3,
      VERTEX_SHADER_SOURCE,
      FRAGMENT_SHADER_SOURCE,
      METHOD: WebGLRenderingContext.TRIANGLES,
      UNIFORMS,
      ATTRIBUTES: [{ name: "a_position", size: 2, type: WebGLRenderingContext.FLOAT }],
    };
  }

  // 什么都不写：坐标恒在裁剪面外，片元全透明，双重保证不产出任何像素
  processVisibleItem(
    _nodeIndex: number,
    startIndex: number,
    _data: NodeDisplayData,
  ): void {
    const array = this.array;
    array[startIndex] = 2e9;
    array[startIndex + 1] = 2e9;
    array[startIndex + 2] = 0;
  }

  setUniforms(_params: RenderParams, { gl, uniformLocations }: ProgramInfo): void {
    gl.uniformMatrix3fv(uniformLocations.u_matrix, false, _params.matrix);
  }
}
