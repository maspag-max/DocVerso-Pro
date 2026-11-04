# Documento "da Word" (italiano) con tabella unita, sfondi, elenco, immagine: poi PDF con LibreOffice
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.oxml import OxmlElement
import sys
d = Document()
for s in d.sections: s.left_margin = s.right_margin = Cm(2); s.top_margin = s.bottom_margin = Cm(2)
st = d.styles["Normal"]; st.font.name = "Calibri"; st.font.size = Pt(11)
h = d.add_paragraph(); r = h.add_run("VERBALE DI SOPRALLUOGO"); r.bold = True; r.font.size = Pt(18); r.font.color.rgb = RGBColor(0x1F, 0x4E, 0x79)
h.alignment = WD_ALIGN_PARAGRAPH.CENTER
p = d.add_paragraph("Il giorno 12 ottobre 2026, presso l'impianto di termovalorizzazione di Acerra, si è svolto il sopralluogo previsto dal programma di ricerca. Sono state esaminate le aree di manutenzione delle caldaie e i locali tecnici, con particolare attenzione agli ambienti confinati e sospetti di inquinamento.")
p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
d.add_paragraph("Partecipanti:", style=None).runs[0].bold = True
for n in ["Ing. Mario Rossi – responsabile della sicurezza", "Dott. Giulia Bianchi – ricercatrice", "Sig. Luca Verdi – capoturno"]:
    d.add_paragraph(n, style="List Bullet")
t = d.add_table(rows=5, cols=3); t.style = "Table Grid"
hdr = ["Area", "Rischio rilevato", "Misura proposta"]
for i, x in enumerate(hdr):
    c = t.rows[0].cells[i]; c.text = x; c.paragraphs[0].runs[0].bold = True
    sh = OxmlElement("w:shd"); sh.set(qn("w:val"), "clear"); sh.set(qn("w:fill"), "D9E2F3"); c._tc.get_or_add_tcPr().append(sh)
rows = [["Caldaia 1", "Accesso al passo d'uomo senza procedura scritta", "Redigere la procedura di accesso"],
        ["Tramoggia", "Atmosfera potenzialmente povera di ossigeno", "Misuratore multigas e ventilazione forzata"],
        ["Locale pompe", "Superfici calde non segnalate", "Cartellonistica e protezioni"]]
for r_i, row in enumerate(rows, 1):
    for c_i, x in enumerate(row): t.rows[r_i].cells[c_i].text = x
m = t.rows[4].cells[0].merge(t.rows[4].cells[2]); m.text = "Note: le misure dovranno essere attuate entro 30 giorni."
d.add_paragraph()
d.add_picture(sys.argv[2], width=Cm(8))
cap = d.add_paragraph("Figura 1 – Passo d'uomo della caldaia"); cap.runs[0].italic = True; cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
d.add_paragraph("Napoli, 14 ottobre 2026\t\tIl responsabile: ____________________")
d.save(sys.argv[1])
