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

## Vim

Die Vorgabe-Keymap ist Vim, gebaut aus denselben Bausteinen wie alles andere (umbelegbar in `keymap.config`):

- **Zähler** vor allem: `3j`, `5x`, `2dw`, `d3w`, `3p`, `3ifoo<ESC>`, `10G`, `50%`
- **Bewegungen**: `h j k l w W b B e E ge gE 0 ^ $ _ + - gg G H M L f t F T ; , % { } n N` sowie `'a` und `` `a ``
- **Operatoren** (Mode `operator`): `d c y > < gu gU g~`, doppelt für ganze Zeilen (`dd cc yy >> guu`)
- **Textobjekte**: `iw aw iW aW i" a" i' a' i` a` i( a( ib i{ a{ iB i[ a[ i< a< it at ip ap`
- **Kurzformen**: `x X s S C D Y p P r R ~ J gJ Strg+A Strg+X`
- **Visual**: `v`, `V`, `Strg+V` (Block mit `I`, `A`, `$A`, `c`, `d`, `y`, `r`, `~`), `o`, `gv`, `p` ersetzt die Auswahl, `:` gibt `'<,'>`
- **Register** `"a`–`"z` (`"A` hängt an), `"0`, `"1`–`"9`, `"-`, `"_`, `"+`; `:reg`
- **Marken** `ma`, `'a`, `` `a ``, `''`, `'<` `'>`; `:marks`
- **Makros** `qa … q`, `@a`, `@@`, `3@a`; **Wiederholen** mit `.`
- **Suche** `/ ? n N * #`; `:noh`, ESC blendet die Markierung aus
- **Rollen** `Strg+D/U/F/B/E/Y`, `zz zt zb`
- **Ex** `:w :q :q! :wq :x :e DATEI :N :$`, `:s/a/b/g`, `:%s/…/…/gi`, `:'<,'>s/…`, `:5,10s/…` (Vim-Muster mit `\( \) \| \< \>`, Ersatz mit `& \1 \r`)
- **Insert-Mode**: `Strg+W` (Wort löschen), `Strg+U`, `Strg+T`/`Strg+D` (Einrückung), `Strg+H`, `Strg+C`/`Strg+[` wie ESC

## Weitere Tasten

| Taste | Befehl |
|---|---|
| Strg+P | Dateimanager mit Suchfeld: links Dateinamen (Teilfolge), rechts Treffer im Inhalt |
| `:` | Terminal (`open`, `w`, `q`, `workdir`, `run npm test`, `help`, Zeilennummer …) |
| gd / F12, Strg+O | Definition, zurück |
| K, gr, F2, Leertaste f | Hover, Referenzen, Umbenennen, Formatieren |
| ]d / [d | nächster / vorheriger Fehler |
| Strg+Leertaste | Vorschläge (nach `.` automatisch), mit Auto-Import |
| F9 / F10 | `npm run build` / `npx tsc --noEmit`, Ausgabe im Terminal |
| F11 | Vollbild an/aus |

Im Dateimanager: `/` oder Strg+F ins Suchfeld, ESC zu den Ergebnissen, TAB wechselt den Bereich,
ENTER öffnet (bei Inhaltstreffern an der Zeile), F2 neue Datei, F5 kopieren, F6 verschieben,
r umbenennen, F7 Ordner, F8 löschen, Strg+W macht den Ordner zum Arbeitsordner.
