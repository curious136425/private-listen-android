param(
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepQa
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$adb = 'D:\PrivateListen\AndroidSdk\platform-tools\adb.exe'
$jdk = 'D:\PrivateListen\tools\jdk17\jdk-17.0.20+8'
$sdk = 'D:\PrivateListen\AndroidSdk'
$qaPackage = 'com.privatelisten.app.qa'
$testPackage = 'com.privatelisten.app.qa.test'
$runDirectory = [IO.Path]::GetFullPath((Join-Path $projectRoot 'app\build\qa-run'))

if (-not $runDirectory.StartsWith($projectRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'QA 输出目录不在项目内。'
}
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

$env:ADB_LIBUSB = '0'
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = 'D:\PrivateListen\gradle-cache'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB 失败：$($Arguments -join ' ')" }
}

function Get-UiXml {
    & $adb shell uiautomator dump /sdcard/private-listen-qa-window.xml | Out-Null
    if ($LASTEXITCODE -ne 0) { return $null }
    $raw = (& $adb shell cat /sdcard/private-listen-qa-window.xml) -join ''
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    try { return [xml]$raw } catch { return $null }
}

function Tap-Node {
    param(
        [string]$Text = '',
        [string]$Description = '',
        [switch]$Contains,
        [switch]$OnlyIfUnchecked,
        [switch]$RightSide,
        [int]$YOffset = 0
    )
    $document = Get-UiXml
    if ($null -eq $document) { return $false }
    $nodes = $document.SelectNodes('//node')
    foreach ($node in $nodes) {
        $candidate = if ($Description) { [string]$node.'content-desc' } else { [string]$node.text }
        $matches = if ($Contains) { $candidate.Contains($(if ($Description) { $Description } else { $Text })) } else {
            $candidate -eq $(if ($Description) { $Description } else { $Text })
        }
        if (-not $matches) { continue }
        if ($OnlyIfUnchecked -and [string]$node.checked -eq 'true') { return $true }
        $bounds = [string]$node.bounds
        $match = [regex]::Match($bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $match.Success) { continue }
        $left = [int]$match.Groups[1].Value
        $right = [int]$match.Groups[3].Value
        $x = if ($RightSide) { $left + (($right - $left) * 0.84) } else { ($left + $right) / 2 }
        $y = (([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2) + $YOffset
        Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
        return $true
    }
    return $false
}

function Tap-FirstEditable {
    $document = Get-UiXml
    if ($null -eq $document) { return $false }
    $node = $document.SelectSingleNode('//node[contains(@class,"EditText") and @enabled="true"]')
    if ($null -eq $node) { return $false }
    $match = [regex]::Match([string]$node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $match.Success) { return $false }
    $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
    $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
    Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
    return $true
}

function Wait-ForText {
    param([string]$Text, [int]$Seconds = 20)
    $end = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $end) {
        $document = Get-UiXml
        if ($null -ne $document) {
            foreach ($node in $document.SelectNodes('//node')) {
                if ([string]$node.text -eq $Text) { return $true }
            }
        }
        Start-Sleep -Milliseconds 750
    }
    return $false
}

function Unlock-Keyguard {
    Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb shell wm dismiss-keyguard | Out-Null
    Start-Sleep -Milliseconds 500
    $state = ((& $adb shell dumpsys window) | Select-String 'isKeyguardShowing=' | Select-Object -First 1).Line
    if ($state -notmatch 'isKeyguardShowing=true') { return }

    $temporaryPin = [Environment]::GetEnvironmentVariable('PRIVATE_LISTEN_DEVICE_PIN')
    if ([string]::IsNullOrWhiteSpace($temporaryPin) -or $temporaryPin -notmatch '^\d{4,16}$') {
        throw '手机处于锁定状态；请先解锁，或仅在当前 PowerShell 进程临时设置 PRIVATE_LISTEN_DEVICE_PIN。'
    }

    Invoke-Adb shell input swipe 630 2300 630 500 500 | Out-Null
    Start-Sleep -Seconds 1
    foreach ($digit in $temporaryPin.ToCharArray()) {
        if (-not (Tap-Node -Description ([string]$digit))) {
            throw '未能在锁屏数字键盘上找到密码按键。'
        }
        Start-Sleep -Milliseconds 150
    }
    Start-Sleep -Seconds 2
    $state = ((& $adb shell dumpsys window) | Select-String 'isKeyguardShowing=' | Select-Object -First 1).Line
    if ($state -match 'isKeyguardShowing=true') { throw '手机解锁失败。' }
}

function Install-WithHonorConfirmation {
    param([string]$Apk, [string]$Label)
    if (-not (Test-Path -LiteralPath $Apk)) { throw "$Label APK 不存在：$Apk" }
    $stdout = Join-Path $runDirectory "$Label-install-out.txt"
    $stderr = Join-Path $runDirectory "$Label-install-err.txt"
    $process = Start-Process -FilePath $adb -ArgumentList @('install', '-r', '-t', $Apk) `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $deadline = [DateTime]::UtcNow.AddMinutes(4)
    $credentialAttempted = $false
    while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        Tap-Node -Text '继续' | Out-Null
        Tap-Node -Text '已了解此应用未经荣耀应用市场检测，可能存在风险。' -OnlyIfUnchecked | Out-Null
        Tap-Node -Text '继续安装' | Out-Null
        Tap-Node -Text '允许本次安装' -Contains -RightSide | Out-Null
        $focus = ((& $adb shell dumpsys window) | Select-String 'mCurrentFocus=' | Select-Object -First 1).Line
        if (-not $credentialAttempted -and $focus -match 'UnifiedAuthenticationDialogActivity') {
            $temporaryPin = [Environment]::GetEnvironmentVariable('PRIVATE_LISTEN_DEVICE_PIN')
            if (-not [string]::IsNullOrWhiteSpace($temporaryPin) -and $temporaryPin -match '^\d{4,16}$') {
                $credentialAttempted = $true
                & $adb shell input tap 970 1650 | Out-Null
                Start-Sleep -Seconds 2
                & $adb shell input tap 630 1230 | Out-Null
                foreach ($digit in $temporaryPin.ToCharArray()) {
                    $keyCode = 7 + [int][string]$digit
                    & $adb shell input keyevent $keyCode | Out-Null
                    Start-Sleep -Milliseconds 120
                }
                & $adb shell input keyevent 66 | Out-Null
            }
        }
        Start-Sleep -Milliseconds 900
        $process.Refresh()
    }
    if (-not $process.HasExited) {
        $process.Kill()
        throw "$Label 安装超时；请确认手机保持解锁。"
    }
    $output = ((Get-Content -LiteralPath $stdout -Raw -ErrorAction SilentlyContinue) +
        (Get-Content -LiteralPath $stderr -Raw -ErrorAction SilentlyContinue))
    if ($process.ExitCode -ne 0 -or $output -notmatch 'Success') {
        throw "$Label 安装失败：$output"
    }
    Tap-Node -Text '完成' | Out-Null
}

function Add-ZipTextEntry {
    param($Archive, [string]$Name, [string]$Content, [bool]$NoCompression = $false)
    $level = if ($NoCompression) {
        [IO.Compression.CompressionLevel]::NoCompression
    } else {
        [IO.Compression.CompressionLevel]::Optimal
    }
    $entry = $Archive.CreateEntry($Name, $level)
    $stream = $entry.Open()
    try {
        $encoding = [Text.UTF8Encoding]::new($false)
        $bytes = $encoding.GetBytes($Content)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally {
        $stream.Dispose()
    }
}

function New-QaEpub {
    param([string]$Destination)
    Add-Type -AssemblyName System.IO.Compression
    $file = [IO.File]::Open($Destination, [IO.FileMode]::Create, [IO.FileAccess]::ReadWrite)
    try {
        $archive = [IO.Compression.ZipArchive]::new($file, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            $firstBody = (1..40 | ForEach-Object { "这是自动验收第一章的第 $($_) 段。系统应当连续朗读，并保存章节和段内位置。" }) -join ''
            $secondBody = (1..40 | ForEach-Object { "这是自动验收第二章的第 $($_) 段。章节顺序必须正确，锁屏后仍应继续朗读。" }) -join ''
            Add-ZipTextEntry $archive 'mimetype' 'application/epub+zip' $true
            Add-ZipTextEntry $archive 'META-INF/container.xml' @'
<?xml version="1.0"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles>
</container>
'@
            Add-ZipTextEntry $archive 'OPS/book.opf' @'
<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>自动验收书</dc:title><dc:creator>QA 作者</dc:creator>
  </metadata>
  <manifest>
    <item id="c1" href="c1.xhtml"/><item id="c2" href="c2.xhtml"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>
'@
            Add-ZipTextEntry $archive 'OPS/c1.xhtml' "<html xmlns=`"http://www.w3.org/1999/xhtml`"><head><title>第一章</title></head><body><h1>第一章</h1><p>$firstBody</p></body></html>"
            Add-ZipTextEntry $archive 'OPS/c2.xhtml' "<html xmlns=`"http://www.w3.org/1999/xhtml`"><head><title>第二章</title></head><body><h1>第二章</h1><p>$secondBody</p></body></html>"
        } finally {
            $archive.Dispose()
        }
    } finally {
        $file.Dispose()
    }
}

