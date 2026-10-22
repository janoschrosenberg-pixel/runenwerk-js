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

    private static final Set<String> JAVA = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
            "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record",
            "sealed", "permits", "non-sealed", "yield", "module", "requires", "exports", "opens", "uses", "provides", "with", "to", "transitive");
    private static final Set<String> JAVA_CONSTS = Set.of("true", "false", "null", "this");
    static final Set<String> SQL = Set.of("select", "from", "where", "and", "or", "not", "in", "is", "null", "like", "between", "exists",
            "insert", "into", "values", "update", "set", "delete", "merge", "using", "matched", "when", "then", "else", "end", "case", "create",
            "replace", "alter", "drop", "truncate", "table", "view", "index", "unique", "primary", "foreign", "key", "references", "constraint",
            "default", "check", "sequence", "synonym", "grant", "revoke", "on", "to", "join", "inner", "left", "right", "full", "outer", "cross",
            "natural", "group", "by", "order", "having", "asc", "desc", "nulls", "first", "last", "union", "all", "intersect", "minus", "except",
            "distinct", "as", "with", "connect", "start", "prior", "level", "rownum", "rowid", "fetch", "next", "rows", "only", "offset", "commit",
            "rollback", "savepoint", "begin", "declare", "exception", "raise", "return", "returns", "function", "procedure", "package", "body",
            "trigger", "before", "after", "for", "each", "row", "loop", "while", "exit", "if", "elsif", "cursor", "open", "close", "type", 
            "of", "number", "varchar2", "varchar", "char", "nvarchar2", "date", "timestamp", "clob", "blob", "integer", "boolean", "pls_integer",
            "execute", "immediate", "pragma", "autonomous_transaction", "bulk", "collect", "forall", "out", "nocopy", "sysdate", "systimestamp",
            "dual", "partition", "over", "within", "pivot", "unpivot", "comment", "column", "add", "modify", "rename", "cascade", "purge");

    private static final int CODE = 0, TEMPLATE = 1, JSX_TAG = 2, JSX_KIND = 3;

    static boolean identAnf(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    static boolean ident(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** nach Sprache aus {@link Doc#sprache()} */
    static byte[] faerben(CharSequence s, String sprache) {
        if (sprache.equals("sql")) return sql(s);
        if (sprache.equals("java")) return faerben(s, false, true);
        return faerben(s, sprache.endsWith("react") || sprache.equals("javascript"), false);
    }

    static byte[] faerben(CharSequence s, boolean jsx) {
        return faerben(s, jsx, false);
    }

    static byte[] faerben(CharSequence s, boolean jsx, boolean java) {
        Set<String> schluessel = java ? JAVA : KEYWORDS;
        Set<String> konst = java ? JAVA_CONSTS : CONSTS;
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
            if (java && c == '"' && d == '"' && i + 2 < n && s.charAt(i + 2) == '"') {
                // Text-Block """ ... """
                int e = indexOf3(s, i + 3);
                e = e < 0 ? n : e + 3;
                while (i < e) f[i++] = STRING;
                continue;
            }
            if (java && c == '@' && identAnf(d)) {
                f[i++] = ATTR;
                while (i < n && (ident(s.charAt(i)) || s.charAt(i) == '.')) f[i++] = ATTR;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = text(s, f, i, c, false);
                ausdruck = false;
                continue;
            }
            if (c == '`' && !java && sp < 255) {
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
                if (schluessel.contains(w)) {
                    k = KEYWORD;
                } else if (konst.contains(w)) {
                    k = CONST;
                } else {
                    int j = i;
                    while (j < n && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
                    if (j < n && s.charAt(j) == '(') k = FUNC;
                    else if (Character.isUpperCase(c)) k = TYPE;
                    else k = TEXT;
                }
                for (int q = a; q < i; q++) f[q] = k;
                ausdruck = !java && VOR_AUSDRUCK.contains(w);
                continue;
            }
            if (c == '<' && jsx && ausdruck && (identAnf(d) || d == '>') && sp < 255) {
                f[i++] = PUNCT;
                while (i < n && (ident(s.charAt(i)) || ".-:".indexOf(s.charAt(i)) >= 0)) f[i++] = TAG;
                art[++sp] = JSX_TAG;
                continue;
            }
            if (c == '/' && ausdruck && !java) {
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

    private static int indexOf3(CharSequence s, int ab) {
        for (int k = ab; k + 2 < s.length(); k++) {
            if (s.charAt(k) == '\\') k++;
            else if (s.charAt(k) == '"' && s.charAt(k + 1) == '"' && s.charAt(k + 2) == '"') return k;
        }
        return -1;
    }

    /** SQL und PL/SQL: Schluesselwoerter ohne Gross/Klein, '...', "...", -- und Block-Kommentare */
    static byte[] sql(CharSequence s) {
        int n = s.length();
        byte[] f = new byte[n];
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            char d = i + 1 < n ? s.charAt(i + 1) : 0;
            if (c == '-' && d == '-') {
                while (i < n && s.charAt(i) != '\n') f[i++] = COMMENT;
            } else if (c == '/' && d == '*') {
                int e = indexOf(s, "*/", i + 2);
                e = e < 0 ? n : e + 2;
                while (i < e) f[i++] = COMMENT;
            } else if (c == '\'') {
                f[i++] = STRING;
                while (i < n) {
                    char x = s.charAt(i);
                    f[i++] = STRING;
                    if (x == '\'') {
                        if (i < n && s.charAt(i) == '\'') f[i++] = STRING;
                        else break;
                    }
                }
            } else if (c == '"') {
                f[i++] = TYPE;
                while (i < n && s.charAt(i) != '"' && s.charAt(i) != '\n') f[i++] = TYPE;
                if (i < n && s.charAt(i) == '"') f[i++] = TYPE;
            } else if (Character.isDigit(c)) {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.')) f[i++] = NUMBER;
            } else if (Character.isLetter(c) || c == '_') {
                int a = i;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$' || s.charAt(i) == '#')) i++;
                String w = s.subSequence(a, i).toString().toLowerCase();
                int j = i;
                while (j < n && s.charAt(j) == ' ') j++;
                byte k = SQL.contains(w) ? KEYWORD : (j < n && s.charAt(j) == '(') ? FUNC : TEXT;
                for (int q = a; q < i; q++) f[q] = k;
            } else if (c == ':' && i + 1 < n && Character.isLetter(d)) {
                f[i++] = ATTR;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) f[i++] = ATTR;
            } else {
                f[i++] = c <= ' ' ? TEXT : PUNCT;
            }
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
