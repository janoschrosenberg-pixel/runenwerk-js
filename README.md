# RUNENWERK JS

Editor für JavaScript, TypeScript und React in Java – nach dem Vorbild des RUNENWERK-Editors:
gleiche Keymap (`mode … extends …`, Tastenfolgen, `ANY`, Layouts), gleiche Befehlsnamen,
Mapping-Funktionen, Terminal, Dateimanager im Norton-Commander-Stil. Alles wird auf der CPU
in einen eigenen Bildpuffer gemalt (eigene Glyphen-Blits); Java zeigt nur das fertige Bild.
Keine Abhängigkeiten außer einem JDK ab 17.

```bash
sh build.sh                 # Windows: build.bat
sh run.sh ~/projekte/app    # Windows: run.bat C:\projekte\app
```

Ohne Ordner fragt der Editor beim Start nach dem **Arbeitsordner** (TAB ergänzt Pfade). Dort wird
indiziert und dort startet der Language Server (`workdir PFAD` wechselt ihn später).

Language Server: `npm install -g typescript typescript-language-server`
(ein `node_modules/.bin/typescript-language-server` im Projekt hat Vorrang).

## Konfiguration (`~/.runenjs`, beim ersten Start angelegt)

- `editor.config` – Schrift, Einzug, Language Server, F9/F10-Befehle, ignorierte Ordner
- `keymap.config` – Tastenbelegung (Modes: base, normal, insert, complete, console, popup, commander, fmsearch)
- `mapping/*.map` – eigene Befehle als Folge von Editor-Befehlen (`keymap` im Terminal lädt alles neu)

## Wichtigste Tasten (Vorgabe)

| Taste | Befehl |
|---|---|
| Strg+P | Dateimanager mit Suchfeld: links Dateinamen (Teilfolge), rechts Treffer im Inhalt |
| `:` | Terminal (`open`, `w`, `q`, `workdir`, `run npm test`, `help`, Zeilennummer …) |
| gd / F12, Strg+O | Definition, zurück |
| K, gr, F2, Strg+F | Hover, Referenzen, Umbenennen, Formatieren |
| Strg+Leertaste | Vorschläge (nach `.` automatisch), mit Auto-Import |
| ge | nächster Fehler |
| F9 / F10 | `npm run build` / `npx tsc --noEmit`, Ausgabe im Terminal |
| F11 | Vollbild an/aus |

Im Dateimanager: `/` oder Strg+F ins Suchfeld, ESC zu den Ergebnissen, TAB wechselt den Bereich,
ENTER öffnet (bei Inhaltstreffern an der Zeile), F2 neue Datei, F5 kopieren, F6 verschieben,
r umbenennen, F7 Ordner, F8 löschen, Strg+W macht den Ordner zum Arbeitsordner.
