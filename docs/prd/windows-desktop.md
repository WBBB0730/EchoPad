# windows-desktop

**Windows 版电脑端**

## Problem Statement

电脑端目前只有 Mac（Apple 芯片）版，用 Windows 电脑的人用不了 EchoPad。手机端已经可以配多台电脑端，也已经会按电脑的系统显示修饰键（Windows 上是 Ctrl、Alt、Shift、Win），差的只是 Windows 上的电脑端。

## Solution

给电脑端加 Windows 版，功能和 Mac 版一致：触控板、打字与按键、语音输入、剪贴板同步、发送文件、配对和应用内更新。只改电脑端和发版流程，手机端和通信协议不动，Mac 版的行为不变。安装包是一个 `setup.exe`，和 Mac、安卓的安装包一起发到同一个 GitHub Release。

电脑端当初就按跨平台选的技术（ADR-0001），大部分代码两个系统共用。要补的是几处只写了 Mac 的实现（剪贴板、屏幕参考、缩放、托盘和窗口），加上安装包、应用内更新和发版流程里的 Windows 部分。

## User Stories

1. 作为 Windows 用户，我希望在 GitHub Release 上下载一个安装包，不需要管理员权限就能装上电脑端。
2. 作为 Windows 用户，我希望用手机移动指针、点击、右键、滚动、拖拽和缩放，手感和 Mac 版一致。
3. 作为 Windows 用户，我希望用手机键盘打的字、语音输入整理好的文字直接输入到电脑的插入点。
4. 作为 Windows 用户，我希望手机上的快捷操作换成 Windows 的组合键，修饰键显示成 Ctrl、Alt、Shift、Win。
5. 作为 Windows 用户，我希望手机和电脑之间同步剪贴板上的文字，密码管理器复制的密码不被同步。
6. 作为 Windows 用户，我希望手机发来的文件存进"下载"文件夹并且已经复制好，在资源管理器里复制的文件也能发到手机。
7. 作为 Windows 用户，我希望关掉窗口后电脑端留在系统托盘里继续运行，单击托盘图标重新打开窗口。
8. 作为 Windows 用户，我希望再次打开 EchoPad 时显示已有的窗口，而不是启动第二个。
9. 作为 Windows 用户，我希望电脑端能在应用内更新，更新之后配对仍然有效。
10. 作为同时用 Mac 和 Windows 的用户，我希望一部手机同时配上两台电脑，随时切换当前电脑。
11. 作为维护者，我希望 Mac、Windows、安卓三个安装包一起发版，任何一个打包失败就整个不发布。
12. 作为维护者，我希望每次推到 `main` 都在 Windows 上编译并跑一遍电脑端的测试。

## Implementation Decisions

- **范围**：只改 `desktop/`、`.github/workflows/`、仓库根的 `.gitattributes`，以及文档。`protocol/` 和 `mobile/` 不动：电脑端连接时已经把自己的系统告诉手机端，手机端已经按它显示修饰键（docs/prd/typing.md）。Mac 版的行为不变，新加的依赖只在 Windows 上编译。
- **开发方式**：维护者决定在 Mac 上写完，不在 Windows 真机上验证。Mac 上能做的检查：用 cargo-xwin 交叉编译 Windows 版（含测试代码）、交叉打出 NSIS 安装包；不依赖系统的换算逻辑写成两个系统共用的纯函数，在 Mac 上跑单元测试。Windows 上的单元测试和集成测试由日常检查在 Windows runner 上跑。模拟键鼠、剪贴板、托盘这些直接调系统接口的部分，没有在 Windows 上运行过。
- **支持的系统**：64 位（x86_64）的 Windows 10 和 Windows 11。
- **启动和窗口**：
  - 再次打开 EchoPad 时显示已有的窗口。Mac 靠系统的"重新打开"事件，Windows 没有这个事件，改用 Tauri 官方的单实例插件（`tauri-plugin-single-instance` 的稳定版 2.x），只在 Windows 上注册。
  - 常驻在系统托盘（任务栏的通知区域）：关掉窗口只是隐藏，任务栏上不留按钮；左键单击托盘图标打开窗口，右键是菜单，菜单内容和 Mac 一样。
  - 托盘图标用彩色的应用图标：Mac 用的黑色单色模板图在深色任务栏上看不见。
  - 窗口里不放菜单栏：Tauri 的应用菜单在 Windows 上会显示在窗口里，所以只在 Mac 上设置（ADR-0020）。
  - 标题栏用系统自带的：Mac 上"标题栏和系统窗口按钮融合"是 macOS 专属的窗口设置，在 Windows 上不生效。下面那条放置顶按钮的顶栏照常显示。
  - "发送文本"对话框里 Ctrl+Enter 也能发送，Mac 上仍是 ⌘↩。
  - 拖文件到主窗口、窗口置顶、系统对话框、系统通知、选文件面板用的都是 Tauri 跨平台的接口，不改，只验证。
