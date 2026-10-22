# RUNENWERK JS

Editor für TypeScript, React, Java und SQL in Java – nach dem Vorbild des RUNENWERK-Editors:
gleiche Keymap (`mode … extends …`, Tastenfolgen, `ANY`, Layouts), gleiche Befehlsnamen,
Mapping-Funktionen, Terminal, Dateimanager im Norton-Commander-Stil. Alles wird auf der CPU
in einen eigenen Bildpuffer gemalt (eigene Glyphen-Blits); Java zeigt nur das fertige Bild.
Keine Abhängigkeiten außer einem JDK ab 17.

```bash
sh build.sh                 # Windows: build.bat (laedt beim ersten Mal jdtls nach vendor/ und packt ihn in die Jar)
java -jar runenjs.jar       # oder run.sh / run.bat
```

## Konfiguration: `config.txt` neben der Jar

Beim ersten Start legt der Editor neben `runenjs.jar` an (ist der Ordner nicht beschreibbar: `~/.runenjs`):

- `config.txt` – **alles**: Arbeitsbereiche, Ausnahmen für den Index, Language Server, Formatierer,
  Datenbank-Treiber und -Konten, Schrift, F9/F10. Jede Einstellung ist dort kommentiert; `:reload` liest sie neu.
- `keymap.config` – Tastenbelegung. Die Vorgabe steckt in der Jar, diese Datei ergänzt und überschreibt sie
  (neue Tasten aus einer neuen Jar kommen so automatisch dazu).
- `mapping/*.map` – eigene Befehle als Folge von Editor-Befehlen.
- `start.log`, `jdtls-daten/`, `jdtls-config/` – Log und Arbeitsdaten des Java Language Servers.

## Zwei Arbeitsbereiche

`ts.workspace` (TypeScript/React) und `java.workspace` (Java) in `config.txt`. Beide werden indiziert,
Strg+P sucht Dateinamen und Inhalt in beiden (`frontend/…`, `backend/…`). Ausnahmen: `ts.ignore` und
`java.ignore` – `node_modules` gilt für jeden Ordner dieses Namens, `/build` nur direkt im Arbeitsbereich.
Fehlt `ts.workspace`, fragt der Editor beim Start; `:workdir PFAD` und `:javaWorkdir PFAD` setzen sie und
tragen sie in `config.txt` ein. Die Hervorhebung richtet sich nach der Datei (`.ts/.tsx/.js/.jsx`, `.java`, `.sql`).

## Language Server

- **TypeScript**: `npm install -g typescript typescript-language-server` (`node_modules/.bin` geht vor).
- **Java**: Eclipse jdtls ist **in `runenjs.jar` eingebaut** (deshalb ist sie ca. 50 MB groß) und wird beim ersten Start
  daneben nach `jdtls-eingebaut/` entpackt – nichts zu installieren. jdtls braucht Java 21+: der Editor nimmt das Java,
  mit dem er läuft, wenn es 21+ ist, sonst sucht er ein JDK 21+ (JAVA_HOME, übliche Ordner) oder nimmt `java.lsp.java`.
  Maven- und Gradle-Projekte werden importiert.

Definition (`gd`), Hover (`K`), Vervollständigung, Referenzen (`gr`), Umbenennen (`F2`), Fehler (`]d`/`[d`)
gehen in beiden Sprachen. **Von Methode zu Methode**: `Strg+J` / `Strg+K` (oder `]m` / `[m`), mit Zähler.
**Call Hierarchy**: `Leertaste c` (oder `gh`) zeigt alle Stellen, die die Methode/Funktion unter dem Cursor aufrufen,
als Liste; tippen filtert, ENTER springt hin.

## Debugger (Java und JS/TS, gleiche Bedienung)

| Taste | |
|---|---|
| Leertaste d b | Haltepunkt an/aus (roter Punkt im Rand) |
| Leertaste d d | starten: in Java-Dateien Spring Boot im Debug-Modus (Profil `spring.profile`) und anhängen; in JS/TS-Dateien die aktuelle Datei mit Node (`debug.ts.type = chrome` für die App im Browser) |
| Leertaste d a | an Laufendes anhängen: Java auf `debug.java.port` (5005), Node auf 9229 |
| F3 / F8 / F7 / Shift+F8 | weiter / Schritt drüber / hinein / heraus (auch Leertaste d c / d n / d i / d o) |
| Leertaste d x | stoppen (Java: nur trennen, die Anwendung läuft weiter) |
| Leertaste d p | ins Debug-Panel: links Aufrufstapel, rechts Variablen; `j`/`k`, TAB, ENTER klappt auf bzw. wählt den Frame, `y` kopiert, ESC zurück |

Java nutzt JDI aus dem JDK (der Editor muss also mit einem JDK laufen, nicht nur mit einer JRE) und verbindet sich per
JDWP mit Java 21. JS/TS nutzt js-debug von Microsoft (Node und Chrome, Source Maps); beim ersten Start bietet der
Editor an, ihn herunterzuladen (`:installJsDebug`). TypeScript läuft mit Node 22.6+ direkt, sonst `debug.node.runtime = npx tsx`.

