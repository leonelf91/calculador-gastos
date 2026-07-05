@echo off
rem Proceso batch: genera en htmls\ un reporte HTML de gastos por categoria
rem por cada resumen de tarjeta PDF que haya en pdfs\.
rem Uso: procesar.bat            -> procesa todos los PDFs de pdfs\
rem      procesar.bat un.pdf     -> procesa solo ese PDF (salida en htmls\)
setlocal
set "JAVA_HOME=C:\Users\Leonel\.jdks\corretto-21.0.8"

cd /d "%~dp0"
"%JAVA_HOME%\bin\javac" -encoding UTF-8 -cp lib\pdfbox-app-3.0.5.jar -d out src\Main.java
if errorlevel 1 exit /b 1
if "%~1"=="" (
    "%JAVA_HOME%\bin\java" -cp "out;lib\pdfbox-app-3.0.5.jar" Main pdfs htmls
) else (
    "%JAVA_HOME%\bin\java" -cp "out;lib\pdfbox-app-3.0.5.jar" Main "%~1" htmls
)
endlocal
