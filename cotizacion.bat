@echo off
rem Proceso batch: consulta al BNA el dolar oficial y al BCRA la UVA de los
rem ultimos 2 anios, regenera docs\index.html con la relacion dolar-UVA y lo
rem publica en la GitHub Page del repo.
rem Uso: cotizacion.bat
setlocal
set "JAVA_HOME=C:\Users\Leonel\.jdks\corretto-21.0.9"

cd /d "%~dp0"
call :proceso >> cotizacion.log 2>&1
set "RC=%errorlevel%"
echo [%date% %time%] Fin con codigo %RC% >> cotizacion.log
exit /b %RC%

:proceso
echo ==================== %date% %time% ====================
"%JAVA_HOME%\bin\javac" -encoding UTF-8 -d out src\DolarUva.java
if errorlevel 1 exit /b 1
"%JAVA_HOME%\bin\java" -cp out DolarUva
if errorlevel 1 exit /b 1

git add docs\index.html
git diff --cached --quiet && echo Sin cambios para publicar. && exit /b 0
git commit -m "Actualizar relacion dolar oficial - UVA"
git push origin main
exit /b 0
