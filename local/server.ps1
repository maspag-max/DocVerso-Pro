# DocVerso in locale: piccolo server web che serve la cartella "app" solo a questo computer
# (indirizzo localhost, non raggiungibile da altri PC). Non servono diritti di amministratore.
# Ogni richiesta è servita in parallelo (una richiesta lenta non blocca le altre); i file grandi
# (modelli di traduzione) sono inviati a pezzi, senza caricarli tutti in memoria.
# Word/PowerPoint → PDF: con Microsoft Office di questo computer (risultato identico a "Salva come PDF"),
# oppure con LibreOffice se Office non c'è. I file restano sul computer (cartella temporanea, poi cancellati).
# Si chiude chiudendo la sua finestra.

$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot 'app'
if (-not (Test-Path (Join-Path $root 'index.html'))) {
    Write-Host "Non trovo la cartella 'app' accanto a questo file: estrai di nuovo l'archivio di DocVerso." -ForegroundColor Red
    Read-Host 'Premi Invio per chiudere'
    exit 1
}
$rootFull = [System.IO.Path]::GetFullPath($root)

# Un clic dentro questa finestra non deve mettere in pausa il server ("Modifica rapida" di Windows)
try {
    Add-Type -Namespace DocVerso -Name Console -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern IntPtr GetStdHandle(int h);
[DllImport("kernel32.dll")] public static extern bool GetConsoleMode(IntPtr h, out uint m);
[DllImport("kernel32.dll")] public static extern bool SetConsoleMode(IntPtr h, uint m);
'@
    $h = [DocVerso.Console]::GetStdHandle(-10)
    $mode = 0
    if ([DocVerso.Console]::GetConsoleMode($h, [ref]$mode)) {
        [void][DocVerso.Console]::SetConsoleMode($h, ($mode -band (-bnot 0x0040)) -bor 0x0080)
    }
} catch { }

# Porta fissa (così la cache del browser resta valida fra un avvio e l'altro); se è occupata, la successiva
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

