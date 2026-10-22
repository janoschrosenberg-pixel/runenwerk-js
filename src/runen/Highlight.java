package runen;

import java.util.Set;

/** Hervorhebung fuer JavaScript/TypeScript mit JSX: eine Klasse je Zeichen. */
final class Highlight {
    static final byte TEXT = 0, KEYWORD = 1, STRING = 2, COMMENT = 3, NUMBER = 4, TYPE = 5, TAG = 6, FUNC = 7, PUNCT = 8, ATTR = 9, CONST = 10;

    private static final Set<String> KEYWORDS = Set.of("abstract", "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
            "debugger", "declare", "default", "delete", "do", "else", "enum", "export", "extends", "finally", "for", "from", "function", "get", "if",
            "implements", "import", "in", "infer", "instanceof", "interface", "is", "keyof", "let", "module", "namespace", "new", "of", "override",
            "package", "private", "protected", "public", "readonly", "return", "satisfies", "set", "static", "switch", "throw", "try", "type",
            "typeof", "var", "void", "while", "with", "yield", "string", "number", "boolean", "any", "unknown", "never", "object", "symbol", "bigint");
    private static final Set<String> CONSTS = Set.of("true", "false", "null", "undefined", "this", "super", "NaN", "Infinity");
    /** nach diesen Woertern darf ein Regex oder JSX folgen */
    private static final Set<String> VOR_AUSDRUCK = Set.of("return", "typeof", "case", "do", "else", "in", "of", "new", "delete", "void", "throw",
            "yield", "await", "instanceof", "default", "export");

    private static final int CODE = 0, TEMPLATE = 1, JSX_TAG = 2, JSX_KIND = 3;