- **模拟键鼠**（docs/prd/touchpad.md、docs/prd/typing.md）：
  - 用的库 enigo 支持 Windows。点击、双击、拖拽、打字、特殊键、媒体键、修饰键不用改。
  - 快捷操作已经按 Windows 写好：Ctrl 加字母，重做是 Ctrl+Y，切换应用是 Alt+Tab。修饰键里的 Meta 在 Windows 上是 Win 键。
  - 快捷操作和带修饰键的字交给主线程按，是 Mac 的限制；Windows 上沿用同一条路径，不另写分支。
  - 不需要"辅助功能"权限：权限状态始终是已授权，"需要允许辅助功能"这一行不出现。
  - 指针移动：先读出指针的位置，加上位移，再移到算出的新位置，不受 Windows 的鼠标速度和"提高指针精确度"影响，手感仍由手机端的指针加速决定。位移的单位在 Mac 上是点，在 Windows 上是物理像素，所以乘以指针所在显示器的缩放比例（缩放 150% 就乘 1.5），两个系统一样快。enigo 换算新位置时只按主显示器算，指针到不了其他显示器（上游源码里还留着这条待办），所以 Windows 上的移动不经过 enigo：按整个虚拟桌面换算成绝对坐标，用系统的 `SendInput` 发出。换算时取目标像素的中心，系统换算回像素时不论向下取整还是四舍五入都落在这个像素上，慢慢移动时不会卡在原地。屏幕边缘和显示器之间的空隙由 Windows 自己挡住，不用 Mac 上限制在显示器内的那一套。
  - 滚动：用 enigo 的平滑滚动，在 Windows 上发的是不足一格的滚轮量（一格是 120）。手机端发来的是点，按 Chromium 一格滚 100 像素换算，1 点是 1.2 个滚轮单位。这个系数是估计值，要在真机上调到和 Mac 接近。
  - 缩放：按住 Ctrl 滚滚轮（ADR-0023）。缩放量（两指距离变化比例减 1 的累计）每攒够 0.15 发一格滚轮，向上是放大；每次都是按下 Ctrl、滚轮、松开 Ctrl，Ctrl 不会一直按在电脑上，和修饰键的处理一致（ADR-0016）。0.15 是估计值，要在真机上调。表现是一档一档地缩放，浏览器里是整页缩放。
  - 控制不了以管理员身份运行的窗口，除非电脑端也以管理员身份运行（docs/prd/touchpad.md 已写明）。
