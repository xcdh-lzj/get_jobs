# 重新制作干净分发包

这份说明供维护者在 Git 工作区内重新打包使用。接收者直接运行 Windows 包，不需要 Python、Git 或执行这些步骤。

打包工具按文件清单导出源码，并覆盖通用默认值、测试样本和本地代理配置；SQLite 文件从本目录的公开表结构和筛选字典重新生成。它不会复制工作区的数据库、浏览器目录、日志、简历或 Git 历史。它只在内存中读取本地凭据作泄漏比对，不把这些值写进报告。

`defaults/schema.sql` 和 `defaults/options.json` 来自公开的 [v2.0.1 数据库模板](https://github.com/loks666/get_jobs/releases/tag/v2.0.1)。原模板 SHA-256：`81e837df423f1dcf06c89f430493a244bdb17f46d7eab6b22afaec94675e1c97`。只提取表结构、索引与四个平台选项表的 `type/name/code/sort_order`，不保留原模板其他行或时间戳。

## 步骤

1. 确认当前代码已经完成验证，安装 JDK 21、Node.js 22/24、pnpm 11、Python 3.11+、Google Chrome。
2. 在 `front` 中安装依赖，执行 `node node_modules/next/dist/bin/next build --webpack`，再执行 `node scripts/copy-dist.mjs`，生成最新管理页面。
3. 在项目根目录运行 Gradle 的 `installPatchrightDriver` 任务，确保 `build/patchright-driver/.version` 是 `1.62.1`。
4. 在根目录运行以下命令，每次使用新的输出目录：

```text
python3 -m unittest discover -s scripts -p test_package_share.py -v
python3 scripts/package_share.py prepare --label clean --output target/share/clean
```

5. 进入 `target/share/clean/get_jobs-source-clean`，从脱敏源码编译并测试：

```text
# Windows
gradlew.bat bootJar test -x buildFrontend
# macOS / Linux
sh ./gradlew bootJar test -x buildFrontend
```

6. 用新数据库的副本，在隔离目录检查 JAR 启动、管理页面、空白账号、筛选配置与未启动的任务状态。不要对外发送真实消息，不要用原数据库做验证，也不要直接启动待分发的数据库；运行产生的登录态和数据不应进包。浏览器 DOM 测试使用模拟页面，开启时还需要按项目测试配置设置本机 Chrome 和驱动路径。
7. 回到原工作区根目录完成打包：

```text
python3 scripts/package_share.py finish --output target/share/clean --jar target/share/clean/get_jobs-source-clean/build/libs/get_jobs-2.0.1.jar
```

工具会检查 JAR 的资源范围、前端页面、通用默认配置、数据库中所有非公开业务表是否为空、本地已知私密值与本机路径是否出现在文件中，并生成两个 ZIP、每包的 `MANIFEST.sha256`、压缩包的 `SHA256SUMS.txt` 和本地 `privacy-audit.json`。

最终只封装 `prepare` 时记录的文件以及指定 JAR；在暂存源码中构建产生的 Gradle 缓存、测试报告、Node 依赖等不进入压缩包。所有导出路径必须位于 `target/share`，已有输出目录或数据库不会被覆盖。

## 维护注意

- 新增平台、表结构、资源文件或修改测试默认值后，复核导出清单和覆盖逻辑；不确定的内容应先审查，再加入允许清单。
- 不要直接把原数据库复制后删表再分发；SQLite 的空闲页、WAL、备份仍可能保留旧数据。
- 运行包仍依赖接收者自己的 Java、Node.js 和 Chrome；它没有附带这些软件的安装程序。
- macOS 上的验证不能替代 Windows 原生验证，报告保留这一限制。不要把真实扫码投递作为打包自动测试。
