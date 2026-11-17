# Get Jobs — Windows 使用说明

这是独立的干净分发版本，包含当前程序功能。默认不包含任何已登录账号，也不会启动投递任务。接收者需要设置自己的搜索条件、简历和账号。

## 首次准备

使用 Windows 10 / 11 的 64 位系统，先安装：

1. **JDK 21**：从 [Eclipse Temurin 官方下载页](https://adoptium.net/temurin/releases/?version=21) 选择 Windows、x64、JDK 21 的 MSI 安装包。安装时勾选添加 PATH 和设置 JAVA_HOME。
2. **Node.js 22 或 24**：从 [Node.js 官方下载页](https://nodejs.org/en/download) 选择相应版本的 Windows MSI，保留添加 PATH 的默认选项。程序运行时浏览器驱动也需要 Node.js。
3. **Google Chrome 正式版**：从 [Google 官方页面](https://www.google.com/chrome/) 安装。程序通过 Chrome 的独立用户目录运行，不读取日常 Chrome 的登录状态。

安装后重新打开终端。命令 `java -version` 应为 21 或更高，`node --version` 应为 22 或更高。建议使用上面列出的版本。

## 运行包：直接运行

1. **完整解压** `get_jobs-windows-*.zip`，例如放到 `D:\GetJobs\`。不要直接在压缩包预览窗口内启动，也不要放进需要管理员写入权限的 Program Files。
2. 双击 `start.bat`，保持控制台窗口打开。脚本会检查 Java、Node、Chrome、数据库与驱动文件，并给出缺少的依赖。
3. 日志显示应用启动成功后，浏览器打开 **http://localhost:9527**。管理页面和 API 均包含在程序中，不需要另外运行前端，也不需要下载数据库或安装 pnpm。
4. 进入 Boss 页面，用自己的账号扫码登录并确认登录。添加多个账号时，分别登录。
5. 设置自己的城市、搜索关键词、薪资、HR 活跃时间、黑名单及精投方向。初始关键词为空，薪资不限，黑名单为空，默认 HR 近 3 天活跃。精投方向保留通用示例，接收者应改成自己的目标，也可以上传自己的简历解析。
6. 使用 AI 精投审核、简历解析或 AI 招呼语前，先在“环境变量配置”页面填写自己的 **BASE_URL、API_KEY、MODEL**。这些值最初全部为空；精准审核与 AI 招呼语是两个独立开关。没有配置 AI 时，请关闭精准审核及 AI 招呼语后使用普通过滤；如要精投，先配置 AI。个人介绍为空，不会冒用他人的经历。
7. 图片简历发送、重复沟通、消息通知默认关闭。需要时上传自己的简历并启用相关设置。确认好配置后，由接收者在管理页面主动点击开始投递。

停止投递先点管理页面的停止；关闭整个程序在启动窗口按 **Ctrl+C**，等待退出。不要重复启动多个实例。

需要无窗口投递时，先在普通模式完成登录，再在 PowerShell 中执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-windows.ps1 -Headless
```

无窗口模式遇到登录过期或验证码，需要停止后重新用 `start.bat` 登录。只检查依赖而不启动程序：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-windows.ps1 -CheckOnly
```

脚本的 ExecutionPolicy Bypass 只作用于这次 PowerShell 进程，不会修改系统的执行策略。

## 源码包：修改与重新构建

`get_jobs-source-*.zip` 包含后端、前端、测试、Gradle Wrapper、公开筛选字典、新建的空白业务数据库，以及已编译的管理页面；不包含 Git 历史、依赖缓存、浏览器登录资料或任何运行记录。

解压源码包后，安装上述依赖，在源码根目录 PowerShell 中执行：

```powershell
.\gradlew.bat bootRun
```

首次运行会从网络下载 Gradle、Java 依赖和 Patchright 驱动。源码包已带管理页面，因此直接运行无需先构建前端。不要把项目分享者的数据库或 Cookie 复制过来。

如需修改前端，安装 pnpm 11，在源码根目录执行：

```powershell
npm install --global pnpm@11
pnpm --dir front install --frozen-lockfile
.\gradlew.bat buildFrontend -PrebuildFrontend
```

`buildFrontend` 使用 webpack 构建并复制到后端静态资源目录；重新启动后生效。生成后端 JAR：

```powershell
.\gradlew.bat bootJar
```

单独复制 JAR 不够：运行时还需要 `db\getjobs.db`、Patchright 驱动、Node.js 和 Chrome。运行包已提供前两项以及配置好的启动脚本。

## 数据与再分享

首次分发数据库由公开表结构重新建立，只预装城市、薪资等公共选项和空白服务配置；默认页面配置由程序首次启动初始化。原始公开模板来自 [上游 v2.0.1 Release](https://github.com/loks666/get_jobs/releases/tag/v2.0.1)，不使用分享者本地数据库。

使用后，接收者自己的资料会存入 `db`、`browser-data`、`browser-accounts`、`cookies`、`data`、`target` 等运行目录。这些目录可能包含登录态、简历、投递记录、AI 缓存或日志。**以后再分享时使用原始干净 ZIP 或重新生成分发包，不要压缩已经使用过的运行目录。**

`LICENSE` 保留项目原有许可。`MANIFEST.sha256` 用于核对包内文件；压缩包同目录的 `SHA256SUMS.txt` 用于核对 ZIP。

## 常见问题

- **缺少 Java / Node / Chrome**：安装后重新打开启动窗口；确认环境变量指向正确版本。
- **9527 端口占用**：先停止原实例。不要随意结束其他 Java 程序。程序前端 API 地址按 9527 构建，普通使用无需改端口。
- **启动报无法写数据库**：完整解压到当前用户可写入的本地目录。
- **管理页面能打开，AI 功能失败**：检查接收者自己的 API 地址、密钥、模型和余额；程序未提供任何可用密钥。
- **扫码或投递出现验证码**：由接收者处理登录验证后继续。网站页面变更或账号限制仍可能影响运行。
- **构建下载失败**：源码的 Gradle 配置不预设本地代理。需要代理时使用接收者自己可用的代理配置。

本分发包已在隔离目录做干净数据库启动及功能接口检查；制作环境为 macOS，Windows 原生环境及扫码后真实投递由接收者首次使用时验证。