$devices = @((& $adb devices) | Select-String "`tdevice$")
if ($devices.Count -ne 1) { throw "需要且只能连接一台可用 Android 设备，当前为 $($devices.Count) 台。" }
Unlock-Keyguard

if (-not $SkipBuild) {
    & (Join-Path $projectRoot 'gradlew.bat') :app:assembleQa :app:assembleQaAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'QA 构建失败。' }
}

$qaApk = Join-Path $projectRoot 'app\build\outputs\apk\qa\app-qa.apk'
$testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\qa\app-qa-androidTest.apk'
if (-not $SkipInstall) {
    Install-WithHonorConfirmation $qaApk 'qa'
    Install-WithHonorConfirmation $testApk 'qa-test'
}

$instrumentation = (& $adb shell am instrument -w "$testPackage/androidx.test.runner.AndroidJUnitRunner") -join "`n"
if ($LASTEXITCODE -ne 0 -or $instrumentation -notmatch 'OK \(2 tests\)') {
    throw "Room 迁移/备份仪器测试失败：`n$instrumentation"
}

Invoke-Adb shell pm grant $qaPackage android.permission.POST_NOTIFICATIONS | Out-Null
$epub = Join-Path $runDirectory 'private-listen-qa.epub'
New-QaEpub $epub
Invoke-Adb push $epub /sdcard/Download/private-listen-qa.epub | Out-Null
Invoke-Adb -Arguments @('shell', 'monkey', '-p', $qaPackage, '1') | Out-Null
if (-not (Wait-ForText '新建 / 导入' 20)) { throw 'QA 首页未出现。' }
Tap-Node -Text '设置' | Out-Null
if (-not (Wait-ForText '基础设置' 10)) { throw '基础设置页面未出现。' }
Tap-Node -Text '1.2x' | Out-Null
Tap-Node -Text '保存默认设置' | Out-Null
if (-not (Wait-ForText '新建 / 导入' 10)) { throw '默认设置保存后未返回书库。' }
Tap-Node -Text '新建 / 导入' | Out-Null
if (-not (Wait-ForText '导入 EPUB' 10)) { throw '新建页面未出现 EPUB 导入按钮。' }
Tap-Node -Text '导入 EPUB' | Out-Null
Start-Sleep -Seconds 2
if (Tap-Node -Description '搜索') {
    Invoke-Adb shell input text private-listen-qa | Out-Null
    Invoke-Adb shell input keyevent 66 | Out-Null
    Start-Sleep -Seconds 2
}
if (-not (Wait-ForText 'private-listen-qa.epub' 15)) { throw '文件选择器未找到 QA EPUB。' }
Tap-Node -Text 'private-listen-qa.epub' -YOffset -260 | Out-Null
if (-not (Wait-ForText '自动验收书' 30)) { throw 'EPUB 未成功导入并打开。' }
if (-not (Wait-ForText '作者：QA 作者' 5)) { throw 'EPUB 作者未显示。' }
if (-not (Wait-ForText '第一章' 5)) { throw 'EPUB 章节标题未显示。' }
Tap-Node -Text '返回书库' | Out-Null
if (-not (Wait-ForText '自动验收书' 10)) { throw '书库未保存导入书籍。' }
Tap-Node -Text '重命名' | Out-Null
if (-not (Wait-ForText '重命名' 5)) { throw '重命名对话框未出现。' }
if (-not (Tap-FirstEditable)) { throw '未找到重命名标题输入框。' }
Invoke-Adb shell input keyevent 123 | Out-Null
1..20 | ForEach-Object { Invoke-Adb shell input keyevent 67 | Out-Null }
Invoke-Adb shell input text QA-Book | Out-Null
Tap-Node -Text '保存' | Out-Null
if (-not (Wait-ForText 'QA-Book' 10)) { throw '书籍重命名未生效。' }
if (-not (Tap-Node -Text '开始听')) { throw '未找到开始听按钮。' }
Start-Sleep -Seconds 8
$serviceState = (& $adb shell dumpsys activity services $qaPackage) -join "`n"
if ($serviceState -notmatch 'PlaybackService' -or $serviceState -notmatch 'isForeground=true') {
    throw '播放服务未进入前台运行。'
}
$notificationState = (& $adb shell dumpsys notification --noredact) -join "`n"
if ($notificationState -notmatch 'QA-Book' -or $notificationState -notmatch 'actions=4') {
    throw '媒体通知未包含书名和四个控制动作。'
}

