// 方形节点的渲染 program —— 与圆形 createNodeBorderProgram 完全同构：
// 状态环(0.14) → 白描边(0.16) → 类型色实心(fill)（方向 A，规格 §2，
// graphCanvas.ts 的 NODE_PROGRAMS）。
// 圆形用欧几里得距离 length()，方形改用切比雪夫距离 max(|x|,|y|)，
// 几何沿用 @sigma/node-square：6 顶点四边形、角点 ±45°/±135°、sqrt8 缩放
//（半边长取圆形半径的 0.9：边长等于直径时方块看着比圆大；状态环随之等比缩）。

import { NodeProgram } from "sigma/rendering";
import type { ProgramInfo } from "sigma/rendering";
import type { NodeDisplayData, RenderParams } from "sigma/types";
import { floatColor } from "sigma/utils";

const { UNSIGNED_BYTE, FLOAT } = WebGLRenderingContext;

const UNIFORMS = ["u_sizeRatio", "u_correctionRatio", "u_cameraAngle", "u_matrix"] as const;

const VERTEX_SHADER_SOURCE = /*glsl*/ `
attribute vec2 a_position;
attribute float a_size;
attribute float a_angle;

uniform mat3 u_matrix;
uniform float u_sizeRatio;
uniform float u_cameraAngle;
uniform float u_correctionRatio;

varying vec2 v_diffVector;
varying float v_radius;

#ifdef PICKING_MODE
attribute vec4 a_id;
varying vec4 v_color;
#else
attribute vec4 a_ringColor;
attribute vec4 a_borderColor;
attribute vec4 a_color;
varying vec4 v_ringColor;
varying vec4 v_borderColor;
varying vec4 v_fillColor;
#endif

const float sqrt_8 = sqrt(8.0);
const float sqrt_2 = sqrt(2.0);

void main() {
  // 方块比圆小一丁点：边长 = 直径时方块看着比圆大（面积 4r² 对 πr²）。取 0.9，
  // 接近等面积的 0.886，各层环随之一起缩，hover/选中态两种形状仍对齐
  float size = a_size * u_correctionRatio / u_sizeRatio * sqrt_8 * 0.9;
  // 屏幕上保持轴对齐（跟随相机旋转），切比雪夫度量用未旋转的本地坐标
  float angle = a_angle + u_cameraAngle;
  vec2 diffVector = size * vec2(cos(angle), sin(angle));
  vec2 position = a_position + diffVector;
  gl_Position = vec4((u_matrix * vec3(position, 1)).xy, 0, 1);

  v_diffVector = size * vec2(cos(a_angle), sin(a_angle));
  v_radius = size / sqrt_2; // 半边长 = 0.9 × 圆形程序的半径

  #ifdef PICKING_MODE
  v_color = a_id;
  #else
  v_ringColor = a_ringColor;
  v_borderColor = a_borderColor;
  v_fillColor = a_color;
  #endif
}
`;

const FRAGMENT_SHADER_SOURCE = /*glsl*/ `
precision highp float;

varying vec2 v_diffVector;
varying float v_radius;

#ifdef PICKING_MODE
varying vec4 v_color;
#else
varying vec4 v_ringColor;
varying vec4 v_borderColor;
varying vec4 v_fillColor;
#endif

uniform float u_correctionRatio;

const float bias = 255.0 / 254.0;
const vec4 transparent = vec4(0.0, 0.0, 0.0, 0.0);

void main(void) {
  float dist = max(abs(v_diffVector.x), abs(v_diffVector.y));

  #ifdef PICKING_MODE
  if (dist > v_radius)
    gl_FragColor = transparent;
  else {
    gl_FragColor = v_color;
    gl_FragColor.a *= bias;
  }
  #else
  float aaBorder = 2.0 * u_correctionRatio;
  // 层厚比例与圆形配置一致：状态环 0.14 / 白描边 0.16 / 剩余为类型色实心
  float r0 = v_radius;
  float r1 = r0 - v_radius * 0.14;
  float r2 = r1 - v_radius * 0.16;

  vec4 c1 = v_ringColor;   c1.a *= bias;
  vec4 c2 = v_borderColor; c2.a *= bias;
  vec4 c3 = v_fillColor;   c3.a *= bias;

  if (dist > r0) {
    gl_FragColor = transparent;
  } else if (dist > r0 - aaBorder) {
    gl_FragColor = mix(c1, transparent, (dist - r0 + aaBorder) / aaBorder);
  } else if (dist > r1) {
    gl_FragColor = c1;
  } else if (dist > r1 - aaBorder) {
    gl_FragColor = mix(c2, c1, (dist - r1 + aaBorder) / aaBorder);
  } else if (dist > r2) {
    gl_FragColor = c2;
  } else if (dist > r2 - aaBorder) {
    gl_FragColor = mix(c3, c2, (dist - r2 + aaBorder) / aaBorder);
  } else {
    gl_FragColor = c3;
  }
  #endif
}
`;

export class NodeSquareShellProgram extends NodeProgram<(typeof UNIFORMS)[number]> {
  getDefinition() {
    return {
      VERTICES: 6,
      VERTEX_SHADER_SOURCE,
      FRAGMENT_SHADER_SOURCE,
      METHOD: WebGLRenderingContext.TRIANGLES,
      UNIFORMS,
      ATTRIBUTES: [
        { name: "a_position", size: 2, type: FLOAT },
        { name: "a_id", size: 4, type: UNSIGNED_BYTE, normalized: true },
        { name: "a_size", size: 1, type: FLOAT },
        { name: "a_ringColor", size: 4, type: UNSIGNED_BYTE, normalized: true },
        { name: "a_borderColor", size: 4, type: UNSIGNED_BYTE, normalized: true },
        { name: "a_color", size: 4, type: UNSIGNED_BYTE, normalized: true },
      ],
      CONSTANT_ATTRIBUTES: [{ name: "a_angle", size: 1, type: FLOAT }],
      CONSTANT_DATA: [
        [Math.PI / 4],
        [(3 * Math.PI) / 4],
        [-Math.PI / 4],
        [(3 * Math.PI) / 4],
        [-Math.PI / 4],
        [(-3 * Math.PI) / 4],
      ],
    };
  }

  processVisibleItem(nodeIndex: number, startIndex: number, data: NodeDisplayData): void {
    const array = this.array;
    const d = data as NodeDisplayData & {
      ringColor?: string;
      borderColor?: string;
    };
    array[startIndex++] = data.x;
    array[startIndex++] = data.y;
    array[startIndex++] = nodeIndex;
    array[startIndex++] = data.size;
    array[startIndex++] = floatColor(d.ringColor || "rgba(0,0,0,0)");
    array[startIndex++] = floatColor(d.borderColor || "rgba(0,0,0,0)");
    array[startIndex++] = floatColor(data.color);
  }

  setUniforms(params: RenderParams, { gl, uniformLocations }: ProgramInfo): void {
    const { u_sizeRatio, u_correctionRatio, u_cameraAngle, u_matrix } = uniformLocations;
    gl.uniform1f(u_sizeRatio, params.sizeRatio);
    gl.uniform1f(u_correctionRatio, params.correctionRatio);
    gl.uniform1f(u_cameraAngle, params.cameraAngle);
    gl.uniformMatrix3fv(u_matrix, false, params.matrix);
  }
}
