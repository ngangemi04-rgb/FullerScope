"""Validates the generated .docx: package integrity, XML well-formedness, structure."""
import os
import xml.dom.minidom as MD
import zipfile

from docx import Document

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "StethoScope_Technical_Documentation.docx")

z = zipfile.ZipFile(PATH)
bad = z.testzip()
print("ZIP integrity:", "OK" if bad is None else "CORRUPT at " + str(bad))

for n in ["[Content_Types].xml", "word/document.xml", "word/styles.xml", "word/settings.xml"]:
    print("  part", n, "->", "present" if n in z.namelist() else "MISSING")

fails = 0
for n in z.namelist():
    if n.endswith(".xml") or n.endswith(".rels"):
        try:
            MD.parseString(z.read(n))
        except Exception as e:  # noqa: BLE001
            fails += 1
            print("  XML PARSE FAIL:", n, e)
print("XML well-formed:", "all parts parsed" if fails == 0 else f"{fails} failures")

docxml = z.read("word/document.xml").decode("utf8", "ignore")
settings = z.read("word/settings.xml").decode("utf8", "ignore")

d = Document(PATH)
heads = [(p.style.name, p.text) for p in d.paragraphs if p.style.name.startswith("Heading")]
h1 = [t for s, t in heads if s == "Heading 1"]

print()
print("paragraphs:", len(d.paragraphs))
print("tables:", len(d.tables), " total rows:", sum(len(t.rows) for t in d.tables))
print("H1:", len(h1), " H2:", len([1 for s, _ in heads if s == "Heading 2"]))
print("TOC field present:", "TOC \\o" in docxml or "TOC" in docxml)
print("updateFields set:", "updateFields" in settings)
print("page breaks:", docxml.count('w:type="page"'))
print()
print("Sections:")
for t in h1:
    print("   ", t)

# Column widths must be set on every cell or Word/Docs will re-flow the table.
unset = 0
for t in d.tables:
    for row in t.rows:
        for c in row.cells:
            if c.width is None:
                unset += 1
print()
print("cells with no explicit width:", unset)
