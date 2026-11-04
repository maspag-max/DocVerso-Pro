# DocVerso in locale: piccolo server web che serve la cartella "app" solo a questo computer
# (indirizzo localhost, non raggiungibile da altri PC). Non servono diritti di amministratore.
# Si chiude chiudendo la sua finestra.

$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot 'app'
if (-not (Test-Path (Join-Path $root 'index.html'))) {
    Write-Host "Non trovo la cartella 'app' accanto a questo file: estrai di nuovo l'archivio di DocVerso." -ForegroundColor Red
    Read-Host 'Premi Invio per chiudere'
    exit 1
}
$rootFull = [System.IO.Path]::GetFullPath($root)

$types = @{
    '.html' = 'text/html; charset=utf-8'; '.js' = 'text/javascript; charset=utf-8'; '.mjs' = 'text/javascript; charset=utf-8'
    '.css' = 'text/css; charset=utf-8'; '.json' = 'application/json'; '.wasm' = 'application/wasm'
    '.svg' = 'image/svg+xml'; '.png' = 'image/png'; '.jpg' = 'image/jpeg'; '.ico' = 'image/x-icon'
    '.woff2' = 'font/woff2'; '.woff' = 'font/woff'; '.ttf' = 'font/ttf'; '.pfb' = 'application/octet-stream'
    '.bcmap' = 'application/octet-stream'; '.gz' = 'application/gzip'; '.map' = 'application/json'; '.txt' = 'text/plain; charset=utf-8'
}

# Porta fissa (così la cache dei modelli di traduzione resta valida fra un avvio e l'altro); se è occupata, la successiva
$listener = $null
foreach ($port in 8765..8775) {
    try {
        $l = New-Object System.Net.HttpListener
        $l.Prefixes.Add("http://localhost:$port/")
        $l.Start()
        $listener = $l
        break
    } catch { }
}
if (-not $listener) {
    Write-Host 'Non riesco ad avviare il server locale (porte 8765-8775 occupate).' -ForegroundColor Red
    Read-Host 'Premi Invio per chiudere'
    exit 1
}
$url = $listener.Prefixes | Select-Object -First 1

Write-Host ''
Write-Host '  DocVerso e'' aperto nel browser:' -ForegroundColor Cyan
Write-Host "  $url"
Write-Host ''
Write-Host '  I file restano su questo computer. Lascia aperta questa finestra mentre usi DocVerso;'
Write-Host '  chiudila quando hai finito.'
Write-Host ''
Start-Process $url

while ($listener.IsListening) {
    try {
        $ctx = $listener.GetContext()
    } catch { break }
    $res = $ctx.Response
    try {
        $path = [System.Uri]::UnescapeDataString($ctx.Request.Url.AbsolutePath)
        if ($path.EndsWith('/')) { $path += 'index.html' }
        $file = [System.IO.Path]::GetFullPath((Join-Path $rootFull ($path.TrimStart('/') -replace '/', '\')))
        # Solo file dentro la cartella dell'app
        if (-not $file.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path $file -PathType Leaf)) {
            $res.StatusCode = 404
            $bytes = [System.Text.Encoding]::UTF8.GetBytes('Non trovato')
        } else {
            $ext = [System.IO.Path]::GetExtension($file).ToLowerInvariant()
            $res.ContentType = $(if ($types.ContainsKey($ext)) { $types[$ext] } else { 'application/octet-stream' })
            $res.Headers.Add('Cache-Control', 'no-cache')
            $bytes = [System.IO.File]::ReadAllBytes($file)
        }
        $res.ContentLength64 = $bytes.Length
        $res.OutputStream.Write($bytes, 0, $bytes.Length)
    } catch {
        try { $res.StatusCode = 500 } catch { }
    } finally {
        try { $res.OutputStream.Close() } catch { }
    }
}
