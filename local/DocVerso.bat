@echo off
rem DocVerso in locale: avvia il piccolo server e apre l'app nel browser.
title DocVerso
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0server.ps1"
