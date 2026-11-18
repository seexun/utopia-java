const LOCK_KEY = 'screen-lock'
const LOCK_PATH_KEY = 'screen-lock-path'

interface LockState {
  isLock: boolean
  lockPath: string
}

export const useLockStore = defineStore('lock', {
  // 首页语义统一（menu-ia T2）：lockPath 兜底/回落 '/'——解锁后回首页 = 直达当前库
  // 图谱（原 '/index' 落若依默认首页；记录的是真实当前路径，兜底仅在无路径时用）
  state: (): LockState => ({
    isLock: JSON.parse(localStorage.getItem(LOCK_KEY) || 'false'),
    lockPath: localStorage.getItem(LOCK_PATH_KEY) || '/'
  }),
  actions: {
    // 锁定屏幕，同时记录当前路径
    lockScreen(currentPath: string) {
      this.lockPath = currentPath || '/'
      localStorage.setItem(LOCK_PATH_KEY, this.lockPath)
      this.isLock = true
      localStorage.setItem(LOCK_KEY, 'true')
    },
    // 解锁屏幕，清除路径
    unlockScreen() {
      this.isLock = false
      localStorage.setItem(LOCK_KEY, 'false')
      this.lockPath = '/'
      localStorage.setItem(LOCK_PATH_KEY, '/')
    }
  }
})

export default useLockStore
