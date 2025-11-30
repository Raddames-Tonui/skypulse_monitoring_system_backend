# ------------------------------
# Start SkyPulse Java Application
# ------------------------------

# 1. Load .env from parent folder into process environment
$envFile = "..\.env"

if (Test-Path $envFile) {
Get-Content $envFile | ForEach-Object {
if ($_ -match "^\s*([^#][^=]*)=(.*)$") {
[System.Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), "Process")
}
}
Write-Host "✅ Loaded .env from $envFile"
} else {
Write-Warning ".env file not found at $envFile"
}

# 2. Locate JAR file in current folder
$jarFile = Get-ChildItem -Path . -Filter "*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not $jarFile) {
Write-Error "❌ No JAR file found in $(Get-Location)"
exit 1
}

Write-Host "✅ Found JAR: $($jarFile.Name)"

# 3. Check config.xml in parent folder
$configFile = "..\config.xml"
if (-not (Test-Path $configFile)) {
Write-Warning "⚠ Config file not found at $configFile, using default 'config.xml' in JAR folder"
$configFile = ".\config.xml"
}

# 4. Start Java application
Write-Host "🚀 Starting application..."
Start-Process "java" "-jar `"$($jarFile.FullName)`" `"$configFile`"" -NoNewWindow -Wait
