$ErrorActionPreference = "Stop"
$java = "C:\Program Files\Java\jdk-25\bin\java.exe"
$javac = "C:\Program Files\Java\jdk-25\bin\javac.exe"

if (-not (Test-Path "out")) { New-Item -ItemType Directory -Path "out" | Out-Null }

$sources = Get-ChildItem -Path "src" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
Write-Host "Compiling Java sources..."
& $javac -d out -cp "lib/h2-2.3.232.jar" $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Starting Smart File Management System..."
& $java -cp "out;lib/h2-2.3.232.jar" com.smartfms.Main
