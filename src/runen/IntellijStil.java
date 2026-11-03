package runen;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Uebersetzt einen IntelliJ-Code-Style (XML-Export oder .idea/codeStyles/Project.xml) in ein Eclipse-
 * Formatter-Profil, mit dem der eingebaute Java Language Server formatiert - IntelliJ wird dafuer nicht
 * gebraucht. Grundlage sind IntelliJs Standardwerte (ein Export enthaelt nur die Abweichungen); uebersetzt
 * werden Einrueckung, Zeilenlaenge, Klammern, Zeilenumbrueche, Leerzeilen, Annotationen und Leerzeichen.
 * Kommentare bleiben, wie sie sind; Import-Reihenfolge ist nicht Sache des Formatters.
 */
final class IntellijStil {
    private static final String P = "org.eclipse.jdt.core.formatter.";

    /** IntelliJ-Werte aus der XML: erst allgemeine, dann JavaCodeStyleSettings, dann codeStyleSettings language="JAVA" */
    static Map<String, String> lesen(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        org.w3c.dom.Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList schemes = d.getElementsByTagName("code_scheme");
        if (schemes.getLength() == 0) throw new IllegalArgumentException("kein <code_scheme> - ist das ein IntelliJ-Code-Style?");
        Element s = (Element) schemes.item(0);
        Map<String, String> w = new LinkedHashMap<>();
        Element java = null, javaEigen = null;
        for (Node n = s.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e)) continue;
            switch (e.getTagName()) {
                case "option" -> w.put(e.getAttribute("name"), e.getAttribute("value"));
                case "JavaCodeStyleSettings" -> javaEigen = e;
                case "codeStyleSettings" -> {
                    if ("JAVA".equals(e.getAttribute("language"))) java = e;
                }
                default -> {
                }
            }
        }
        if (javaEigen != null) optionen(javaEigen, w);
        if (java != null) optionen(java, w);
        return w;
    }

    /** option-Kinder (auch in indentOptions) */
    private static void optionen(Element e, Map<String, String> w) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element k)) continue;
            if (k.getTagName().equals("option")) w.put(k.getAttribute("name"), k.getAttribute("value"));
            else if (k.getTagName().equals("indentOptions")) optionen(k, w);
        }
    }

    /** Eclipse-Profil (XML) mit dem Namen name */
    static String eclipseProfil(String intellijXml, String name) throws Exception {
        Map<String, String> w = lesen(intellijXml);
        Map<String, String> e = new LinkedHashMap<>();
        int indent = zahl(w, "INDENT_SIZE", 4), cont = zahl(w, "CONTINUATION_INDENT_SIZE", 8), tab = zahl(w, "TAB_SIZE", 4);
        boolean tabs = an(w, "USE_TAB_CHARACTER", false);
        e.put("tabulation.char", tabs ? "tab" : "space");
        e.put("tabulation.size", String.valueOf(tabs ? tab : indent));
        e.put("indentation.size", String.valueOf(indent));
        e.put("use_tabs_only_for_leading_indentations", "false");
        int c = Math.max(0, Math.round(cont / (float) Math.max(1, indent)));
        e.put("continuation_indentation", String.valueOf(c));
        e.put("continuation_indentation_for_array_initializer", String.valueOf(c));
        String rand = String.valueOf(zahl(w, "RIGHT_MARGIN", 120));
        e.put("lineSplit", rand);
        e.put("comment.line_length", rand);
        e.put("join_wrapped_lines", an(w, "KEEP_LINE_BREAKS", true) ? "false" : "true");
        e.put("indent_empty_lines", an(w, "KEEP_INDENTS_ON_EMPTY_LINES", false) ? "true" : "false");
        // Klammern
        String block = klammer(zahl(w, "BRACE_STYLE", 1)), klasse = klammer(zahl(w, "CLASS_BRACE_STYLE", 1)), methode = klammer(zahl(w, "METHOD_BRACE_STYLE", 1));
        String lambda = klammer(zahl(w, "LAMBDA_BRACE_STYLE", 1));
        for (String k : new String[] {"type_declaration", "anonymous_type_declaration", "enum_declaration", "record_declaration", "annotation_type_declaration"}) e.put("brace_position_for_" + k, klasse);
        for (String k : new String[] {"method_declaration", "constructor_declaration", "record_constructor"}) e.put("brace_position_for_" + k, methode);
        for (String k : new String[] {"block", "block_in_case", "switch", "enum_constant"}) e.put("brace_position_for_" + k, block);
        e.put("brace_position_for_lambda_body", lambda);
        e.put("brace_position_for_array_initializer", "end_of_line");
        e.put("insert_new_line_before_else_in_if_statement", ja(an(w, "ELSE_ON_NEW_LINE", false)));
        e.put("insert_new_line_before_catch_in_try_statement", ja(an(w, "CATCH_ON_NEW_LINE", false)));
        e.put("insert_new_line_before_finally_in_try_statement", ja(an(w, "FINALLY_ON_NEW_LINE", false)));
        e.put("insert_new_line_before_while_in_do_statement", ja(an(w, "WHILE_ON_NEW_LINE", false)));
        // Leerzeilen
        e.put("blank_lines_before_package", String.valueOf(zahl(w, "BLANK_LINES_BEFORE_PACKAGE", 0)));
        e.put("blank_lines_after_package", String.valueOf(zahl(w, "BLANK_LINES_AFTER_PACKAGE", 1)));
        e.put("blank_lines_before_imports", String.valueOf(zahl(w, "BLANK_LINES_BEFORE_IMPORTS", 1)));
        e.put("blank_lines_after_imports", String.valueOf(zahl(w, "BLANK_LINES_AFTER_IMPORTS", 1)));
        e.put("blank_lines_between_import_groups", "1");
        e.put("blank_lines_before_method", String.valueOf(zahl(w, "BLANK_LINES_AROUND_METHOD", 1)));
        e.put("blank_lines_before_abstract_method", String.valueOf(zahl(w, "BLANK_LINES_AROUND_METHOD_IN_INTERFACE", 1)));
        e.put("blank_lines_before_field", String.valueOf(zahl(w, "BLANK_LINES_AROUND_FIELD", 0)));
        e.put("blank_lines_before_member_type", String.valueOf(zahl(w, "BLANK_LINES_AROUND_CLASS", 1)));
        e.put("blank_lines_between_type_declarations", String.valueOf(zahl(w, "BLANK_LINES_AROUND_CLASS", 1)));
        e.put("blank_lines_before_first_class_body_declaration", String.valueOf(zahl(w, "BLANK_LINES_AFTER_CLASS_HEADER", 0)));
        e.put("blank_lines_after_last_class_body_declaration", String.valueOf(zahl(w, "BLANK_LINES_BEFORE_CLASS_END", 0)));
        e.put("blank_lines_at_beginning_of_method_body", String.valueOf(zahl(w, "BLANK_LINES_BEFORE_METHOD_BODY", 0)));
        e.put("number_of_empty_lines_to_preserve", String.valueOf(Math.max(zahl(w, "KEEP_BLANK_LINES_IN_CODE", 2), zahl(w, "KEEP_BLANK_LINES_IN_DECLARATIONS", 2))));
        // switch
        e.put("indent_switchstatements_compare_to_switch", String.valueOf(an(w, "INDENT_CASE_FROM_SWITCH", true)));
        e.put("indent_switchstatements_compare_to_cases", "true");
        e.put("indent_breaks_compare_to_cases", String.valueOf(an(w, "INDENT_BREAK_FROM_CASE", true)));
        // Zeilenumbrueche: IntelliJ 0 nicht umbrechen, 1 wenn zu lang, 2 immer, 4/5 einzeln wenn zu lang
        String param = umbruch(zahl(w, "METHOD_PARAMETERS_WRAP", 0), an(w, "ALIGN_MULTILINE_PARAMETERS", true), an(w, "METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE", false));
        e.put("alignment_for_parameters_in_method_declaration", param);
        e.put("alignment_for_parameters_in_constructor_declaration", param);
        e.put("alignment_for_record_components", param);
        String args = umbruch(zahl(w, "CALL_PARAMETERS_WRAP", 0), an(w, "ALIGN_MULTILINE_PARAMETERS_IN_CALLS", false), an(w, "CALL_PARAMETERS_LPAREN_ON_NEXT_LINE", false));
        for (String k : new String[] {"method_invocation", "allocation_expression", "explicit_constructor_call", "enum_constant", "qualified_allocation_expression"}) e.put("alignment_for_arguments_in_" + k, args);
        e.put("alignment_for_selector_in_method_invocation", umbruch(zahl(w, "METHOD_CALL_CHAIN_WRAP", 0), an(w, "ALIGN_MULTILINE_CHAINED_METHODS", false)));
        String ext = umbruch(zahl(w, "EXTENDS_LIST_WRAP", 0), an(w, "ALIGN_MULTILINE_EXTENDS_LIST", false));
        e.put("alignment_for_superinterfaces_in_type_declaration", ext);
        e.put("alignment_for_superinterfaces_in_enum_declaration", ext);
        e.put("alignment_for_superinterfaces_in_record_declaration", ext);
        e.put("alignment_for_superclass_in_type_declaration", umbruch(zahl(w, "EXTENDS_KEYWORD_WRAP", 0), false));
        String thr = umbruch(zahl(w, "THROWS_LIST_WRAP", 0), an(w, "ALIGN_MULTILINE_THROWS_LIST", false));
        e.put("alignment_for_throws_clause_in_method_declaration", thr);
        e.put("alignment_for_throws_clause_in_constructor_declaration", thr);
        e.put("alignment_for_expressions_in_array_initializer", umbruch(zahl(w, "ARRAY_INITIALIZER_WRAP", 0), an(w, "ALIGN_MULTILINE_ARRAY_INITIALIZER_EXPRESSION", false)));
        e.put("alignment_for_enum_constants", umbruch(zahl(w, "ENUM_CONSTANTS_WRAP", 0), false));
        e.put("alignment_for_conditional_expression", umbruch(zahl(w, "TERNARY_OPERATION_WRAP", 0), an(w, "ALIGN_MULTILINE_TERNARY_OPERATION", false)));
        String bin = umbruch(zahl(w, "BINARY_OPERATION_WRAP", 0), an(w, "ALIGN_MULTILINE_BINARY_OPERATION", false));
        for (String k : new String[] {"binary_expression", "additive_operator", "multiplicative_operator", "logical_operator", "string_concatenation", "bitwise_operator", "relational_operator", "shift_operator"}) e.put("alignment_for_" + k, bin);
        e.put("alignment_for_assignment", umbruch(zahl(w, "ASSIGNMENT_WRAP", 0), false));
        e.put("alignment_for_resources_in_try", umbruch(zahl(w, "RESOURCE_LIST_WRAP", 0), an(w, "ALIGN_MULTILINE_RESOURCES", true)));
        boolean vorOp = an(w, "BINARY_OPERATION_SIGN_ON_NEXT_LINE", false);
        for (String k : new String[] {"binary_operator", "additive_operator", "multiplicative_operator", "logical_operator", "string_concatenation", "bitwise_operator", "relational_operator", "shift_operator"}) e.put("wrap_before_" + k, String.valueOf(vorOp));
        e.put("wrap_before_conditional_operator", String.valueOf(an(w, "TERNARY_OPERATION_SIGNS_ON_NEXT_LINE", false)));
        e.put("wrap_before_assignment_operator", "false");
        // Annotationen: 2 = eigene Zeile
        e.put("insert_new_line_after_annotation_on_type", ja(zahl(w, "CLASS_ANNOTATION_WRAP", 2) == 2));
        e.put("insert_new_line_after_annotation_on_method", ja(zahl(w, "METHOD_ANNOTATION_WRAP", 2) == 2));
        e.put("insert_new_line_after_annotation_on_field", ja(zahl(w, "FIELD_ANNOTATION_WRAP", 2) == 2));
        e.put("insert_new_line_after_annotation_on_parameter", ja(zahl(w, "PARAMETER_ANNOTATION_WRAP", 0) == 2));
        e.put("insert_new_line_after_annotation_on_local_variable", ja(zahl(w, "VARIABLE_ANNOTATION_WRAP", 0) == 2));
        e.put("insert_new_line_after_annotation_on_enum_constant", ja(zahl(w, "ENUM_FIELD_ANNOTATION_WRAP", 0) == 2));
        e.put("insert_new_line_after_annotation_on_package", "insert");
        // Leerzeichen
        e.put("insert_space_after_closing_paren_in_cast", ja(an(w, "SPACE_AFTER_TYPE_CAST", true)));
        e.put("insert_space_before_opening_brace_in_array_initializer", ja(an(w, "SPACE_BEFORE_ARRAY_INITIALIZER_LBRACE", false)));
        boolean innen = an(w, "SPACE_WITHIN_ARRAY_INITIALIZER_BRACES", false);
        e.put("insert_space_after_opening_brace_in_array_initializer", ja(innen));
        e.put("insert_space_before_closing_brace_in_array_initializer", ja(innen));
        // Kommentare laesst IntelliJ (bis auf Javadoc-Kleinigkeiten) stehen
        e.put("comment.format_javadoc_comments", "false");
        e.put("comment.format_block_comments", "false");
        e.put("comment.format_line_comments", "false");
        e.put("comment.format_header", "false");
        e.put("never_indent_line_comments_on_first_column", String.valueOf(an(w, "LINE_COMMENT_AT_FIRST_COLUMN", true)));
        e.put("never_indent_block_comments_on_first_column", String.valueOf(an(w, "BLOCK_COMMENT_AT_FIRST_COLUMN", true)));
        // @formatter:off / on
        e.put("use_on_off_tags", String.valueOf(an(w, "FORMATTER_TAGS_ENABLED", false)));
        e.put("disabling_tag", w.getOrDefault("FORMATTER_OFF_TAG", "@formatter:off"));
        e.put("enabling_tag", w.getOrDefault("FORMATTER_ON_TAG", "@formatter:on"));

        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
        b.append("<!-- erzeugt von RUNENWERK JS aus einem IntelliJ-Code-Style; Aenderungen am IntelliJ-Stil vornehmen -->\n");
        b.append("<profiles version=\"23\">\n<profile kind=\"CodeFormatterProfile\" name=\"").append(name).append("\" version=\"23\">\n");
        for (var x : e.entrySet()) b.append("<setting id=\"").append(P).append(x.getKey()).append("\" value=\"").append(x.getValue().replace("\"", "&quot;")).append("\"/>\n");
        b.append("</profile>\n</profiles>\n");
        return b.toString();
    }

    /** IntelliJ-Klammerstil -> Eclipse */
    private static String klammer(int stil) {
        return switch (stil) {
            case 2 -> "next_line";
            case 3, 4 -> "next_line_shifted";
            case 5 -> "next_line_on_wrap";
            default -> "end_of_line";
        };
    }

    private static String umbruch(int wrap, boolean ausrichten) {
        return umbruch(wrap, ausrichten, false);
    }

    /**
     * IntelliJ-Umbruch (0 nicht, 1 wenn zu lang, 2 immer, 4/5 einzeln wenn zu lang) und "Align when multiline" ->
     * Eclipse-Wert (Art | Einrueckung | erzwingen). Wie IntelliJ bleibt das erste Element hinter "(" stehen
     * (Eclipse 80), ausser bei "New line after '('" (Eclipse 48).
     */
    private static String umbruch(int wrap, boolean ausrichten, boolean klammerZeile) {
        if (wrap == 0) return "0";
        int jedes = klammerZeile ? 48 : 80;
        int art = (wrap & 2) != 0 ? jedes | 1 : (wrap & 4) != 0 ? jedes : 16;
        return String.valueOf(art | (ausrichten ? 2 : 0));
    }

    private static String ja(boolean b) {
        return b ? "insert" : "do not insert";
    }

    private static int zahl(Map<String, String> w, String k, int vorgabe) {
        try {
            return w.containsKey(k) ? Integer.parseInt(w.get(k).strip()) : vorgabe;
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    private static boolean an(Map<String, String> w, String k, boolean vorgabe) {
        return w.containsKey(k) ? Boolean.parseBoolean(w.get(k).strip()) : vorgabe;
    }
}
