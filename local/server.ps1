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
    param($ctx, $rootFull)
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

    # Conversione di un file in PDF; restituisce il percorso del PDF o lancia un errore con un messaggio chiaro
    function Convert-ToPdf($in, $out, $conv) {
        $ext = [System.IO.Path]::GetExtension($in).ToLowerInvariant()
        $isWord = $ext -in '.doc', '.docx', '.docm', '.rtf', '.odt', '.dot', '.dotx'
        $isPpt = $ext -in '.ppt', '.pptx', '.pptm', '.pps', '.ppsx', '.odp'
        # Una conversione alla volta: Office non ama essere comandato da più richieste insieme
        $mutex = New-Object System.Threading.Mutex($false, 'DocVersoOfficePdf')
        [void]$mutex.WaitOne()
        try {
            if ($isWord -and $conv.word) {
                $app = $null; $doc = $null
                try {
                    $app = New-Object -ComObject Word.Application
                    $app.Visible = $false
                    $app.DisplayAlerts = 0                  # nessuna finestra di dialogo
                    try { $app.AutomationSecurity = 3 } catch { }   # macro disattivate
                    # Open(FileName, ConfirmConversions, ReadOnly, AddToRecentFiles)
                    $doc = $app.Documents.Open($in, $false, $true, $false)
                    $doc.ExportAsFixedFormat($out, 17)      # 17 = wdExportFormatPDF
                } finally {
                    if ($doc) { try { $doc.Close(0) } catch { }; [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($doc) }
                    if ($app) {
                        # Si chiude Word solo se non ci sono altri documenti aperti
                        try { if ($app.Documents.Count -eq 0) { $app.Quit(0) } } catch { }
                        [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($app)
                    }
                }
            } elseif ($isPpt -and $conv.powerpoint) {
                $app = $null; $pres = $null
                try {
                    $app = New-Object -ComObject PowerPoint.Application
                    try { $app.AutomationSecurity = 3 } catch { }
                    # Open(FileName, ReadOnly = msoTrue, Untitled = msoFalse, WithWindow = msoFalse)
                    $pres = $app.Presentations.Open($in, -1, 0, 0)
                    $pres.SaveAs($out, 32)                  # 32 = ppSaveAsPDF
                } finally {
                    if ($pres) { try { $pres.Close() } catch { }; [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($pres) }
                    if ($app) {
                        # PowerPoint è uno solo per tutto il computer: si chiude solo se non ha altre presentazioni aperte
                        try { if ($app.Presentations.Count -eq 0) { $app.Quit() } } catch { }
                        [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($app)
                    }
                }
            } elseif (($isWord -or $isPpt) -and $conv.libreoffice) {
                $dir = [System.IO.Path]::GetDirectoryName($out)
                $prof = 'file:///' + ((Join-Path $dir 'profilo') -replace '\\', '/')
                $p = Start-Process -FilePath $conv.libreoffice -ArgumentList @("-env:UserInstallation=$prof", '--headless', '--convert-to', 'pdf', '--outdir', "`"$dir`"", "`"$in`"") -WindowStyle Hidden -PassThru -Wait
                $made = Join-Path $dir ([System.IO.Path]::GetFileNameWithoutExtension($in) + '.pdf')
                if ((Test-Path $made) -and $made -ne $out) { Move-Item -Force $made $out }
            } else {
                $what = $(if ($isPpt) { 'PowerPoint' } else { 'Word' })
                throw "Per convertire in PDF serve Microsoft $what (o LibreOffice) installato su questo computer."
            }
        } finally {
            $mutex.ReleaseMutex(); $mutex.Dispose()
        }
        if (-not (Test-Path $out)) { throw 'La conversione in PDF non è riuscita: il file potrebbe essere protetto da password o danneggiato.' }
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
                try {
                    Convert-ToPdf $in $out (Get-Converters)
                } catch {
                    $msg = $_.Exception.Message
                    if ($msg -notmatch '^(Per convertire|La conversione)') { $msg = "Office non è riuscito a convertire il file: $msg" }
                    Send-Text 500 $msg
                    return
                }
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
    [void]$ps.AddScript($serve).AddArgument($ctx).AddArgument($rootFull)
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
