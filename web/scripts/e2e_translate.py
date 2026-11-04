"""
Prova nel browser vero (Chrome senza interfaccia) di una conversione con traduzione:
  python3 scripts/e2e_translate.py <scelta> <file> <uscita> [da] [a]
<scelta> = valore della conversione (pdf-word-tr, docx-tr, img-word-tr). Serve `vite preview` sulla porta 4173.
"""
import sys, asyncio, os
from playwright.async_api import async_playwright

mode, inp, out = sys.argv[1], sys.argv[2], sys.argv[3]
src = sys.argv[4] if len(sys.argv) > 4 else 'auto'
dst = sys.argv[5] if len(sys.argv) > 5 else 'it'


async def main():
    async with async_playwright() as p:
        proxy = os.environ.get('HTTPS_PROXY')
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome', args=['--no-sandbox'],
                                    proxy={'server': proxy, 'bypass': 'localhost,127.0.0.1'} if proxy else None)
        ctx = await b.new_context(viewport={'width': 1280, 'height': 860}, accept_downloads=True)
        pg = await ctx.new_page()
        logs = []
        pg.on('console', lambda m: logs.append(m.type + ': ' + m.text))
        pg.on('pageerror', lambda e: logs.append('ERR ' + str(e)))
        await pg.goto('http://localhost:4173/')
        await pg.set_input_files('#pick', inp)
        await pg.check(f'input[value="{mode}"]')
        await pg.select_option('.extra select >> nth=0', src)
        await pg.select_option('.extra select >> nth=1', dst)
        await pg.click('#go')
        last = ''
        for i in range(900):
            await pg.wait_for_timeout(1000)
            if await pg.query_selector('.verso-inner.turned'):
                break
            err = await pg.query_selector('#error:not([hidden])')
            if err:
                print('ERRORE:', await err.inner_text())
                break
            t = await pg.inner_text('#progress-text')
            if t != last:
                print(i, 's', t)
                last = t
        if await pg.query_selector('.verso-inner.turned'):
            async with pg.expect_download() as dl:
                await pg.click('.download')
            await (await dl.value).save_as(out)
            print('salvato', out)
        print('\n'.join(l for l in logs if 'ERR' in l or 'rror' in l)[-3000:])
        await b.close()

asyncio.run(main())
