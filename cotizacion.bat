@echo off
rem Proceso batch: consulta al BNA la cotizacion del dolar de los ultimos 90
rem dias, regenera docs\index.html y lo publica en la GitHub Page del repo.
rem Uso: cotizacion.bat
setlocal
set "JAVA_HOME=C:\Users\Leonel\.jdks\corretto-21.0.8"

cd /d "%~dp0"
"%JAVA_HOME%\bin\javac" -encoding UTF-8 -d out src\CotizacionDolar.java
if errorlevel 1 exit /b 1
"%JAVA_HOME%\bin\java" -cp out CotizacionDolar
if errorlevel 1 exit /b 1

git add docs\index.html
git diff --cached --quiet && echo Sin cambios para publicar. && exit /b 0
git commit -m "Actualizar cotizacion dolar BNA"
git push origin main
endlocal
