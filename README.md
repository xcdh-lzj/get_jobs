# Get Jobs 二次开发版

本项目基于 **[loks666/get_jobs](https://github.com/loks666/get_jobs)** 进行二次开发，感谢原作者和贡献者。此仓库为独立维护的修改版本，与上游官方发布相区分。

保留上游完整 [LICENSE](LICENSE) 和版权声明，继续遵守 **PolyForm Noncommercial License 1.0.0**。本项目仅供非商业用途；公开源码不代表获得商业授权。

## 主要改进

- Boss 多账号独立配置、登录资料隔离，以及按顺序执行的账号队列。
- 识别当日沟通上限后结束当前账号；处理剩余沟通次数提醒。
- 同账号按顺序处理多个城市，支持低产出判断和切换阈值。
- 从目标方向和简历生成精投配置，结合标题初筛与岗位职责语义审核。
- AI 审核缓存与调用预算控制，减少重复分析和 Token 消耗。
- 可自定义薪资区间、HR 活跃天数，支持账号间复制黑名单。
- 管理页实时进度、分区布局、可收起侧边栏、统一保存入口和本地岗位详情。
- 修复分析图表随账号轮询反复重绘的问题。

这批增强主要面向 Boss；其他平台保留原有代码，实际可用性取决于对应平台页面和上游维护情况。

## 环境要求

- JDK 21，并配置 `JAVA_HOME`。
- Node.js 22 或 24、pnpm 11。
- Python 3.11 或更新版本，用于从公开数据初始化空白数据库。
- Google Chrome 正式版、Git。

浏览器默认以**有窗口模式**运行，扫码登录在独立浏览器资料目录内完成。程序启动后，由使用者在管理页面主动开始投递。

## 从源码运行

先克隆本仓库并进入项目根目录。首次使用安装 pnpm：

```text
npm install --global pnpm@11
```

macOS / Linux：

```bash
python3 scripts/init_database.py
pnpm --dir front install --frozen-lockfile
pnpm --dir front build:prod
sh ./gradlew bootRun
```

Windows PowerShell：

```powershell
py -3 scripts/init_database.py
pnpm --dir front install --frozen-lockfile
pnpm --dir front build:prod
.\gradlew.bat bootRun
```

`init_database.py` 只用仓库中的公开表结构和城市、薪资等选项创建 `db/getjobs.db`，拒绝覆盖已有数据库。已有数据库的安装无需重复初始化。首次构建需要下载 Gradle、Java 和浏览器驱动依赖。

启动成功后打开 **http://localhost:9527**。前端已复制到后端静态资源目录，常规使用无需再运行一个前端开发服务器。

1. 设置自己的城市、关键词、薪资、HR 活跃范围和精投方向。
2. 在 Boss 页面打开对应账号浏览器，扫码登录并确认保存登录；多账号分别操作。
3. 使用 AI 审核、简历解析或 AI 招呼语前，在“服务与通知”填写自己的 API 地址、密钥和模型。仓库未提供可用的 API 密钥。
4. 填写自己的个人介绍，需要时上传自己的简历；保存全部修改后再开始投递。
5. 停止任务使用管理页停止按钮，退出程序在启动终端按 `Ctrl+C`。

默认关键词、个人介绍、黑名单和服务凭据均为空，图片简历发送及重复沟通默认关闭。精投方向中的通用示例可自行修改。AI 审核和 AI 招呼语分别配置；未配置服务时应先关闭相关功能。

## 修改与测试

```text
pnpm --dir front test:progress
python3 -m unittest discover -s scripts -p "test_*.py" -v
```

后端测试与打包：

```bash
# macOS / Linux
sh ./gradlew test bootJar -x buildFrontend
```

```powershell
# Windows
.\gradlew.bat test bootJar -x buildFrontend
```

修改前端后重新运行 `pnpm --dir front build:prod`。调试管理页可在 `front` 内执行 `pnpm dev`；API 默认连接本地 9527 端口。

## 公开仓库与本地资料

首次公开发布采用全新的提交历史，历史开发分支与回滚点保留在维护者本机。源码不包含原维护环境的数据库、浏览器登录态、Cookie、简历、投递记录、AI 缓存、日志、截图或旧 Git 历史。测试中的 HR 活跃样本为合成数据。

文档图片只保留与上游公开文件哈希一致的素材，不包含本机运行截图。

使用后生成的 `db/`、`data/`、`browser-data/`、`browser-accounts/`、`cookies/`、`target/` 和 `.env` 等属于本地资料，已加入忽略规则。环境变量文件仅保留在本地；服务凭据通过管理页面设置并保存在数据库中。

## 配置如何保存

| 内容 | 存放位置 | 提交 Git |
| --- | --- | --- |
| 通用程序配置与空白初始值 | `src/main/resources/application.yaml`、`defaults/page-config.json` | 是 |
| 个人初始化资料 | `config/page-config.local.json` | 否 |
| 本机程序配置 | `config/application-local.yaml` | 否 |
| 本机 Gradle 代理 | `config/gradle-local.properties` | 否 |
| 管理页面保存的账号、筛选、介绍、服务凭据 | `db/getjobs.db` | 否 |
| 登录态、简历、投递历史与日志 | 各运行目录 | 否 |

日常通过管理页面保存配置即可，数据库是实际运行配置的来源。可选的 `config/application-local.yaml` 会由程序启动时读取；本机 Gradle 配置支持 `systemProp.*`，命令行 `-D` 参数优先。

若需要给全新数据库预置自己的页面设置，可复制通用默认 JSON 为 `config/page-config.local.json` 并编辑，再在本机 `config/application-local.yaml` 中指定：

```yaml
getjobs:
  defaults-file: ./config/page-config.local.json
```

个人初始化文件只对首次初始化的空白数据库生效，已保存的页面配置不受覆盖；无需在数据库和 JSON 中重复维护日常设置。

提交前执行隐私检查：

```text
python3 scripts/check_publication.py
```

检查针对当前暂存区，包括凭据、本机身份、路径、未批准的二进制文件和运行数据；扫描报告写入已忽略的 `target/privacy-audit.json`，只列路径与规则名。发布时仅推送公开分支，不推送本机备份标签或历史开发分支。

可用 `git config core.hooksPath .githooks` 启用提交前检查和中文提交格式。维护者本机还可将 `getjobs.publicRoot` 配置为公开历史的根提交，使推送钩子拒绝包含旧私密历史的分支或标签。
