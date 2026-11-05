"""
Server di prova (Linux) che si comporta come DocVerso.bat/server.ps1: serve web/dist e risponde a
/api/office-status e /api/office-pdf usando LibreOffice al posto di Office. Solo per le prove.
  python3 scripts/office-server.py 4180
"""
import http.server, json, os, shutil, subprocess, sys, tempfile, urllib.parse

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'dist')
OK_EXT = {'.doc', '.docx', '.docm', '.rtf', '.odt', '.dot', '.dotx', '.ppt', '.pptx', '.pptm', '.pps', '.ppsx', '.odp'}


class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=ROOT, **k)

    def send_text(self, code, text, ctype='text/plain; charset=utf-8'):
        b = text.encode()
        self.send_response(code); self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        if self.path.startswith('/api/office-status'):
            return self.send_text(200, json.dumps({'word': False, 'powerpoint': False, 'libreoffice': True}), 'application/json')
        return super().do_GET()

    def do_POST(self):
        if not self.path.startswith('/api/office-pdf'):
            return self.send_text(404, 'Non trovato')
        name = urllib.parse.unquote(self.headers.get('X-Nome-File', ''))
        ext = os.path.splitext(name)[1].lower()
        if ext not in OK_EXT:
            return self.send_text(400, 'Formato non supportato: scegli un file Word o PowerPoint.')
        data = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        work = tempfile.mkdtemp(prefix='docverso-')
        try:
            src = os.path.join(work, 'documento' + ext)
            open(src, 'wb').write(data)
            subprocess.run(['soffice', '--headless', '--convert-to', 'pdf', '--outdir', work, src], capture_output=True, timeout=240)
            out = os.path.join(work, 'documento.pdf')
            if not os.path.exists(out):
                return self.send_text(500, 'La conversione in PDF non è riuscita: il file potrebbe essere protetto da password o danneggiato.')
            b = open(out, 'rb').read()
            self.send_response(200); self.send_header('Content-Type', 'application/pdf'); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)
        finally:
            shutil.rmtree(work, ignore_errors=True)


http.server.ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1]) if len(sys.argv) > 1 else 4180), H).serve_forever()
