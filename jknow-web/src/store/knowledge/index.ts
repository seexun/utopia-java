// 知识域 store 入口（源仓 src/store/index.ts 的 jknow 落位）。
// T3 裁定：utopia 的 pinia 体系与若依 store 并存且**共用同一个 pinia 实例**——
// 知识域模块经 *Hook(store) 显式传实例（源仓同款机制），实例取若依 main.ts
// 装进应用的这一枚，两侧 store 同源；测试（spec 的 globalPinia）也拿同一枚。
// 源仓 index.ts 的 setupStore(app) 不迁：应用装配由若依 main.ts 的 app.use(store)
// 承担（pinia 4 的 app.use 即源仓 setupStore 的全部职责）。
export { default as store } from "@/store";