    static boolean identAnf(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    static boolean ident(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    static byte[] faerben(CharSequence s, boolean jsx) {
        int n = s.length();
        byte[] f = new byte[n];
        int[] art = new int[256];
        int[] tiefe = new int[256];
        int sp = 0;
        boolean ausdruck = true; // hier darf ein Regex oder JSX anfangen
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            int modus = art[sp];
            if (modus == TEMPLATE) {
                if (c == '`') {
                    f[i++] = STRING;
                    sp--;
                    ausdruck = false;
                } else if (c == '\\') {
                    f[i++] = STRING;
                    if (i < n) f[i++] = STRING;
                } else if (c == '$' && i + 1 < n && s.charAt(i + 1) == '{' && sp < 255) {
                    f[i++] = PUNCT;
                    f[i++] = PUNCT;
                    art[++sp] = CODE;
                    tiefe[sp] = 0;
                    ausdruck = true;
                } else {
                    f[i++] = STRING;
                }
                continue;
            }
            if (modus == JSX_KIND) {
                if (c == '{' && sp < 255) {
                    f[i++] = PUNCT;
                    art[++sp] = CODE;
                    tiefe[sp] = 0;
                    ausdruck = true;
                } else if (c == '<' && i + 1 < n && s.charAt(i + 1) == '/') {
                    f[i++] = PUNCT;
                    f[i++] = PUNCT;
                    while (i < n && (ident(s.charAt(i)) || ".-:".indexOf(s.charAt(i)) >= 0)) f[i++] = TAG;
                    while (i < n && s.charAt(i) != '>' && s.charAt(i) != '\n') i++;
                    if (i < n && s.charAt(i) == '>') f[i++] = PUNCT;
                    if (--tiefe[sp] <= 0) {
                        sp--;
                        ausdruck = false;
                    }
                } else if (c == '<' && sp < 255) {
                    f[i++] = PUNCT;
                    while (i < n && (ident(s.charAt(i)) || ".-:".indexOf(s.charAt(i)) >= 0)) f[i++] = TAG;
                    art[++sp] = JSX_TAG;
                } else {
                    f[i++] = TEXT;
                }
                continue;
            }
            if (modus == JSX_TAG) {
                if (c <= ' ') {
                    i++;
                } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '>') {
                    f[i++] = PUNCT;
                    f[i++] = PUNCT;
                    sp--;
                    ausdruck = false;
                } else if (c == '>') {
                    f[i++] = PUNCT;
                    sp--;
                    if (art[sp] == JSX_KIND) {
                        tiefe[sp]++;
                    } else if (sp < 255) {
                        art[++sp] = JSX_KIND;
                        tiefe[sp] = 1;
                    }
                } else if (c == '{' && sp < 255) {
                    f[i++] = PUNCT;
                    art[++sp] = CODE;
                    tiefe[sp] = 0;
                    ausdruck = true;
                } else if (c == '"' || c == '\'') {
                    i = text(s, f, i, c, true);
                } else if (identAnf(c)) {
                    while (i < n && (ident(s.charAt(i)) || s.charAt(i) == '-' || s.charAt(i) == ':')) f[i++] = ATTR;
                } else {
                    f[i++] = PUNCT;
                }
                continue;
            }
            // ---------------------------------------------------------- Code
            if (c <= ' ') {
                i++;
                continue;
            }
            char d = i + 1 < n ? s.charAt(i + 1) : 0;
            if (c == '/' && d == '/') {
                while (i < n && s.charAt(i) != '\n') f[i++] = COMMENT;
                continue;
            }
            if (c == '/' && d == '*') {
                int e = indexOf(s, "*/", i + 2);
                e = e < 0 ? n : e + 2;
                while (i < e) f[i++] = COMMENT;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = text(s, f, i, c, false);
                ausdruck = false;
                continue;
            }
            if (c == '`' && sp < 255) {
                f[i++] = STRING;
                art[++sp] = TEMPLATE;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && Character.isDigit(d))) {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) f[i++] = NUMBER;
                ausdruck = false;
                continue;
            }
            if (identAnf(c)) {
                int a = i;
                while (i < n && ident(s.charAt(i))) i++;
                String w = s.subSequence(a, i).toString();
                byte k;
                if (KEYWORDS.contains(w)) {
                    k = KEYWORD;
                } else if (CONSTS.contains(w)) {
                    k = CONST;
                } else {
                    int j = i;
                    while (j < n && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
                    if (j < n && s.charAt(j) == '(') k = FUNC;
                    else if (Character.isUpperCase(c)) k = TYPE;
                    else k = TEXT;
                }
                for (int q = a; q < i; q++) f[q] = k;
                ausdruck = VOR_AUSDRUCK.contains(w);
                continue;
            }
            if (c == '<' && jsx && ausdruck && (identAnf(d) || d == '>') && sp < 255) {
                f[i++] = PUNCT;
                while (i < n && (ident(s.charAt(i)) || ".-:".indexOf(s.charAt(i)) >= 0)) f[i++] = TAG;
                art[++sp] = JSX_TAG;
                continue;
            }
            if (c == '/' && ausdruck) {
                int j = i + 1;
                boolean klasse = false;
                while (j < n && s.charAt(j) != '\n') {
                    char x = s.charAt(j);
                    if (x == '\\') j++;
                    else if (x == '[') klasse = true;
                    else if (x == ']') klasse = false;
                    else if (x == '/' && !klasse) break;
                    j++;
                }
                if (j < n && s.charAt(j) == '/') {
                    j++;
                    while (j < n && Character.isLetter(s.charAt(j))) j++;
                    while (i < j) f[i++] = STRING;
                    ausdruck = false;
                    continue;
                }
            }
            if (c == '{') {
                tiefe[sp]++;
            } else if (c == '}') {
                if (tiefe[sp] == 0 && sp > 0) {
                    f[i++] = PUNCT;
                    sp--;
                    ausdruck = false;
                    continue;
                }
                tiefe[sp]--;
            }
            f[i++] = PUNCT;
            ausdruck = c != ')' && c != ']';
        }
        return f;
    }

    private static int text(CharSequence s, byte[] f, int i, char q, boolean mehrzeilig) {
        int n = s.length();
        f[i++] = STRING;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n' && !mehrzeilig) break;
            f[i++] = STRING;
            if (c == '\\' && i < n) f[i++] = STRING;
            else if (c == q) break;
        }
        return i;
    }

    private static int indexOf(CharSequence s, String was, int ab) {
        int n = s.length() - was.length();
        for (int k = ab; k <= n; k++) {
            if (s.charAt(k) == was.charAt(0) && s.charAt(k + 1) == was.charAt(1)) return k;
        }
        return -1;
    }
}
