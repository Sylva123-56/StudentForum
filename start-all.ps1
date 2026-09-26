$ErrorActionPreference = 'Stop'
$proj = 'E:\StudentForum'

$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:DB_USER = 'root'
$env:DB_PASSWORD = '1234'

Write-Host '启动后端 (mvn spring-boot:run) ...' -ForegroundColor Cyan
Start-Process -FilePath 'powershell.exe' -ArgumentList @(
    '-NoExit','-Command',
    "cd '$proj'; `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; `$env:PATH=`"`$env:JAVA_HOME\bin;`$env:PATH`"; `$env:DB_USER='root'; `$env:DB_PASSWORD='1234'; mvn spring-boot:run"
) -WorkingDirectory $proj

Start-Sleep -Seconds 3

Write-Host '启动前端 (npm run dev) ...' -ForegroundColor Cyan
Start-Process -FilePath 'powershell.exe' -ArgumentList @(
    '-NoExit','-Command',
    "cd '$proj'; npm run dev"
) -WorkingDirectory $proj

Write-Host '两个窗口已启动。关闭对应窗口即可停止服务。' -ForegroundColor Green
Start-Sleep -Seconds 2
