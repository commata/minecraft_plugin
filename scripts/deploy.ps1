<#
.SYNOPSIS
  플러그인을 빌드해서 미니PC로 올리고 운영 서버에 설치합니다.

.EXAMPLE
  .\scripts\deploy.ps1 -Plugin custom-items -DryRun     # 무엇을 할지만 확인 (서버 영향 없음)
  .\scripts\deploy.ps1 -Plugin custom-items -UploadOnly # 빌드 + 업로드만, 설치는 나중에
  .\scripts\deploy.ps1 -Plugin custom-items             # 빌드 + 업로드 + 백업 + 설치 + 재시작
  .\scripts\deploy.ps1 -Plugin travel -DryRun           # /home + /tpa. 서버의 예전 Home JAR 도 교체 대상으로 표시됨
#>
param(
    [string]$Plugin = "custom-items",
    [switch]$UploadOnly,
    [switch]$DryRun,
    [int]$Delay = 30,
    [string]$SshHost = "minipc"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if ($Plugin -eq "home") {
    throw "home 플러그인은 travel 로 합쳐졌습니다. -Plugin travel 로 배포하세요 (서버의 예전 Home JAR 은 자동으로 교체됩니다)."
}
if (-not (Test-Path "$root\plugins\$Plugin")) {
    throw "플러그인 폴더가 없습니다: plugins\$Plugin"
}

function Invoke-Checked([string]$what, [scriptblock]$cmd) {
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "$what 실패 (exit $LASTEXITCODE)" }
}

Write-Host "== 1. 빌드: $Plugin" -ForegroundColor Cyan
Push-Location $root
try {
    Invoke-Checked "빌드" { & .\gradlew.bat ":plugins:${Plugin}:build" }
} finally {
    Pop-Location
}

$jar = Get-ChildItem "$root\plugins\$Plugin\build\libs\*.jar" |
    Where-Object { $_.Name -notlike "*-plain.jar" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { throw "빌드된 JAR 을 찾지 못했습니다: plugins\$Plugin\build\libs" }
Write-Host "   JAR: $($jar.Name)"

Write-Host "== 2. 업로드: $SshHost" -ForegroundColor Cyan
Invoke-Checked "폴더 준비" { ssh $SshHost "mkdir -p ~/plugin-staging ~/mc-scripts" }
$scripts = Get-ChildItem "$root\scripts\server\*.sh" | ForEach-Object { $_.FullName }
Invoke-Checked "스크립트 업로드" { scp @scripts "${SshHost}:mc-scripts/" }
Invoke-Checked "JAR 업로드" { scp $jar.FullName "${SshHost}:plugin-staging/" }
Invoke-Checked "스크립트 권한" { ssh $SshHost "sed -i 's/\r$//' ~/mc-scripts/*.sh && chmod +x ~/mc-scripts/*.sh" }

if ($UploadOnly) {
    Write-Host "업로드만 완료. 설치하려면: ssh $SshHost '~/mc-scripts/install-plugin.sh ~/plugin-staging/$($jar.Name)'" -ForegroundColor Green
    exit 0
}

Write-Host "== 3. 설치" -ForegroundColor Cyan
$installArgs = "--delay $Delay"
if ($DryRun) { $installArgs += " --dry-run" }
Invoke-Checked "설치" { ssh -t $SshHost "~/mc-scripts/install-plugin.sh $installArgs ~/plugin-staging/$($jar.Name)" }
Write-Host "완료" -ForegroundColor Green
