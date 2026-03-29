@ECHO OFF
SETLOCAL

CALL "%~dp0services\content-service\mvnw.cmd" %*
EXIT /B %ERRORLEVEL%
