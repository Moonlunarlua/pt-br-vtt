#!/usr/bin/env python3
"""Gera o dicionário offline do app (app/src/main/assets/dicionario.bin, um SQLite compactado com gzip;
não usa a extensão .gz porque o empacotador do Android descompacta esses arquivos por conta própria).

Fonte: extração do Wikcionário em português feita pelo projeto kaikki.org / wiktextract
(conteúdo do Wikcionário, licença CC BY-SA 4.0):

    curl --compressed -o pt.jsonl \
      "https://kaikki.org/ptwiktionary/Portugu%C3%AAs/kaikki.org-dictionary-Portugu%C3%AAs.jsonl"
    python3 tools/build_dictionary.py pt.jsonl

O banco tem três tabelas:
  entry(key, word, pos, tags, senses, syn, ety)  verbetes; key = palavra em minúsculas e sem acentos
  form(key, lemma, tpl)                          formas flexionadas -> verbete de origem
  tpl(id, text)                                  descrição da forma ("plural de X", "gerúndio do verbo X"...)
"""
import gzip
import json
import os
import re
import shutil
import sqlite3
import sys
import unicodedata

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "dicionario.bin")

ENTRY_TAGS = {
    "masculine": "masculino", "feminine": "feminino", "common": "dois gêneros", "dual": "dois gêneros",
    "transitive": "transitivo", "intransitive": "intransitivo", "direct": "direto", "indirect": "indireto",
    "ditransitive": "bitransitivo", "pronominal": "pronominal", "reflexive": "reflexivo",
    "invariable": "invariável", "plural": "plural", "uncountable": "incontável", "impersonal": "impessoal",
}
SENSE_TAGS = dict(ENTRY_TAGS, **{
    "demonym": "gentílico", "Brazil": "Brasil", "Galicia": "Galiza", "figuratively": "figurado",
    "archaic": "arcaico", "popular": "popular", "informal": "informal", "colloquial": "coloquial",
    "pejorative": "pejorativo", "vulgar": "vulgar", "obsolete": "obsoleto", "familiar": "familiar",
    "rare": "raro", "poetic": "poético", "uncommon": "incomum", "ironic": "irônico", "dialectal": "dialetal",
    "derogatory": "depreciativo", "slang": "gíria", "historical": "histórico", "collective": "coletivo",
    "Anglicism": "anglicismo", "diminutive": "diminutivo", "augmentative": "aumentativo",
    "Northeast-Brazil": "Nordeste do Brasil", "Southern-Brazil": "Sul do Brasil",
    "North-Brazil": "Norte do Brasil", "Southeast-Brazil": "Sudeste do Brasil", "Goias": "Goiás",
})
for _t in ("singular", "common", "dual"):
    SENSE_TAGS.pop(_t, None)
TOPICS = {
    "geography": "Geografia", "anthroponym": "antropônimo", "botany": "Botânica", "linguistics": "Linguística",
    "medicine": "Medicina", "zoology": "Zoologia", "law": "Direito", "religion": "Religião",
    "information-science": "Informática", "chemistry": "Química", "food": "Culinária",
    "ornithology": "Ornitologia", "anatomy": "Anatomia", "sports": "Esporte", "nautical": "Náutica",
    "Christianity": "Cristianismo", "ichthyology": "Ictiologia", "mythology": "Mitologia",
    "meteorology": "Meteorologia", "entomology": "Entomologia", "pathology": "Patologia",
    "clothing": "Vestuário", "architecture": "Arquitetura", "arts": "Artes", "transport": "Transporte",
    "ethnology": "Etnologia", "aeronautics": "Aeronáutica", "video-games": "Jogos eletrônicos",
    "engineering": "Engenharia", "communications": "Comunicações", "veterinary": "Veterinária",
    "media": "Mídia", "pharmacology": "Farmacologia", "computing": "Computação",
    "numismatics": "Numismática", "mineralogy": "Mineralogia", "basketball": "Basquete",
    "arithmetic": "Aritmética",
}