& $adb logcat -c
& $adb shell am broadcast -a com.privatelisten.app.qa.action.SIMULATE_AUDIO_CAN_DUCK -p $qaPackage | Out-Null
Start-Sleep -Seconds 2
$duckLog = (& $adb logcat -d -v brief -s PrivateListenPlayback:I '*:S') -join "`n"
if ($duckLog -notmatch 'Transient can-duck focus change; continuing playback') {
    throw '普通通知对应的 can-duck 焦点事件错误地中断了播放。'
}

Invoke-Adb shell input keyevent HOME | Out-Null
Invoke-Adb shell input keyevent 26 | Out-Null
Start-Sleep -Seconds 6
$lockedState = (& $adb shell dumpsys activity services $qaPackage) -join "`n"
if ($lockedState -notmatch 'isForeground=true') { throw '锁屏后前台朗读服务未保持。' }
& $adb logcat -c
& $adb shell am broadcast -a com.privatelisten.app.qa.action.SIMULATE_AUDIO_NOISY -p $qaPackage | Out-Null
Start-Sleep -Seconds 2
$audioLog = (& $adb logcat -d -v brief -s PrivateListenPlayback:I '*:S') -join "`n"
if ($audioLog -notmatch 'QA simulated audio becoming noisy; pausing') {
    throw '耳机断开对应的暂停路径未被触发。'
}

Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb shell wm dismiss-keyguard | Out-Null
Invoke-Adb shell rm -f /sdcard/Download/private-listen-qa.epub | Out-Null

if (-not $KeepQa) {
    & $adb uninstall $testPackage | Out-Null
    & $adb uninstall $qaPackage | Out-Null
}

[Environment]::SetEnvironmentVariable('PRIVATE_LISTEN_DEVICE_PIN', $null, 'Process')

[pscustomobject]@{
    Result = 'PASS'
    Instrumentation = '2/2'
    DefaultSettings = 'PASS'
    EpubImport = 'PASS'
    MetadataAndChapters = 'PASS'
    Rename = 'PASS'
    ForegroundNotificationActions = '4'
    NotificationDoesNotPause = 'PASS'
    LockScreenService = 'PASS'
    AudioBecomingNoisyPause = 'PASS'
}
