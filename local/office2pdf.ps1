# Converte un file Word o PowerPoint in PDF con Office di questo computer (oppure LibreOffice).
# Uso: office2pdf.ps1 <file d'ingresso> <PDF d'uscita>
# Lo avvia server.ps1 in un processo a parte, con un tempo massimo: se Office si blocca (per esempio
# su una finestra nascosta di attivazione o di primo avvio), il server lo chiude e lo dice.
# Si può anche lanciare a mano per una prova:
#   powershell -ExecutionPolicy Bypass -File office2pdf.ps1 C:\cartella\documento.docx C:\cartella\documento.pdf
param([Parameter(Mandatory)][string]$In, [Parameter(Mandatory)][string]$Out)

$ErrorActionPreference = 'Stop'
$errFile = "$Out.errore.txt"

function Fail($msg) {
    [System.IO.File]::WriteAllText($errFile, $msg, [System.Text.Encoding]::UTF8)
    exit 1
}

$ext = [System.IO.Path]::GetExtension($In).ToLowerInvariant()
$isWord = $ext -in '.doc', '.docx', '.docm', '.rtf', '.odt', '.dot', '.dotx'
$isPpt = $ext -in '.ppt', '.pptx', '.pptm', '.pps', '.ppsx', '.odp'
$hasWord = Test-Path 'Registry::HKEY_CLASSES_ROOT\Word.Application\CLSID'
$hasPpt = Test-Path 'Registry::HKEY_CLASSES_ROOT\PowerPoint.Application\CLSID'
$libre = @("$env:ProgramFiles\LibreOffice\program\soffice.exe", "${env:ProgramFiles(x86)}\LibreOffice\program\soffice.exe") |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

try {
    if ($isWord -and $hasWord) {
        $app = $null; $doc = $null
        try {
            $app = New-Object -ComObject Word.Application
            $app.Visible = $false
            $app.DisplayAlerts = 0                          # wdAlertsNone: nessuna finestra di avviso
            try { $app.AutomationSecurity = 3 } catch { }   # macro disattivate
            try { $app.Options.DoNotPromptForConvert = $true } catch { }
            try { $app.Options.ConfirmConversions = $false } catch { }
            # Open(FileName, ConfirmConversions, ReadOnly, AddToRecentFiles, PasswordDocument, PasswordTemplate,
            #      Revert, WritePasswordDocument, WritePasswordTemplate, Format, Encoding, Visible, OpenAndRepair, ...,
            #      NoEncodingDialog). Password finta: un file protetto dà errore invece di chiedere la password.
            $doc = $app.Documents.Open($In, $false, $true, $false, 'x', 'x', $false, 'x', 'x', 0, [Type]::Missing, $false, $false, [Type]::Missing, $true)
            $doc.ExportAsFixedFormat($Out, 17)              # 17 = wdExportFormatPDF
        } finally {
            if ($doc) { try { $doc.Close(0) } catch { }; [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($doc) }
            if ($app) {
                try { if ($app.Documents.Count -eq 0) { $app.Quit(0) } } catch { }
                [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($app)
            }
        }
    } elseif ($isPpt -and $hasPpt) {
        $app = $null; $pres = $null
        try {
            $app = New-Object -ComObject PowerPoint.Application
            try { $app.DisplayAlerts = 1 } catch { }        # ppAlertsNone
            try { $app.AutomationSecurity = 3 } catch { }
            # Open(FileName, ReadOnly = msoTrue, Untitled = msoFalse, WithWindow = msoFalse)
            $pres = $app.Presentations.Open("$In::x", -1, 0, 0)   # "::x" = password finta (file protetti: errore, non domanda)
            $pres.SaveAs($Out, 32)                          # 32 = ppSaveAsPDF
        } catch {
            # Alcune versioni non accettano la password nel nome: secondo tentativo senza
            if (-not $pres) {
                $pres = $app.Presentations.Open($In, -1, 0, 0)
                $pres.SaveAs($Out, 32)
            } else { throw }
        } finally {
            if ($pres) { try { $pres.Close() } catch { }; [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($pres) }
            if ($app) {
                # PowerPoint è uno solo per tutto il computer: si chiude solo se non ha altre presentazioni aperte
                try { if ($app.Presentations.Count -eq 0) { $app.Quit() } } catch { }
                [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($app)
            }
        }
    } elseif (($isWord -or $isPpt) -and $libre) {
        $dir = [System.IO.Path]::GetDirectoryName($Out)
        $prof = 'file:///' + ((Join-Path $dir 'profilo') -replace '\\', '/')
        Start-Process -FilePath $libre -ArgumentList @("-env:UserInstallation=$prof", '--headless', '--convert-to', 'pdf', '--outdir', "`"$dir`"", "`"$In`"") -WindowStyle Hidden -Wait
        $made = Join-Path $dir ([System.IO.Path]::GetFileNameWithoutExtension($In) + '.pdf')
        if ((Test-Path $made) -and $made -ne $Out) { Move-Item -Force $made $Out }
    } else {
        $what = $(if ($isPpt) { 'PowerPoint' } else { 'Word' })
        Fail "Per convertire in PDF serve Microsoft $what (o LibreOffice) installato su questo computer."
    }
} catch {
    $m = $_.Exception.Message
    if ($m -match 'password') { Fail 'Il file è protetto da password: aprilo, togli la password e riprova.' }
    Fail "Office non è riuscito a convertire il file: $m"
}
if (-not (Test-Path $Out)) { Fail 'La conversione in PDF non è riuscita: il file potrebbe essere danneggiato.' }
exit 0