# Risposta a una richiesta: eseguita in un "runspace" separato, più richieste insieme
$serve = {
    param($ctx, $rootFull, $scriptDir)
    $types = @{
        '.html' = 'text/html; charset=utf-8'; '.js' = 'text/javascript; charset=utf-8'; '.mjs' = 'text/javascript; charset=utf-8'
        '.css' = 'text/css; charset=utf-8'; '.json' = 'application/json'; '.wasm' = 'application/wasm'
        '.svg' = 'image/svg+xml'; '.png' = 'image/png'; '.jpg' = 'image/jpeg'; '.ico' = 'image/x-icon'
        '.woff2' = 'font/woff2'; '.woff' = 'font/woff'; '.ttf' = 'font/ttf'; '.pfb' = 'application/octet-stream'
        '.bcmap' = 'application/octet-stream'; '.gz' = 'application/gzip'; '.map' = 'application/json'; '.txt' = 'text/plain; charset=utf-8'
        '.bin' = 'application/octet-stream'; '.spm' = 'application/octet-stream'
    }
    $res = $ctx.Response
    $fs = $null

    function Send-Text($code, $text, $type = 'text/plain; charset=utf-8') {
        $res.StatusCode = $code
        $res.ContentType = $type
        $b = [System.Text.Encoding]::UTF8.GetBytes($text)
        $res.ContentLength64 = $b.Length
        $res.OutputStream.Write($b, 0, $b.Length)
    }

    # Programmi disponibili per la conversione in PDF
    function Get-Converters {
        $lo = @("$env:ProgramFiles\LibreOffice\program\soffice.exe", "${env:ProgramFiles(x86)}\LibreOffice\program\soffice.exe") |
            Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
        [pscustomobject]@{
            word        = Test-Path 'Registry::HKEY_CLASSES_ROOT\Word.Application\CLSID'
            powerpoint  = Test-Path 'Registry::HKEY_CLASSES_ROOT\PowerPoint.Application\CLSID'
            libreoffice = $lo
        }
    }

    # Messaggi nella finestra nera (dai runspace Write-Host non arriva alla finestra)
    function Log($m) { try { [Console]::WriteLine("  " + (Get-Date -Format 'HH:mm:ss') + "  " + $m) } catch { } }

    # Conversione in PDF in un processo a parte (office2pdf.ps1), con un tempo massimo: se Word o PowerPoint
    # si bloccano (finestra nascosta di attivazione, primo avvio, licenza…) si chiudono e si spiega perché
    function Convert-ToPdf($in, $out, $script) {
        $officeNames = 'WINWORD', 'POWERPNT', 'soffice.bin'
        $before = @(Get-Process -Name $officeNames -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
        $mutex = New-Object System.Threading.Mutex($false, 'DocVersoOfficePdf')
        [void]$mutex.WaitOne()
        try {
            $p = Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -PassThru -ArgumentList @(
                '-NoProfile', '-STA', '-ExecutionPolicy', 'Bypass', '-File', "`"$script`"", "`"$in`"", "`"$out`"")
            if (-not $p.WaitForExit(120000)) {
                try { $p.Kill() } catch { }
                # Si chiudono solo i Word/PowerPoint avviati per questa conversione, non quelli già aperti
                Get-Process -Name $officeNames -ErrorAction SilentlyContinue | Where-Object { $before -notcontains $_.Id } |
                    ForEach-Object { try { $_.Kill() } catch { } }
                throw ('Office non ha risposto entro 2 minuti: di solito aspetta una risposta in una finestra nascosta ' +
                    '(attivazione, accesso all''account, primo avvio). Apri Word o PowerPoint una volta a mano, chiudi gli avvisi e riprova.')
            }
        } finally {
            $mutex.ReleaseMutex(); $mutex.Dispose()
        }
        $err = "$out.errore.txt"
        if (Test-Path $err) { throw ([System.IO.File]::ReadAllText($err)) }
        if (-not (Test-Path $out)) { throw 'La conversione in PDF non è riuscita.' }
    }

    try {
        $path = [System.Uri]::UnescapeDataString($ctx.Request.Url.AbsolutePath)

        # --- Che cosa può convertire in PDF questo computer
        if ($path -eq '/api/office-status') {
            $c = Get-Converters
            Send-Text 200 ("{""word"":$($c.word.ToString().ToLower()),""powerpoint"":$($c.powerpoint.ToString().ToLower()),""libreoffice"":$(([bool]$c.libreoffice).ToString().ToLower())}") 'application/json'
            return
        }

        # --- Word/PowerPoint → PDF: il file arriva nel corpo della richiesta, il PDF torna nella risposta
        if ($path -eq '/api/office-pdf' -and $ctx.Request.HttpMethod -eq 'POST') {
            $work = Join-Path ([System.IO.Path]::GetTempPath()) ('DocVerso-' + [guid]::NewGuid().ToString('N'))
            [void](New-Item -ItemType Directory -Path $work)
            try {
                $name = [System.Uri]::UnescapeDataString([string]$ctx.Request.Headers['X-Nome-File'])
                $ext = [System.IO.Path]::GetExtension($name).ToLowerInvariant()
                $allowed = '.doc', '.docx', '.docm', '.rtf', '.odt', '.dot', '.dotx', '.ppt', '.pptx', '.pptm', '.pps', '.ppsx', '.odp'
                if ($ext -notin $allowed) { Send-Text 400 'Formato non supportato: scegli un file Word o PowerPoint.'; return }
                $in = Join-Path $work ('documento' + $ext)
                $out = Join-Path $work 'documento.pdf'
                $f = [System.IO.File]::Create($in)
                try { $ctx.Request.InputStream.CopyTo($f) } finally { $f.Dispose() }
                $t0 = Get-Date
                Log "Conversione in PDF di $name…"
                try {
                    Convert-ToPdf $in $out (Join-Path $scriptDir 'office2pdf.ps1')
                } catch {
                    $msg = $_.Exception.Message
                    Log "  non riuscita: $msg"
                    Send-Text 500 $msg
                    return
                }
                Log ("  fatto in {0:0} secondi" -f ((Get-Date) - $t0).TotalSeconds)
                $res.ContentType = 'application/pdf'
                $fs = [System.IO.File]::OpenRead($out)
                $res.ContentLength64 = $fs.Length
                $fs.CopyTo($res.OutputStream, 1048576)
            } finally {
                if ($fs) { $fs.Dispose(); $fs = $null }
                Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
            }
            return
        }

        if ($path.EndsWith('/')) { $path += 'index.html' }
        $file = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($rootFull, ($path.TrimStart('/') -replace '/', '\')))
        # Solo file dentro la cartella dell'app
        if (-not $file.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase) -or -not [System.IO.File]::Exists($file)) {
            $res.StatusCode = 404
            $bytes = [System.Text.Encoding]::UTF8.GetBytes('Non trovato')
            $res.ContentLength64 = $bytes.Length
            $res.OutputStream.Write($bytes, 0, $bytes.Length)
        } else {
            $ext = [System.IO.Path]::GetExtension($file).ToLowerInvariant()
            $res.ContentType = $(if ($types.ContainsKey($ext)) { $types[$ext] } else { 'application/octet-stream' })
            $res.Headers.Add('Cache-Control', 'no-cache')
            $fs = [System.IO.File]::Open($file, 'Open', 'Read', 'Read')
            $res.ContentLength64 = $fs.Length
            $fs.CopyTo($res.OutputStream, 1048576)
        }
    } catch {
        try { $res.StatusCode = 500 } catch { }
    } finally {
        if ($fs) { $fs.Dispose() }
        try { $res.OutputStream.Close() } catch { }
    }
}

$pool = [RunspaceFactory]::CreateRunspacePool(1, 8)
$pool.ApartmentState = 'STA'   # richiesto dagli oggetti COM di Office
$pool.Open()
$jobs = New-Object System.Collections.ArrayList

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
    $ps = [PowerShell]::Create()
    $ps.RunspacePool = $pool
    [void]$ps.AddScript($serve).AddArgument($ctx).AddArgument($rootFull).AddArgument($PSScriptRoot)
    [void]$jobs.Add(@{ ps = $ps; handle = $ps.BeginInvoke() })
    # Libera le risposte già concluse
    for ($i = $jobs.Count - 1; $i -ge 0; $i--) {
        $j = $jobs[$i]
        if ($j.handle.IsCompleted) {
            try { [void]$j.ps.EndInvoke($j.handle) } catch { }
            $j.ps.Dispose()
            $jobs.RemoveAt($i)
        }
    }
}
