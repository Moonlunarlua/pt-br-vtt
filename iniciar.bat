@echo off
chcp 65001 >nul
title Estremere VTT
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo  O Node.js nao esta instalado.
  echo  Vou abrir a pagina de download. Baixe a versao LTS, instale
  echo  ^(so ir clicando em Avancar^) e depois rode este arquivo de novo.
  echo.
  start https://nodejs.org/pt-br/download
  pause
  exit /b 1
)

if not exist node_modules (
  echo Instalando o que a mesa precisa ^(so na primeira vez^)...
  call npm install --omit=dev
  if errorlevel 1 (
    echo Deu erro no npm install. Confira sua internet e tente de novo.
    pause
    exit /b 1
  )
)

node server.js
echo.
echo A mesa foi fechada.
pause
