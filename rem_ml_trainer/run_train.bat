@echo off
chcp 65001 >nul
echo =======================================================
echo  FlashAlarm REM 模型训练与代码导出 - 一键运行助手
echo =======================================================
echo.

set PYTHON_EXE=C:\Python314\python.exe

if not exist "%PYTHON_EXE%" (
    echo [!] 未在 C:\Python314 找到 python.exe，请检查 Python 安装路径。
    pause
    exit /b 1
)

echo [+] 检测到 Python: %PYTHON_EXE%
echo [+] 正在确认环境依赖包...
"%PYTHON_EXE%" -m pip install -r "%~dp0requirements.txt"
if errorlevel 1 (
    echo [!] 依赖包安装出现问题，请检查网络。
    pause
    exit /b 1
)

echo.
echo =======================================================
echo 请选择运行模式：
echo  1. 快速尝鲜测试（自动从 PhysioNet 下载 1 位受试者样本并导出 Java 代码）
echo  2. 全量数据集训练（需已手动下载解压 550MB sleep-accel 文件夹）
echo =======================================================
set /p choice="请输入数字 (1 或 2，默认为 1): "

if "%choice%"=="2" (
    echo.
    set /p datadir="请输入解压后的 sleep-accel 文件夹完整路径: "
    echo [+] 开始全量训练...
    "%PYTHON_EXE%" "%~dp0train_rem_model.py" --data-dir "%datadir%"
) else (
    echo.
    echo [+] 开始快速样本下载与测试运行...
    "%PYTHON_EXE%" "%~dp0train_rem_model.py" --download-sample --sample-count 1
)

echo.
echo =======================================================
echo 执行完毕！生成的代码已存放于:
echo app\src\main\java\com\flashalarm\miband\domain\algorithm\
echo =======================================================
pause
