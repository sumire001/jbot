# ichikaBot 前端预览服务启动脚本
# 用法: 在 powershell 中执行  .\preview\start.ps1
#       也可带端口: .\preview\start.ps1 -Port 8080
param(
    [int]$Port = 3000
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$rootDir = Split-Path -Parent $scriptDir   # konztai_ichikabot/

Write-Host "启动 ichikaBot 前端预览服务..." -ForegroundColor Cyan

# 检查 Node.js
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host "未检测到 Node.js，请先安装 Node.js (https://nodejs.org/)" -ForegroundColor Red
    exit 1
}

# 切换到项目根目录并启动服务
Set-Location $rootDir
$env:PORT = $Port
node "$scriptDir\server.js" --port=$Port
