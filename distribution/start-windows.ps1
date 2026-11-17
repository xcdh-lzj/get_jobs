param([switch]$Headless, [switch]$CheckOnly)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
Set-Location -LiteralPath $PSScriptRoot

try {
    $javaBinary = $null
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
        $javaBinary = "$env:JAVA_HOME\bin\java.exe"
    } else {
        $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($javaCommand) { $javaBinary = $javaCommand.Source }
    }
    if (-not $javaBinary) { throw '未找到 Java。请先安装 JDK 21，并勾选添加到 PATH / 设置 JAVA_HOME，然后重新打开启动窗口。' }
    # Java writes -version to stderr. Capture it without PowerShell 5.1 treating
    # a successful version check as a terminating NativeCommandError.
    $versionInfo = New-Object System.Diagnostics.ProcessStartInfo
    $versionInfo.FileName = $javaBinary
    $versionInfo.Arguments = '-version'
    $versionInfo.UseShellExecute = $false
    $versionInfo.RedirectStandardOutput = $true
    $versionInfo.RedirectStandardError = $true
    $versionInfo.CreateNoWindow = $true
    $versionProcess = [System.Diagnostics.Process]::Start($versionInfo)
    $javaVersion = $versionProcess.StandardOutput.ReadToEnd() + $versionProcess.StandardError.ReadToEnd()
    $versionProcess.WaitForExit()
    if ($versionProcess.ExitCode -ne 0) { throw 'Java 版本检查失败。' }
    $versionProcess.Dispose()
    if ($javaVersion -notmatch 'version "(\d+)') { throw '无法识别 Java 版本，请安装 JDK 21。' }
    if ([int]$Matches[1] -lt 21) { throw '当前 Java 低于 21，请安装 JDK 21 并调整 JAVA_HOME / PATH。' }

    $nodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
    if (-not $nodeCommand) { throw '未找到 Node.js。请先安装 Node.js 22 或 24，并重新打开启动窗口。' }
    $nodeBinary = $nodeCommand.Source
    $nodeVersion = (& $nodeBinary --version | Out-String).Trim()
    if ($nodeVersion -notmatch '^v(\d+)\.') { throw '无法识别 Node.js 版本。' }
    if ([int]$Matches[1] -lt 22) { throw 'Node.js 版本过低，请安装 Node.js 22 或 24。' }

    $chromeCandidates = @(
        "${env:ProgramFiles}\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
        "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
    )
    $chromeBinary = $chromeCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $chromeBinary) { throw '未在标准安装目录找到 Google Chrome。请安装正式版 Chrome；Edge 不能替代该依赖。' }

    $driverDirectory = Join-Path $PSScriptRoot 'runtime\patchright-driver'
    foreach ($required in @('get_jobs.jar', 'db\getjobs.db', 'runtime\patchright-driver\package\cli.js')) {
        if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $required))) {
            throw "缺少文件：$required。请完整解压运行包后再启动。"
        }
    }
    Write-Host "Java: $javaBinary"
    Write-Host "Node: $nodeVersion"
    Write-Host "Chrome: $chromeBinary"
    if ($CheckOnly) { Write-Host '环境检查通过。'; exit 0 }

    $listener = Get-NetTCPConnection -State Listen -LocalPort 9527 -ErrorAction SilentlyContinue
    if ($listener) { throw '9527 端口已被占用。请先关闭原来的 Get Jobs 实例，再启动本程序。' }

    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:PLAYWRIGHT_NODEJS_PATH = $nodeBinary
    $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
    # 默认使用窗口模式，首次启动由接收者扫码登录自己的账号。
    $env:GETJOBS_BROWSER_HEADLESS = if ($Headless) { 'true' } else { 'false' }
    Write-Host ''
    Write-Host '服务启动后，打开 http://localhost:9527 进入管理页面。'
    Write-Host '本窗口需要保持运行；按 Ctrl+C 停止程序。启动不会自动开始投递。'
    Write-Host ''
    & $javaBinary '-Dfile.encoding=UTF-8' '-Djava.net.preferIPv4Stack=true' `
        "-Djdk.net.unixdomain.tmpdir=$env:SystemRoot\Temp" `
        "-Dplaywright.cli.dir=$driverDirectory" '-jar' (Join-Path $PSScriptRoot 'get_jobs.jar')
    exit $LASTEXITCODE
} catch {
    Write-Host ("启动失败：" + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
