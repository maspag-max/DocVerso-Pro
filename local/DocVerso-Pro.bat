@echo off
rem DocVerso Pro in locale: avvia il piccolo server e apre l'app nel browser.
title DocVerso Pro
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0server.ps1"
