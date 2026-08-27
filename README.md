# 听笺 0.2.2

一个仅使用 Android 系统 `TextToSpeech` 的本地听读应用。当前闭环是：粘贴文字或导入 UTF-8 TXT/无 DRM EPUB，分章节、分段连续朗读，在后台或锁屏时继续工作，并保存播放进度。

## 当前功能

- 书库、新建/导入、播放、基础设置页面
- 多篇内容书库，支持重命名、删除和“未开始/听读中/已读完”状态
- 粘贴文字、Android 文件选择器导入 UTF-8 TXT
- 导入无 DRM EPUB，自动读取书名、作者、章节目录和 spine 正文顺序
- EPUB 播放页显示当前章节，忽略复杂图片、版式和表格
- 按换行和中文/英文句末标点切分，展示段最多 500 个 UTF-16 字符
- 长段朗读时再拆成最多 120 字符的 TTS 片段，并保存段内字符位置
- 播放、暂停、停止、上一段、下一段
- 0.8x、1.0x、1.2x、1.5x 语速
- 可保存默认语速；系统返回多个可区分中文声音时可保存默认声音
- `mediaPlayback` 前台服务、partial wake lock，以及通知栏播放/暂停/上一段/下一段
- 普通通知音与朗读短暂叠加，不暂停正文；来电等短暂独占音频会暂停并在焦点恢复后续播
- 使用音频焦点处理其他音乐；耳机断开时自动暂停
- TTS 初始化或朗读异常后有限自动重试
- 15、30、60 分钟睡眠定时
- 使用 Room 保存多本内容、章节、段落和段内进度，并自动迁移 0.1.x 的单篇数据
- 每次内容/进度变更后自动生成应用内数据库快照，同时支持手动导出/恢复 JSON
- 全文结束后显示“已读完”，可从头重新播放
- 首页提供荣耀后台运行、电池优化和自启动设置引导
- 启动时读取系统 TTS Engine、Voice、Locale 和联网要求

## EPUB 范围

- 仅支持无 DRM 的 EPUB 2/3 常规文本书籍
- 不处理封面之外的复杂图片、排版、脚注交互或表格
- 检测到加密信息时保守拒绝导入，并显示错误；不尝试绕过 DRM
- 原始 TXT/EPUB 文件不会因删除书库条目而被删除

## 构建

项目需要 JDK 17 和 Android SDK。当前电脑的工具链位于 `D:\PrivateListen`。

```powershell
$env:JAVA_HOME='D:\PrivateListen\tools\jdk17\jdk-17.0.20+8'
$env:ANDROID_HOME='D:\PrivateListen\AndroidSdk'
$env:ANDROID_SDK_ROOT='D:\PrivateListen\AndroidSdk'
$env:GRADLE_USER_HOME='D:\PrivateListen\gradle-cache'
.\gradlew.bat :app:testQaUnitTest :app:lintQa :app:assembleDebug :app:assembleQa :app:assembleQaAndroidTest
```

可安装的优化 APK 使用 release 构建；当前私人侧载版本沿用本机 debug 签名，以便覆盖升级：

```powershell
.\gradlew.bat :app:assembleRelease
```

荣耀 Magic7 上的 ADB 需要关闭 libusb 后端：

```powershell
$env:ADB_LIBUSB='0'
D:\PrivateListen\AndroidSdk\platform-tools\adb.exe devices -l
```

`qa` 构建类型使用 `.qa` 包名，可在不改动正式应用数据的情况下做实机验收。

## 荣耀 Magic7 自动验收

`tools\qa-device.ps1` 会构建并安装隔离的 QA 包，完成 Room 迁移/备份仪器测试、EPUB 元数据与章节导入、默认设置、重命名、通知四按钮、锁屏前台服务和耳机断开暂停路径验证，随后卸载 QA 包。

荣耀系统仍会要求设备凭据确认侧载安装。若要无人值守运行，可只在当前 PowerShell 进程临时提供凭据；脚本不会把它写入磁盘，进程结束后即失效：

```powershell
$env:PRIVATE_LISTEN_DEVICE_PIN = Read-Host '设备密码'
.\tools\qa-device.ps1
Remove-Item Env:PRIVATE_LISTEN_DEVICE_PIN -ErrorAction SilentlyContinue
```
