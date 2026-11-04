from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
from reportlab.lib import colors
from reportlab.platypus import Table, TableStyle, Paragraph, Frame
from reportlab.lib.styles import ParagraphStyle
from reportlab.graphics.barcode import code128
from reportlab.lib.enums import TA_JUSTIFY
from PIL import Image, ImageDraw
import sys
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
pdfmetrics.registerFont(TTFont('DejaVuSans','/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'))
out = sys.argv[1]
# Foto di prova (gradiente con forme)
img = Image.new("RGB", (400, 260), (200, 220, 240)); d = ImageDraw.Draw(img)
for i in range(260): d.line([(0, i), (400, i)], fill=(60 + i // 3, 120 + i // 4, 180))
d.rectangle([40, 120, 180, 230], fill=(90, 90, 90)); d.ellipse([220, 40, 360, 180], fill=(250, 200, 60))
img.save("photo.jpg", quality=90)

W, H = A4
c = canvas.Canvas(out, pagesize=A4)
c.setTitle("Confined space entry permit")
# Logo vettoriale (curve) + intestazione
c.setFillColor(colors.HexColor("#1F4E79")); c.circle(70, H - 60, 22, fill=1, stroke=0)
c.setFillColor(colors.white); c.setFont("Helvetica-Bold", 14); c.drawCentredString(70, H - 65, "SL")
c.setFillColor(colors.HexColor("#1F4E79")); c.setFont("Helvetica-Bold", 16)
c.drawString(105, H - 55, "CONFINED SPACE ENTRY PERMIT")
c.setFont("Helvetica", 9); c.setFillColor(colors.black)
c.drawString(105, H - 70, "Workplace safety department - Laboratory IV")
c.drawRightString(W - 50, H - 55, "Form CS-01 rev. 3")
c.drawRightString(W - 50, H - 68, "Page 1 of 2")
c.setStrokeColor(colors.HexColor("#1F4E79")); c.setLineWidth(1.5); c.line(50, H - 85, W - 50, H - 85)
# Campi da compilare su una riga
c.setFont("Helvetica", 10)
c.drawString(50, H - 110, "Site of: ______________________________")
c.drawString(330, H - 110, "Date: ____/____/________")
c.drawString(50, H - 128, "Company name: _________________________")
c.drawString(330, H - 128, "VAT number: 01165400589")
# Paragrafo giustificato
st = ParagraphStyle("p", fontName="Times-Roman", fontSize=10, leading=12.5, alignment=TA_JUSTIFY)
txt = ("This permit must be completed before any person enters a confined space. The supervisor shall verify "
       "that the atmosphere has been tested, that isolation of all energy sources has been carried out and that "
       "a rescue plan is available at the entrance. Workers must have received specific training and must be "
       "equipped with the personal protective equipment listed below. The permit is valid only for the shift indicated.")
f = Frame(50, H - 215, W - 100, 80, showBoundary=0, leftPadding=0, rightPadding=0, topPadding=0, bottomPadding=0)
f.addFromList([Paragraph(txt, st)], c)
# Tabella con intestazione colorata, celle unite, caselle
cell = ParagraphStyle("c", fontName="Helvetica", fontSize=9, leading=11)
data = [["Check", "Yes", "No", "Notes"],
        [Paragraph("Presence of operating procedure for the specific confined space", cell), "☐", "☐", ""],
        [Paragraph("Atmosphere tested (oxygen, flammable gases, toxic substances)", cell), "☐", "☐", ""],
        ["Isolation of energy sources", "☐", "☐", ""],
        ["Rescue equipment", "Tripod and winch available at the entrance", "", ""],
        ["General measures", "", "", ""]]
t = Table(data, colWidths=[230, 45, 45, 175], rowHeights=[18, 30, 30, 20, 20, 20])
t.setStyle(TableStyle([
    ("GRID", (0, 0), (-1, -1), 0.6, colors.black),
    ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#D9E2F3")),
    ("FONT", (0, 0), (-1, 0), "Helvetica-Bold", 9.5),
    ("FONT", (0, 1), (-1, -1), "Helvetica", 9),
    ("FONT", (1, 1), (2, 3), "DejaVuSans", 10), ("ALIGN", (1, 0), (2, -1), "CENTER"),
    ("SPAN", (1, 4), (3, 4)),
    ("SPAN", (0, 5), (3, 5)),
    ("BACKGROUND", (0, 5), (-1, 5), colors.HexColor("#FCE4D6")),
    ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
]))
tw, th = t.wrapOn(c, W, H); t.drawOn(c, 50, H - 240 - th)
y = H - 240 - th - 25
# Foto e didascalia
c.drawImage("photo.jpg", 50, y - 130, width=200, height=130)
c.setFont("Helvetica-Oblique", 8.5); c.drawString(50, y - 142, "Figure 1 - Access hatch of the tank")
# Testo accanto alla foto
c.setFont("Helvetica-Bold", 10); c.drawString(270, y - 10, "Responsible supervisor")
c.setFont("Helvetica", 10)
c.drawString(270, y - 28, "Name: Mario Rossi")
c.drawString(270, y - 44, "Tax code: SPGMSM70S08A509E")
c.drawString(270, y - 60, "Phone: +39 081 123 4567")
c.setFillColor(colors.HexColor("#C00000")); c.drawString(270, y - 80, "Entry prohibited without this permit")
c.setFillColor(colors.black)
# Codice a barre
bc = code128.Code128("CS01-2026-0042", barHeight=28, barWidth=0.9)
bc.drawOn(c, 330, 70)
c.setFont("Helvetica", 8); c.drawString(345, 60, "CS01-2026-0042")
c.drawString(50, 60, "Signature of supervisor: ______________________")
c.showPage()
# Pagina 2: orizzontale? no, A4 con tabella a griglia piena
c.setFont("Helvetica-Bold", 13); c.drawString(50, H - 60, "GAS MEASUREMENTS")
c.setFont("Helvetica", 9)
hdr = ["Time", "O2 (%)", "LEL (%)", "H2S (ppm)", "CO (ppm)", "Operator"]
rows = [hdr] + [[f"{8+i}:00", "20.9", "0", "0", "2", "M. Rossi" if i % 2 == 0 else "G. Bianchi"] for i in range(8)]
t2 = Table(rows, colWidths=[60, 60, 60, 70, 70, 175])
t2.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#404040")),
    ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1F4E79")), ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
    ("FONT", (0, 0), (-1, 0), "Helvetica-Bold", 9), ("ALIGN", (1, 1), (4, -1), "RIGHT")]))
tw, th = t2.wrapOn(c, W, H); t2.drawOn(c, 50, H - 80 - th)
c.setFont("Helvetica", 10)
c.drawString(50, H - 110 - th, "Alarm thresholds: O2 below 19.5% or above 23.5%; LEL above 10%; H2S above 10 ppm.")
c.drawString(50, 60, "Form CS-01 rev. 3"); c.drawRightString(W - 50, 60, "Page 2 of 2")
c.save()
