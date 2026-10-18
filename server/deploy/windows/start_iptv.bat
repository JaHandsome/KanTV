@echo off
rem iptv-nas Windows 启动脚本（开发机 / Windows NAS）
rem 常驻运行：python app\main.py（HTTP :8420 + 调度器）
rem 建议：用"任务计划程序"配置开机启动 + 每分钟存活检查拉起（见 deploy\nas_task.md）

cd /d "%~dp0.."
where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] 未找到 python, 请先安装 Python 3.8+
    pause
    exit /b 1
)
echo starting iptv-nas ...
python app\main.py
rem 异常退出后 10 秒自动重启（简易守护；生产建议用任务计划程序）
if errorlevel 1 (
    echo [WARN] 进程退出, 10 秒后重启...
    timeout /t 10 /nobreak >nul
    start "" "%~f0"
)
