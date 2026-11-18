import axios from "axios";
import type { App } from "vue";

/** jknow T2 类型闭合：源仓的 PlatformConfigs 全局接口（types/global.d.ts，pure-admin
 * 基建）未随迁，且源仓 tsconfig strict:false 掩住了本文件 object 索引/置空的松散
 * 写法——此处按本文件实际消费面给最小形状收口（逻辑逐语句不变）。 */
interface PlatformConfigs {
  ResponsiveStorageNameSpace?: string;
  [key: string]: unknown;
}

let config: PlatformConfigs = {};
const { VITE_PUBLIC_PATH } = import.meta.env;

const setConfig = (cfg?: unknown) => {
  config = Object.assign(config, cfg);
};

const getConfig = (key?: string): PlatformConfigs => {
  if (typeof key === "string") {
    const arr = key.split(".");
    if (arr && arr.length) {
      let data: PlatformConfigs | null = config;
      arr.forEach(v => {
        if (data && typeof data[v] !== "undefined") {
          data = data[v] as PlatformConfigs;
        } else {
          data = null;
        }
      });
      return data as PlatformConfigs;
    }
  }
  return config;
};

/** 获取项目动态全局配置 */
export const getPlatformConfig = async (app: App): Promise<undefined> => {
  app.config.globalProperties.$config = getConfig();
  return axios({
    method: "get",
    url: `${VITE_PUBLIC_PATH}platform-config.json`
  })
    .then(({ data: config }: { data: PlatformConfigs }) => {
      let $config = app.config.globalProperties.$config;
      // 自动注入系统配置
      if (app && $config && typeof config === "object") {
        $config = Object.assign($config, config);
        app.config.globalProperties.$config = $config;
        // 设置全局配置
        setConfig($config);
      }
      return $config;
    })
    .catch(() => {
      throw "请在public文件夹下添加platform-config.json配置文件";
    });
};

/** 本地响应式存储的命名空间 */
const responsiveStorageNameSpace = () => getConfig().ResponsiveStorageNameSpace;

export { getConfig, setConfig, responsiveStorageNameSpace };