- **剪贴板**（docs/prd/clipboard-sync.md、docs/prd/send-files.md、ADR-0017）：
  - 用库 `clipboard-win`；图片格式的转换用库 `image`。
  - 发现用户复制的内容：沿用每 0.5 秒看一次变化计数、变了才读的做法，Windows 上的计数是系统的剪贴板序列号。电脑端自己写入后记下新的计数，不把自己的写入当成用户复制。ADR-0017 加上 Windows 一节。
  - 用户主动同步时读取：在资源管理器里复制的文件优先，其次是文字，最后是图片（转成 PNG），和 Mac 一样。
  - 写入：文字；手机端发来的文件，效果和在资源管理器里复制这些文件一样，只有一张图片时另放一份图片数据。
  - 不同步的内容：带有微软约定的"不进剪贴板历史、不同步到别的设备"标记的内容，密码管理器用它标记密码，对应 Mac 上 nspasteboard.org 的约定。按微软文档的"Cloud Clipboard and Clipboard History Formats"：剪贴板上有 `ExcludeClipboardContentFromMonitorProcessing`（放了任何数据都算），或者 `CanIncludeInClipboardHistory`、`CanUploadToCloudClipboard` 的值是 0。
  - 剪贴板上的图片：浏览器等应用另放的 PNG 直接用；只有位图时转成 PNG。写入一张图片的位图时，先按照片 EXIF 里的方向转正（解码时不会自动转，手机竖着拍的照片会横过来），再转成不透明的 RGB：带透明度的 BMP 用的文件头 `clipboard-win` 认不了。
  - Windows 没有"一律拒绝应用读剪贴板"的系统设置，"需要允许读取剪贴板"这一行不出现。
- **收到的文件**（docs/prd/send-files.md）：
  - 存进当前用户的"下载"文件夹，不用改。
  - 文件名里有 Windows 不允许的字符（例如 `:`、`?`、`"`）、结尾是点或空格、或者是系统保留的名字（例如 `con.txt`）时，用库 `sanitize-filename` 把这些地方换成下划线，否则存不下来；换完是空的叫"未命名"。只在 Windows 上处理，Mac 上的文件名不变。
