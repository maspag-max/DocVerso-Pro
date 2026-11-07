@echo off
rem Scarica il modello della traduzione accurata (TranslateGemma 4B, circa 3 GB) nella cartella
rem app\modelli\translategemma, cosi' funziona anche senza internet e dietro il proxy dell'ufficio.
rem Se il download si interrompe, rilancialo: riprende da dove era arrivato.
rem Puoi scaricarlo a casa e poi copiare tutta la cartella DocVerso-Pro sul PC dell'ufficio.
setlocal
cd /d "%~dp0"
set "BASE=https://huggingface.co/onnx-community/translategemma-text-4b-it-ONNX/resolve/main"
set "DEST=app\modelli\translategemma\onnx-community\translategemma-text-4b-it-ONNX"
if not exist "%DEST%\onnx" mkdir "%DEST%\onnx"
for %%F in (config.json generation_config.json tokenizer.json tokenizer_config.json onnx/model_q4.onnx onnx/model_q4.onnx_data onnx/model_q4.onnx_data_1) do (
  echo Scarico %%F ...
  curl.exe -L --fail --retry 5 -C - -o "%DEST%\%%F" "%BASE%/%%F"
  if errorlevel 1 (
    echo.
    echo Download non riuscito: controlla la connessione ^(alcune reti aziendali bloccano huggingface.co^) e rilancia.
    pause
    exit /b 1
  )
)
echo.
echo Fatto: il modello della traduzione accurata e' in %DEST%
pause
