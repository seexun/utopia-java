// 这里存放本地图标，在 src/layout/index.vue 文件中加载，避免在首启动加载
import { getSvgInfo } from "@pureadmin/utils";
import { addIcon } from "@iconify/vue/dist/offline";

// https://icon-sets.iconify.design/ep/?keyword=ep
import EpMenu from "~icons/ep/menu?raw";
import EpEdit from "~icons/ep/edit?raw";
import EpGuide from "~icons/ep/guide?raw";
import EpSetUp from "~icons/ep/set-up?raw";
import EpMonitor from "~icons/ep/monitor?raw";
import EpLollipop from "~icons/ep/lollipop?raw";
import EpHistogram from "~icons/ep/histogram?raw";
import EpHomeFilled from "~icons/ep/home-filled?raw";

// https://icon-sets.iconify.design/ri/?keyword=ri
import RiMindMap from "~icons/ri/mind-map?raw";
import RiAdminFill from "~icons/ri/admin-fill?raw";
import RiTableLine from "~icons/ri/table-line?raw";
import RiLinksFill from "~icons/ri/links-fill?raw";
import RiAdminLine from "~icons/ri/admin-line?raw";
import RiListCheck from "~icons/ri/list-check?raw";
import RiSearchLine from "~icons/ri/search-line?raw";
import RiWindowLine from "~icons/ri/window-line?raw";
import RiUbuntuFill from "~icons/ri/ubuntu-fill?raw";
import RiHistoryFill from "~icons/ri/history-fill?raw";
import RiEditBoxLine from "~icons/ri/edit-box-line?raw";
import RiCodeBoxLine from "~icons/ri/code-box-line?raw";
import RiArtboardLine from "~icons/ri/artboard-line?raw";
import RiMarkdownLine from "~icons/ri/markdown-line?raw";
import RiFileInfoLine from "~icons/ri/file-info-line?raw";
import RiBankCardLine from "~icons/ri/bank-card-line?raw";
import RiFilePpt2Line from "~icons/ri/file-ppt-2-line?raw";
import RiGitBranchLine from "~icons/ri/git-branch-line?raw";
import RiSettings3Line from "~icons/ri/settings-3-line?raw";
import RiUserVoiceLine from "~icons/ri/user-voice-line?raw";
import RiBookmark2Line from "~icons/ri/bookmark-2-line?raw";
import RiFileSearchLine from "~icons/ri/file-search-line?raw";
import RiChatSearchLine from "~icons/ri/chat-search-line?raw";
import RiInformationLine from "~icons/ri/information-line?raw";
import RiTerminalWindowLine from "~icons/ri/terminal-window-line?raw";
import RiCheckboxCircleLine from "~icons/ri/checkbox-circle-line?raw";
import RiBarChartHorizontalLine from "~icons/ri/bar-chart-horizontal-line?raw";

// 页面级图标（本仓图标离线化收口，与任务 10 菜单图标同法）：领域页
// （ontology/mappings/kb-settings/account/admin）此前直接 import { Icon }
// from "@iconify/vue"——那是**在线**运行时，缺的键走 api.iconify.design
// 现拉，内网/离线环境静默消失（用户帧里页面图标全裸）。两个要点：
// ① 注册键带冒号（页面模板写的就是 "lucide:xxx" 原键）；② 页面侧的 Icon
// 必须改从 "@iconify/vue/dist/offline" 导入——offline 运行时与在线运行时
// 是两个模块实例、各持一份图标注册表，只注册不换导入是无效的。
// 图标源与 ~icons 组件同一份 @iconify/json（构建期断言缺键直接编译失败）。
import LucideArrowLeftRight from "~icons/lucide/arrow-left-right?raw";
import LucideBookOpen from "~icons/lucide/book-open?raw";
import LucideCheckCheck from "~icons/lucide/check-check?raw";
import LucideChevronRight from "~icons/lucide/chevron-right?raw";
import LucideDatabase from "~icons/lucide/database?raw";
import LucideEllipsis from "~icons/lucide/ellipsis?raw";
import LucideHistory from "~icons/lucide/history?raw";
import LucideInbox from "~icons/lucide/inbox?raw";
import LucideKeyRound from "~icons/lucide/key-round?raw";
import LucideLayers from "~icons/lucide/layers?raw";
import LucideLock from "~icons/lucide/lock?raw";
import LucideNetwork from "~icons/lucide/network?raw";
import LucidePencil from "~icons/lucide/pencil?raw";
import LucidePlug from "~icons/lucide/plug?raw";
import LucidePlus from "~icons/lucide/plus?raw";
import LucideScale from "~icons/lucide/scale?raw";
import LucideSettings2 from "~icons/lucide/settings-2?raw";
import LucideShieldCheck from "~icons/lucide/shield-check?raw";
import LucideSplit from "~icons/lucide/split?raw";
import LucideTable from "~icons/lucide/table?raw";
import LucideTriangleAlert from "~icons/lucide/triangle-alert?raw";
import LucideUpload from "~icons/lucide/upload?raw";
import LucideUserRound from "~icons/lucide/user-round?raw";
import LucideUsers from "~icons/lucide/users?raw";
import LucideWand2 from "~icons/lucide/wand-2?raw";
import LucideX from "~icons/lucide/x?raw";
import SimpleIconsGithub from "~icons/simple-icons/github?raw";

