@echo off
chcp 65001 >nul
title FlashAlarm REM Trainer

set PYTHON_EXE=C:\Python314\python.exe

if not exist "%PYTHON_EXE%" (
    set PYTHON_EXE=python
)

echo =======================================================
echo  FlashAlarm REM 模型训练与代码导出
echo =======================================================
echo.
echo 请选择运行模式：
echo  1. 快速尝鲜测试 (PhysioNet 样本)
echo  2. 1Hz 手环模型全量训练 (sleep-accel)
echo  3. 真 HRV 模型训练 (PhysioNet slpdb + AD8232/ESP32-C3)
echo  4. 【推荐】PAAWS R2 黄金双模态全量训练 (真心电 HRV + 腕部三轴体动)
echo =======================================================
set /p choice=请输入数字 (1, 2, 3 或 4，默认为 4): 

if "%choice%"=="1" goto run_sample
if "%choice%"=="2" goto run_full_1hz
if "%choice%"=="3" goto run_hrv
goto run_paaws

:run_sample
echo.
echo [+] 启动快速样本训练...
"%PYTHON_EXE%" "%~dp0train_rem_model.py" --download-sample --sample-count 1
goto end

:run_full_1hz
echo.
set /p datadir=请输入解压后的 sleep-accel 文件夹完整路径: 
echo [+] 启动 1Hz 全量手环模型训练...
"%PYTHON_EXE%" "%~dp0train_rem_model.py" --data-dir "%datadir%"
goto end

:run_hrv
echo.
echo [+] 启动真 HRV 毫秒心电模型训练 (PhysioNet slpdb)...
"%PYTHON_EXE%" "%~dp0train_true_hrv_model.py"
goto end

:run_paaws
echo.
set paawsdir=F:\paaws_r2_raw
if not exist "%paawsdir%" (
    set /p paawsdir=未找到 F:\paaws_r2_raw，请输入 PAAWS R2 完整路径: 
)
echo [+] 启动 PAAWS R2 黄金双模态全量训练...
"%PYTHON_EXE%" -u "%~dp0train_paaws_rem_model.py" --data-dir "%paawsdir%"
goto end

:end
echo.
echo =======================================================
echo 执行完毕！生成的代码已存放于:
echo app\src\main\java\com\flashalarm\miband\domain\algorithm\
echo =======================================================
pause
