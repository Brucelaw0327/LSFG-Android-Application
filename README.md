# LSFG-Android-Chinese（汉化增强版）

基于 [FrankBarretta/LSFG-Android-Application](https://github.com/FrankBarretta/LSFG-Android-Application) 修改的 Android 端帧生成应用。本 README 仅列出与原版的不同。

> 以下改动均在 **一加 Ace 6（ColorOS 16 / Android 16 / Adreno 830）** 上实测通过。

## 1. 全量简体中文界面

全部文案（教程、设置、悬浮面板、错误提示）翻译为简体中文（`values-zh-rCN`）。

## 2. ColorOS 16（Android 16）特权截屏支持

原版的特权截屏在 ColorOS 16 上拿不到物理显示器 token，会静默失败。本版：

- 新增 ColorOS 专属路径：`DisplayInfo.address.getPhysicalDisplayId()`（公开 API）→ `OplusDisplayManager.getPhysicalDisplayToken(long)`（ColorOS 私有接口）→ `DisplayCaptureArgs` 特权捕获；
- 新增 `HiddenApiBypass` 元反射绕过，无需 `settings put hidden_api_policy` 全局改动即可调用隐藏 API，退出后系统保持原样；
- 捕获失败时完整回传诊断信息，便于排查机型适配。

## 3. 悬浮球常驻所有应用 + 前台自动识别

- 新增 `AutoOverlayWatcherService`：Shizuku 或 Root 3 秒轮询前台应用，悬浮球**常驻所有应用与桌面**（默认开启，可在设置关闭）；
- 点开悬浮球面板显示当前前台应用，一键开启插帧，无需回主界面选目标应用；
- 面板内置**「刷新当前应用」**按钮：探测有竞态（如刚切进游戏仍显示桌面）时一键强制重新探测；
- 修复探测服务因配置瞬时读取失败而自杀、悬浮球消失的问题（失败跳过重试，异常判定需连续 3 次确认）；
- 目标应用进入小窗/分屏/画中画时自动结束会话并提示（UID 过滤截屏只含目标图层，非全屏下无法合成完整画面）。

## 4. 旋转与生命周期稳定性重构

- 旋转时暂停帧生成并停放悬浮层，约 500ms 后重启会话——旋转瞬间画面不再被插帧撕裂；
- 渲染循环停机改为立即退出（不再逐帧排空积压队列白等超时），叠加**持久化 `VkPipelineCache`**（首次编译后落盘复用），旋转恢复时间从 ~6s 降至 ~1.5s；
- 目标不在前台时推迟旋转重启，回前台补做——修复退出游戏回桌面黑屏、只能手动结束会话的 bug；
- 修复旋转后悬浮层半屏残留（停放偏移按旧宽度计算，重排后失效）；
- 修复切换帧倍率后画面永久冻结的问题（重初始化丢失目标包名，捕获暂停后不再恢复）；
- `rc=-40` 渲染循环泄漏自动自愈；`sessionGeneration` 代数守卫杜绝跨会话僵尸捕获。

## 5. 交互与体验

- 游戏内面板关闭「LSFG 帧生成」= 直接结束会话（原版切入透传模式，只有开销没有收益），并弹 Toast 提示；
- 首页顶栏新增悬浮窗权限入口（状态着色），未授权时打开应用自动弹窗引导授权。

## 6. 构建与工程

- `minSdk` 提升至 **34（Android 14+）**；
- 删除声明但未使用的 `INTERNET` 权限（代码零网络请求，最小权限）；
- 修复 lint 错误（`setFrameRate` API 31 守卫、`SoonBlockedPrivateApi` 豁免）；
- `release` 构建签名配置就绪，R8 混淆规则覆盖 JNI 符号绑定与 Shizuku/Root 服务类。