def key_of(word):
    """Mesma normalização do app (WordForms.key): minúsculas, sem acentos."""
    s = unicodedata.normalize("NFD", word.strip().lower())
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return s.replace("’", "'")


def uniq(items):
    out = []
    for i in items:
        if i and i not in out:
            out.append(i)
    return out


def clean(text):
    return re.sub(r"\s+", " ", text).strip()


def main(src):
    tmp = "dicionario.db"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.execute("PRAGMA page_size=4096")
    db.execute("CREATE TABLE entry(key TEXT NOT NULL, word TEXT NOT NULL, pos TEXT, tags TEXT, senses TEXT NOT NULL, syn TEXT, ety TEXT)")
    db.execute("CREATE TABLE form(key TEXT NOT NULL, lemma TEXT NOT NULL, tpl INTEGER NOT NULL, PRIMARY KEY(key, lemma, tpl)) WITHOUT ROWID")
    db.execute("CREATE TABLE tpl(id INTEGER PRIMARY KEY, text TEXT NOT NULL)")
    templates = {}
    entries = forms = 0
    with open(src, encoding="utf-8") as f:
        for line in f:
            e = json.loads(line)
            word = clean(e.get("word", ""))
            if not word or len(word) > 60:
                continue
            key = key_of(word)
            senses = []
            for s in e.get("senses", []):
                glosses = [clean(g) for g in s.get("glosses", []) if clean(g)]
                if not glosses:
                    continue
                if "form_of" in s or "form-of" in s.get("tags", []):
                    gloss = glosses[-1]
                    m = re.match(r"^(.*\s)(\S+?)[.;,:]*$", gloss)
                    lemma = m.group(2) if m else None
                    template = (m.group(1) + "X") if m else None
                    if not lemma and s.get("form_of"):
                        lemma = s["form_of"][0].get("word")
                        template = "forma de X"
                    if not lemma or key_of(lemma) == key and lemma == word:
                        continue
                    tid = templates.setdefault(template, len(templates) + 1)
                    db.execute("INSERT OR IGNORE INTO form VALUES(?,?,?)", (key, lemma, tid))
                    forms += 1
                    continue
                labels = uniq(
                    [SENSE_TAGS.get(t, t if t[:1].isupper() else None) for t in s.get("tags", [])]
                    + [TOPICS.get(t) for t in s.get("topics", [])]
                    + [clean(t) for t in s.get("raw_tags", [])]
                )
                text = " ".join(glosses)
                if labels:
                    text = "(" + ", ".join(labels) + ") " + text
                senses.append(text)
            if not senses:
                continue
            tags = uniq([ENTRY_TAGS.get(t) for t in e.get("tags", [])] + [clean(t) for t in e.get("raw_tags", [])])
            syn = uniq([clean(x.get("word", "")) for x in e.get("synonyms", [])])[:8]
            ety = next((clean(t) for t in e.get("etymology_texts", []) if 0 < len(clean(t)) <= 240), None)
            db.execute(
                "INSERT INTO entry VALUES(?,?,?,?,?,?,?)",
                (key, word, e.get("pos_title"), ", ".join(tags) or None, "\n".join(uniq(senses)),
                 ", ".join(syn) or None, ety),
            )
            entries += 1
    db.executemany("INSERT INTO tpl VALUES(?,?)", [(i, t) for t, i in templates.items()])
    db.execute("CREATE INDEX entry_key ON entry(key)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    with open(tmp, "rb") as a, gzip.GzipFile(OUT, "wb", compresslevel=9, mtime=0) as b:
        shutil.copyfileobj(a, b)
    print(f"{entries} verbetes, {forms} formas, {len(templates)} modelos; "
          f"{os.path.getsize(tmp) / 1e6:.1f} MB -> {os.path.getsize(OUT) / 1e6:.1f} MB (gz)")


if __name__ == "__main__":
    main(sys.argv[1])
