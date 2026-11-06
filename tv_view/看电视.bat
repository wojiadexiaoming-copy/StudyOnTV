@echo off
chcp 936 >nul
setlocal

rem ============================================================
rem  ����Ͷ�� + ���Բ������ӣ�scrcpy һ�������
rem  �÷���˫�����ļ����رջ��洰�ڼ��Ͽ��������ϲ����κζ�����
rem  ���� IP ����ֻ��Ҫ��������һ�С�
rem ============================================================
set "TV_IP=192.168.1.50"
set "TV_PORT=5555"
set "SERIAL=%TV_IP%:%TV_PORT%"

set "ADB=%~dp0adb.exe"
set "SCRCPY=%~dp0scrcpy.exe"

title ������ %SERIAL%

if not exist "%ADB%" goto no_adb
if not exist "%SCRCPY%" goto no_scrcpy

echo ============================================================
echo   ����Ͷ����%SERIAL%
echo ============================================================
echo.
echo [1/3] ���ӵ��� ...
"%ADB%" connect %SERIAL%
echo.

echo [2/3] �ȴ�������Ӧ����� 20 �룩...
set "READY="
for /l %%i in (1,1,20) do (
  if not defined READY (
    "%ADB%" -s %SERIAL% shell echo ok >nul 2>&1
    if not errorlevel 1 set "READY=1"
    if not defined READY (
      <nul set /p "=."
      ping -n 2 127.0.0.1 >nul
    )
  )
)
echo.
if not defined READY goto not_ready

echo [3/3] �򿪻��洰�ڣ��״�Լ 2 �룩...
echo.
echo ------------------------------------------------------------
echo  ���� / ���������ӣ��ȵ�һ�´��ڣ������õ����㣩
echo.
echo    �����             �ƶ�����
echo    �س�              ȷ�� OK
echo    Alt+B / ����Ҽ�  ����
echo    Alt+H / ����м�  ������ Home
echo    Alt+P             ��Դ�������� / ������
echo    Alt+F �� F11      ȫ�� / �˳�ȫ��
echo    Alt+Q             �ص�Ͷ��
echo    ������          �㰴��Ļ������û������������Ӧ���޷�Ӧ��
echo ------------------------------------------------------------
echo.

"%SCRCPY%" --serial %SERIAL% --max-size=1280 --max-fps=30 --stay-awake --window-title="���� %TV_IP%"
if errorlevel 1 goto scrcpy_fail

echo.
echo ���洰���ѹرգ����ӽ������������޲�����̡�
timeout /t 2 /nobreak >nul
exit /b 0

:scrcpy_fail
echo.
echo [����] scrcpy �쳣�˳���
echo ����ԭ��
echo   1. ���Ӷ�����ػ� ���� ���¿������ 10 ������
echo   2. ���ӵ� ������ѡ�� - USB����/������� ���ص�
echo   3. ������ 5555 �˿�δ����
echo   4. ����ǽ�����˵�������ӵ� 5555 ����
echo.
"%ADB%" devices
pause
exit /b 1

:no_adb
echo [����] �Ҳ��� adb.exe����Ӧ�úͱ��ű�����ͬһ���ļ��С�
echo ��ǰĿ¼��%~dp0
pause
exit /b 1

:no_scrcpy
echo [����] �Ҳ��� scrcpy.exe����Ӧ�úͱ��ű�����ͬһ���ļ��С�
echo ��ǰĿ¼��%~dp0
pause
exit /b 1

:not_ready
echo [ʧ��] 20 ���������� %SERIAL%��
echo   ��ȷ�ϣ�
echo     1. �����ѿ������Һ͵�����ͬһ��·������
echo     2. ���� IP ���� %TV_IP%��·�����������ܱ䣩������Ľű����� TV_IP
echo     3. ������ ping ͨ���ӣ�ping %TV_IP%
echo.
"%ADB%" devices
pause
exit /b 1
