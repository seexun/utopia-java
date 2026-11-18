/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import path from 'path'
import vue from '@vitejs/plugin-vue'
import vueJsx from '@vitejs/plugin-vue-jsx'
import Icons from 'unplugin-icons/vite'
import autoImport from 'unplugin-auto-import/vite'
import tailwindcss from '@tailwindcss/vite'
import createVitePlugins from './vite/plugins'

const baseUrl = 'http://localhost:8080' // 后端接口

// https://vitejs.dev/config/
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd())
  const { VITE_APP_ENV } = env
  return {
    // 部署生产环境和开发环境下的URL。
    // 默认情况下，vite 会假设你的应用是被部署在一个域名的根路径上
    // 例如 https://www.ruoyi.vip/。如果应用被部署在一个子路径下，你就需要用这个选项指定子路径。例如，如果你的应用被部署在 https://www.ruoyi.vip/admin/，则设置 baseUrl 为 /admin/。
    base: VITE_APP_ENV === 'production' ? '/' : '/',
    plugins: [
      tailwindcss(),
      // 知识图谱组件族的 tsx（源仓 build/plugins.ts 同款；若依面无 tsx，故不在
      // vite/plugins 里挂）——不挂则 .tsx 走默认 JSX（React classic），
      // 运行时 ReferenceError: React is not defined（T3 冒烟实证）
      vueJsx(),
      // 知识图谱页面族的图标按需加载（源仓 build/plugins.ts 同款配置）：
      // ~icons/lucide|ri|ep/<name> 虚拟模块 + ?raw/svg 变体
      Icons({ compiler: 'vue3', scale: 1 }),
      ...createVitePlugins(env, command === 'build')
    ],
    resolve: {
      // https://cn.vitejs.dev/config/#resolve-alias
      alias: {
        // 设置路径
        '~': path.resolve(__dirname, './'),
        // 设置别名
        '@': path.resolve(__dirname, './src')
      },
      // https://cn.vitejs.dev/config/#resolve-extensions
      extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue']
    },
    // 打包配置
    build: {
      // https://vite.dev/config/build-options.html
      sourcemap: command === 'build' ? false : 'inline',
      outDir: 'dist',
      assetsDir: 'assets',
      chunkSizeWarningLimit: 2000,
      rollupOptions: {
        output: {
          chunkFileNames: 'static/js/[name]-[hash].js',
          entryFileNames: 'static/js/[name]-[hash].js',
          assetFileNames: 'static/[ext]/[name]-[hash].[ext]'
        }
      }
    },
    // vite 相关配置
    server: {
      port: 80,
      host: true,
      open: true,
      proxy: {
        // https://cn.vitejs.dev/config/server-proxy
        '/dev-api': {
          target: baseUrl,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/dev-api/, '')
        },
        // 知识图谱 API 面（baseURL /api，后端路径就是 /api/v1/...——不 rewrite）
        '/api': {
          target: baseUrl,
          changeOrigin: true
        },
         // springdoc proxy
         '^/v3/api-docs/(.*)': {
          target: baseUrl,
          changeOrigin: true,
        }
      }
    },
    css: {
      postcss: {
        plugins: [
          {
            postcssPlugin: 'internal:charset-removal',
            AtRule: {
              charset: (atRule: any) => {
                if (atRule.name === 'charset') {
                  atRule.remove()
                }
              }
            }
          }
        ]
      }
    },
    // vitest（对齐源仓 utopia-web/vitest.config.ts 的 projects 双面结构，T2 随
    // src/domain 迁入落全——按实况去 pure-admin 基建依赖：alias 内联、不搬
    // unplugin-icons/yaml loader/VITE_ 钉值——jknow 路由 history 模式不读它们）。
    // app 面（jsdom）挂 tests|src 的 *.spec.ts（vue/vueJsx 插件供视图类 spec 的
    // SFC 转换，红清单 T3 修）；domain 面（node + 根目录 vitest-setup.ts 的
    // localStorage 桩、语言钉 en）挂 src/domain/**/*.test.ts。vitest 4 的内联
    // projects 不继承根配置的 resolve/plugins，共享面同时铺进每个 project。
    test: {
      projects: [
        {
          resolve: {
            alias: { '@': path.resolve(__dirname, './src') }
          },
          // unplugin-icons：知识页面族/壳组件的 ~icons/lucide|ri|ep/* 虚拟模块
          // （源仓 vitest.config.ts 同款；spec 挂载视图需要同一套转换）；
          // AutoImport：若依 src 面（store/index、layout 链）依赖 unplugin-auto-import
          // 的全局 API（createPinia/ref/...），spec 经 @/router 拖进若依代码时需
          // 同一套转换（T2 报告预告项；dts 关闭防测试运行写盘）
          plugins: [
            vue(),
            vueJsx(),
            Icons({ compiler: 'vue3', scale: 1 }),
            autoImport({ imports: ['vue', 'vue-router', 'pinia'], dts: false })
          ],
          test: {
            name: 'app',
            environment: 'jsdom',
            include: ['tests/**/*.spec.ts', 'src/**/*.spec.ts']
          }
        },
        {
          resolve: {
            alias: { '@': path.resolve(__dirname, './src') }
          },
          test: {
            name: 'domain',
            environment: 'node',
            include: ['src/domain/**/*.test.ts'],
            setupFiles: ['./vitest-setup.ts']
          }
        }
      ]
    }
  }
})