const icons = [
  // Element Plus Icon: https://github.com/element-plus/element-plus-icons
  ["ep/menu", EpMenu],
  ["ep/edit", EpEdit],
  ["ep/guide", EpGuide],
  ["ep/set-up", EpSetUp],
  ["ep/monitor", EpMonitor],
  ["ep/lollipop", EpLollipop],
  ["ep/histogram", EpHistogram],
  ["ep/home-filled", EpHomeFilled],
  // Remix Icon: https://github.com/Remix-Design/RemixIcon
  ["ri/mind-map", RiMindMap],
  ["ri/admin-fill", RiAdminFill],
  ["ri/table-line", RiTableLine],
  ["ri/links-fill", RiLinksFill],
  ["ri/admin-line", RiAdminLine],
  ["ri/list-check", RiListCheck],
  ["ri/search-line", RiSearchLine],
  ["ri/window-line", RiWindowLine],
  ["ri/ubuntu-fill", RiUbuntuFill],
  ["ri/history-fill", RiHistoryFill],
  ["ri/edit-box-line", RiEditBoxLine],
  ["ri/code-box-line", RiCodeBoxLine],
  ["ri/artboard-line", RiArtboardLine],
  ["ri/markdown-line", RiMarkdownLine],
  ["ri/file-info-line", RiFileInfoLine],
  ["ri/bank-card-line", RiBankCardLine],
  ["ri/file-ppt-2-line", RiFilePpt2Line],
  ["ri/git-branch-line", RiGitBranchLine],
  ["ri/settings-3-line", RiSettings3Line],
  ["ri/user-voice-line", RiUserVoiceLine],
  ["ri/bookmark-2-line", RiBookmark2Line],
  ["ri/file-search-line", RiFileSearchLine],
  ["ri/chat-search-line", RiChatSearchLine],
  ["ri/information-line", RiInformationLine],
  ["ri/terminal-window-line", RiTerminalWindowLine],
  ["ri/checkbox-circle-line", RiCheckboxCircleLine],
  ["ri/bar-chart-horizontal-line", RiBarChartHorizontalLine],
  // 页面级图标注册（键与页面模板里的 "lucide:xxx" / "simple-icons:xxx" 同键）
  ["lucide:arrow-left-right", LucideArrowLeftRight],
  ["lucide:book-open", LucideBookOpen],
  ["lucide:check-check", LucideCheckCheck],
  ["lucide:chevron-right", LucideChevronRight],
  ["lucide:database", LucideDatabase],
  ["lucide:ellipsis", LucideEllipsis],
  ["lucide:history", LucideHistory],
  ["lucide:inbox", LucideInbox],
  ["lucide:key-round", LucideKeyRound],
  ["lucide:layers", LucideLayers],
  ["lucide:lock", LucideLock],
  ["lucide:network", LucideNetwork],
  ["lucide:pencil", LucidePencil],
  ["lucide:plug", LucidePlug],
  ["lucide:plus", LucidePlus],
  ["lucide:scale", LucideScale],
  ["lucide:settings-2", LucideSettings2],
  ["lucide:shield-check", LucideShieldCheck],
  ["lucide:split", LucideSplit],
  ["lucide:table", LucideTable],
  ["lucide:triangle-alert", LucideTriangleAlert],
  ["lucide:upload", LucideUpload],
  ["lucide:user-round", LucideUserRound],
  ["lucide:users", LucideUsers],
  ["lucide:wand-2", LucideWand2],
  ["lucide:x", LucideX],
  ["simple-icons:github", SimpleIconsGithub]
];

// 本地菜单图标，后端在路由的 icon 中返回对应的图标字符串并且前端在此处使用 addIcon 添加即可渲染菜单图标
icons.forEach(([name, icon]) => {
  addIcon(name as string, getSvgInfo(icon as string));
});