## Spring Boot

`Leertaste a a` startet die Anwendung im Java-Arbeitsbereich mit dem Profil aus `spring.profile` (Vorgabe `dev`),
`Leertaste a p` mit einem gewählten Profil (aus `spring.profiles` und `application-*.yml`), `Leertaste a d` im Debug-Modus
mit angehängtem Debugger, `Leertaste a x` stoppt sie. Gestartet wird über `mvnw`/`mvn spring-boot:run` oder
`gradlew bootRun`; eigener Befehl mit `spring.run` (`{profile}`). Die Ausgabe steht im Terminal.

## Git

Oben rechts stehen Repo und Branch beider Arbeitsbereiche (`TS frontend:main   Java backend:develop`).
`Leertaste b` wechselt den Branch des Repos der aktuellen Datei, `Leertaste g t` / `Leertaste g j` den des
TS- bzw. Java-Repos: Liste mit allen lokalen und entfernten Branches, tippen filtert, ENTER wechselt
(`git switch`). Offene, ungeänderte Dateien werden danach neu gelesen.

## Formatieren: `Leertaste f`

- TypeScript/JavaScript mit **prettier** (`ts.format`, Vorgabe `prettier --stdin-filepath {file}`; installieren mit
  `npm install -D prettier` im Projekt).
- Java mit einem **Eclipse-Formatter-Profil** (`java.format.eclipse = formatter.xml`, optional `java.format.profile`) über
  jdtls – IntelliJ kann seinen Code-Style als „Eclipse XML“ exportieren – oder mit einem **IntelliJ-Code-Style**
  (`java.format.intellij` + `java.format.intellij.cmd` = `format.bat`/`format.sh` von IntelliJ; IntelliJ muss dafür geschlossen sein).

## Datenbank (Oracle oder jeder JDBC-Treiber)

In `config.txt`: `db.driver = ojdbc11.jar` und beliebig viele Konten
`db.account.NAME = jdbc:oracle:thin:@//host:1521/SERVICE | BENUTZER | PASSWORT`. In einer `.sql`-Datei:

| Taste | |
|---|---|
| Leertaste q | neue, leere SQL-Datei für schnelle Abfragen (Konto wie zuletzt) |
| Strg+Enter | Statement am Cursor ausführen; im Visual-Mode (`v`/`V` markieren) die Auswahl, auch mit F5 oder Leertaste s s |
| F5 | ganze Datei ausführen |
| F6 | Konto für diese Datei wählen (steht unten rechts: `DB dev`) |
| Leertaste o | zur Ergebnistabelle: `hjkl`, `y` Zelle, `Y` Zeile, `Strg+A` alles kopieren, ENTER Zelle ganz zeigen, `q` schließen |
| Leertaste s c / s r / s x | Commit / Rollback / Abbrechen (`db.autocommit = off` ist die Vorgabe) |

Statements enden mit `;`, einer Zeile mit nur `/` oder einer Leerzeile; PL/SQL-Blöcke (`BEGIN`, `DECLARE`,
`CREATE PROCEDURE` …) enden mit `/`. `DBMS_OUTPUT` erscheint im Terminal. Passwörter stehen im Klartext in `config.txt`.

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
| Strg+J / Strg+K | nächste / vorige Methode |
| Leertaste b | Branch wechseln |
| Strg+Enter, F5, F6 | SQL: Statement, Datei, Konto |
| ]d / [d | nächster / vorheriger Fehler |
| Strg+Leertaste | Vorschläge (nach `.` automatisch), mit Auto-Import |
| F9 / F10 | `npm run build` / `npx tsc --noEmit`, Ausgabe im Terminal |
| F11 | Vollbild an/aus |

Im Dateimanager: `/` oder Strg+F ins Suchfeld, ESC zu den Ergebnissen, TAB wechselt den Bereich,
ENTER öffnet (bei Inhaltstreffern an der Zeile), F2 neue Datei, F5 kopieren, F6 verschieben,
r umbenennen, F7 Ordner, F8 löschen, Strg+W macht den Ordner zum Arbeitsordner.

## Wenn kein Fenster kommt

Der Editor schreibt beim Start `start.log` neben die Jar:
Java-Version, Betriebssystem, Schrift, Arbeitsordner und ob das Fenster sichtbar ist. Fehler beim Start
erscheinen zusätzlich als Fenster und in der Konsole. Nötig ist Java 17 oder neuer (`java -version`).

Steht unten „Language Server beendet“ oder „nicht gefunden“: Im Terminal (`:`) und in `start.log` stehen die
letzten Ausgaben des Servers. Meist fehlt `npm install -g typescript typescript-language-server`, oder der
npm-Ordner (Windows: `%APPDATA%\npm`) ist nicht im `Path`. `:lspRestart` startet beide Language Server neu.