- **屏幕参考**（docs/prd/dictation.md）：取前台窗口所属应用的名称和窗口标题，句式和 Mac 一样。应用名取程序文件版本信息里的"文件说明"，没有时用程序的文件名；打不开前台窗口所属的进程（例如它以管理员身份运行）时不附带屏幕参考。不需要权限。用微软官方的 `windows` 库，Tauri 已经带进来同一个版本（0.62）。
- **电脑名**：用主机名，现有代码在 Mac 以外的系统上已经是这样。
- **设置和配对数据**：存在 `%APPDATA%\com.wbbb.echopad.desktop\`，测试包在 `com.wbbb.echopad.desktop.dev` 下。Windows 的用户目录默认只有本人、系统和管理员能访问，密钥文件不另设权限。
- **网络**：
  - 防火墙：电脑端第一次监听端口时，Windows 会弹出防火墙询问，用户要点允许，否则手机端连不上，也发现不了这台电脑端。电脑端装给当前用户、没有管理员权限，所以不在安装时加防火墙规则。README 里写明。
  - 配对码里的地址取的是第一块符合条件的局域网网卡。Windows 上常有虚拟机、WSL 建的虚拟网卡，可能排在真实网卡前面。先在真机上验证；选错时改成取有默认网关的那块网卡。
  - 局域网广播、连接、证书和保活用的库都支持 Windows，不改。
- **应用内更新**（docs/prd/in-app-update.md）：
  - 机制不变。Windows 的更新包就是安装包本身，旁边是它的签名文件；更新清单里对应 `windows-x86_64`。
  - Windows 上执行安装时，更新插件会启动安装器并立刻结束电脑端，安装器装完再打开电脑端。用户在更新弹窗里确认时正是要这个效果；从托盘菜单"退出"时顺手装上的那条路，要关掉插件的"装完重新打开"（`restart_after_install`）。
  - 安装器用插件默认的方式运行：只显示一个进度条，不用用户操作。
  - 开发版和测试包照旧只提示、不安装。
- **安装包**：
  - 用 NSIS 打成一个 `setup.exe`，安装界面是简体中文。
  - 装给当前用户，这是 Tauri 的默认做法：不需要管理员权限，更新时也不弹权限确认。
  - WebView2 用默认的方式处理：系统里没有时由安装器联网下载。Windows 10 1803 起系统自带。
  - 不做代码签名（ADR-0022）。
  - Windows 专属的打包配置写在 `tauri.windows.conf.json` 里，Tauri 会自动合并进主配置。
  - 测试包（`EchoPad Dev`）：`pnpm start` 在 Windows 上照常使用。打包的命令行在 Mac 上只认 Mac 的包类型、在 Windows 上只认 Windows 的，所以包类型不再写在 `pnpm build:dev` 里，改写在各自的配置里：`tauri.macos.conf.json` 只打 `.app`，`tauri.windows.conf.json` 只打 NSIS 安装包。Mac 上的产物不变；发版时仍在命令行里指定包类型。
- **日常检查和发版**（docs/prd/release.md）：
  - 日常检查：电脑端的 job 在 macOS 和 Windows 两种 runner 上各跑一遍类型检查、前端构建和 `cargo test`。步骤里有 bash 的写法，Windows runner 上要指定用 bash。
  - 发版：新增 Windows 打包的 job，在 Windows runner 上打 NSIS 安装包并生成更新包的签名；发布 job 要求 Mac、Windows、安卓三个都成功。
  - 更新包的签名用现有的 Tauri 更新签名私钥，不需要新的 Secret；缺少这把私钥时和 Mac 一样在打包前失败。Windows 安装包不做代码签名（ADR-0022），所以不设代码签名的门禁；`docs/prd/release.md` 里"缺少签名凭证就失败、不发出没签名的包"那条用户故事要改成只针对 Mac 和安卓。
  - 更新清单 `latest.json` 的 `platforms` 里加上 `windows-x86_64`。生成清单要同时拿到 Mac 和 Windows 两个更新包的签名，所以从 Mac 打包的 job 挪到发布 job。
  - 进 Release 的文件名不含空格，Windows 安装包的名字是 `EchoPad_X.Y.Z_x64-setup.exe`。
- **换行符**：仓库根的 `.gitattributes` 规定文本文件在所有系统上都用 LF，避免在 Windows 上拉代码后脚本和测试因换行符出问题。`mobile/gradlew.bat` 一直是按 CRLF 存的，保持原样。
- **文档**：各功能 PRD 里原来写着"Windows 版再做"的地方、`docs/prd/release.md` 和 `docs/prd/in-app-update.md` 里只提到 Mac 的地方、ADR-0017、`DESIGN.md` 的电脑端一节、`README.md`（Windows 上的开发环境、设置文件的位置、防火墙提示、安装时的警告）都随代码改了。更新日志在发版前由维护者说"写更新日志"时再写。

## Testing Decisions

- 电脑端现有的单元测试和集成测试要在 Windows runner 上全部通过。有一条电脑端集成测试用了带双引号的文件名，Windows 上存不下来，要按系统区分期望的文件名。
- 新增单元测试：指针位置换成绝对坐标后，每个像素都落回自己，超出虚拟桌面的贴着边；缩放量攒成档数；更新清单里同时有 Mac 和 Windows 两项；Windows 上不允许的文件名（这一条只在 Windows 上跑）。滚动的换算只是乘一个系数，不单独测。
- 剪贴板、屏幕参考、模拟键鼠直接调用系统接口，不写自动测试，和 Mac 一样靠真机验证。
- 真机验证：
  - 安装包装得上，第一次启动弹出防火墙询问，点允许后手机能配对、能连上。
  - 触控板的各种手势；指针速度和滚动手感与 Mac 接近；在不同缩放比例的显示器上一样快。
  - 多显示器下指针能正常跨屏。
  - 装了虚拟机或 WSL 的电脑上，配对码里的地址没有选错。
  - 电脑忙（例如正在编译）时指针不卡顿。Mac 上为此向系统声明了不进入 App Nap，Windows 上先验证，卡顿时再处理。
  - 打字、特殊键、媒体键、修饰键、快捷操作，包括非美式键盘布局。
  - 语音输入和屏幕参考。
  - 剪贴板同步两个方向；密码管理器复制的内容不同步。
  - 发送文件两个方向：收到的文件能在资源管理器和聊天软件里粘贴，图片能粘贴到只认图片的地方；在资源管理器里复制的文件、截图能发到手机；拖文件进主窗口。
  - 托盘图标在深色和浅色任务栏上都看得清；关掉窗口后任务栏上没有按钮；再次打开 EchoPad 时显示已有的窗口。
  - 系统通知和确认对话框。
- 维护者冒烟：发出第一个带 Windows 安装包的版本后，在一台没装过的 Windows 电脑上安装；再发一版，确认能在应用内更新、从托盘退出时也能顺手装上，更新后配对仍在。

## Out of Scope

- 用模拟触控板做的原生捏合缩放。微软给 Windows 11 新增了公开的模拟触控板接口（`CreateSyntheticPointerDevice2`），能做到和笔记本触控板一样平滑、以指针为中心；但文档还标着预发布，Windows 10 和较早的 Windows 11 没有，这次也没有条件验证。以后单独做，在已有的 Ctrl+滚轮之上加，不影响现有的做法。
- 代码签名（ADR-0022）。
- ARM 版 Windows 的原生安装包。
- MSI 安装包、上架 Microsoft Store。
- 开机自动启动，Mac 版也没有。
- Linux 电脑端。
- 控制以管理员身份运行的窗口。

## Further Notes

- 做完时验证到的程度：
  - 在 Mac 上用 cargo-xwin 交叉编译通过，包括测试代码，没有警告；交叉打出了 NSIS 安装包（装给当前用户、中文界面）。加密库（aws-lc）不需要另装 NASM 和 CMake。
  - 在 Mac 上跑过新增的单元测试和全部现有测试，Mac 版照常。
  - 没有在 Windows 上运行过：真机验证的各项都没做；Windows 上的自动测试要等日常检查在 Windows runner 上跑。
  - 滚动的换算系数（1.2）和缩放一档的缩放量（0.15）都是估计值。
- 已知的风险：
  - 少数老应用不认不足一格的滚轮量，滚动会一格一格地跳。
  - 配对码里的地址可能选到虚拟网卡（见"网络"）。
  - 电脑忙时指针可能卡顿：Mac 上为此向系统声明了不进入 App Nap，Windows 上还没有对应的处理。
- 第一次发带 Windows 安装包的版本之前，先让 `main` 上 Windows 的日常检查通过：Windows 打包失败时三个平台都发不出去。日常检查只编译和测试、不打包，发版流程里 Windows 打包、收集文件和在发布 job 里生成更新清单这几步，要到推标签时才第一次在 GitHub 上运行；在 Mac 上按发版时的参数交叉打过 Windows 安装包和更新签名，也按发版时的参数打过 Mac 的 `.app` 和 `.dmg`。
- 在 Mac 上交叉编译和打包 Windows 版：装好 `x86_64-pc-windows-msvc` 目标、Homebrew 的 `llvm`、`lld`、`nsis` 和 `cargo-xwin`，把 `llvm` 和 `lld` 的 `bin` 目录加进 `PATH`，然后运行 `pnpm tauri build --runner cargo-xwin --target x86_64-pc-windows-msvc`。终端没有设置区域（`LANG` 为空）时 makensis 会崩溃，要先设成 `en_US.UTF-8`。这只用来检查能不能编译、打包，Tauri 对这种做法只是实验性支持，发版仍在 Windows runner 上打。
- Windows 电脑上的开发环境：Microsoft C++ Build Tools（勾选"使用 C++ 的桌面开发"）、Rust（`stable-msvc` 工具链）、Node.js 22、pnpm 10 和 Git。
- `CLAUDE.md` 是指向 `AGENTS.md` 的符号链接。Windows 上的 Git 默认把符号链接检出成一个只写着目标路径的文本文件；要先打开系统的开发人员模式，并在克隆时加上 `-c core.symlinks=true`，它才是真正的链接。
- `MEMORY.md` 里"改完就打包并装上"只写了 Mac 和安卓的做法。在 Windows 电脑上开发时改完怎么装，到时候和维护者确认后再补上。
- 没签名的安装包第一次运行时，Windows 会弹出警告，要多点一步才能继续安装（ADR-0022）。
