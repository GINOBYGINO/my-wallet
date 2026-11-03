# 啟動 PC 暫代前端：http://127.0.0.1:8787
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

$Backup = "C:\Users\Gino\Documents\mywallet-backup\20261015-212057\user10\my_wallet_merged.db"
$Working = Join-Path $Root "data\working.db"

if (-not (Test-Path $Working)) {
    if (-not (Test-Path $Backup)) {
        Write-Error "找不到備份：$Backup"
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $Root "data") | Out-Null
    Copy-Item $Backup $Working
    Write-Host "已從備份建立 working.db"
}

python -m pip install -r requirements.txt -q
Write-Host "開啟 http://127.0.0.1:8787"
python -m uvicorn server.main:app --host 127.0.0.1 --port 8787
