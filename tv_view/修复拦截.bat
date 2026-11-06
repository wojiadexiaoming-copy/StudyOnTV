@echo off
chcp 936 >nul
setlocal

rem ============================================================
rem  �޸��������أ����°����ϰ�����
rem
rem  ʲôʱ��Ҫ�ã�
rem    1. ��������֮������һ�����ȣ�����̼���������ϰ����ã�
rem    2. �������ܹ� adb install -r / ���ǰ�װ֮�󣨱��ܣ�
rem    3. �����ϡ��������ء�ͻȻ������
rem
rem  �÷���˫�����ļ������������޸������ɡ����ظ�ִ�У�����Ū����
rem  ���� IP ����ֻ��Ҫ������һ�С�
rem ============================================================
set "TV_IP=192.168.1.50"
set "TV_PORT=5555"
set "SERIAL=%TV_IP%:%TV_PORT%"

set "ADB=%~dp0adb.exe"
set "SVC=com.family.quizlauncher/com.family.quizlauncher.QuizAccessibilityService"

title �޸��������� %SERIAL%

if not exist "%ADB%" goto no_adb

echo ============================================================
echo   �޸��������أ�%SERIAL%
echo ============================================================
echo.
echo [1/4] ���ӵ��� ...
"%ADB%" connect %SERIAL%
echo.

echo [2/4] �ȴ�������Ӧ����� 20 �룩...
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

echo [3/4] д�����ϰ����� ...
"%ADB%" -s %SERIAL% shell settings put secure enabled_accessibility_services %SVC%
"%ADB%" -s %SERIAL% shell settings put secure accessibility_enabled 1

echo [4/4] �ض�У�� ...
set "GOT_SVC="
set "GOT_EN="
for /f "usebackq delims=" %%a in (`"%ADB%" -s %SERIAL% shell settings get secure enabled_accessibility_services`) do set "GOT_SVC=%%a"
for /f "usebackq delims=" %%a in (`"%ADB%" -s %SERIAL% shell settings get secure accessibility_enabled`) do set "GOT_EN=%%a"

echo   ����󶨣�%GOT_SVC%
echo   ���ϰ����أ�%GOT_EN%
echo.

if /i not "%GOT_EN%"=="1" goto still_off
echo "%GOT_SVC%" | find /i "QuizAccessibilityService" >nul
if errorlevel 1 goto still_off

echo [�ɹ�] ���޸������������ѿ����
echo.
echo   ˳����֤һ�£���һ�������Ƶ�Ӧ�ã�������Ƶ App����
echo   Ӧ�����̵���������������ֱ�����档
echo.
pause
exit /b 0

:still_off
echo [ʧ��] ���ǹر�״̬������ԭ��
echo   1. Ӧ��ûװ��汾���ԣ�Ӧ���� com.family.quizlauncher��
echo   2. ����ϵͳ�����ϰ����������
echo   3. ���Ӷ������� IP ����
echo   4. ������ 5555 �˿�û����������ѡ�� - ������ԣ�
echo.
echo �ֶ���飺
echo   - ���ӣ����� - ���ϰ� - �����صķ��񣬿�������ҳ����ơ��Ƿ����б���
echo   - ���ԣ�ping %TV_IP%
echo   - �˿ڣ�%ADB% devices
echo.
pause
exit /b 1

:no_adb
echo [����] �Ҳ��� adb.exe����Ӧ�úͱ��ű�����ͬһ���ļ��С�
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
