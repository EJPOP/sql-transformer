package domain.convert;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Best-effort resolver that collects alias -> tableId map from FROM/JOIN clauses.
 *
 * <p>We intentionally keep this lightweight (no AST). It is used to:
 * <ul>
 *   <li>Resolve unqualified columns by looking up a unique table candidate</li>
 *   <li>Help DML annotator / SELECT converter decide mapping target</li>
 * </ul>
 *
 * <p>MyBatis dynamic tags (e.g. {@code <if>...</if>}, {@code <![CDATA[...]]>})
 * are preserved and skipped during scanning.
 */
final class FromJoinAliasResolver {

    private FromJoinAliasResolver() {
    }

    static Map<String, String> resolve(String sql) {
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        if (sql == null || sql.isEmpty()) return aliasToTable;

        SqlScan st = new SqlScan(sql);

        boolean expectFromTable = false;
        boolean expectJoinTable = false;
        boolean seenFrom = false;

        while (st.hasNext()) {
            // preserve/skip (do not parse inside)
            if (st.peekIsLineComment()) {
                st.readLineComment();
                continue;
            }
            if (st.peekIsBlockComment()) {
                st.readBlockComment();
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                st.readSingleQuotedString();
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                st.readDoubleQuotedString();
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                st.readMyBatisParam();
                continue;
            }
            if (st.peekIsHashToken()) {
                st.readHashToken();
                continue;
            }
            if (st.peekIsCdata()) {
                st.readCdata();
                continue;
            }
            if (st.peekIsXmlTag()) {
                st.readXmlTag();
                continue;
            }

            // keywords
            if (st.peekWord("FROM")) {
                st.readWord();
                expectFromTable = true;
                expectJoinTable = false;
                seenFrom = true;
                continue;
            }

            // join type: LEFT/RIGHT/INNER/FULL/CROSS ... JOIN
            if (st.peekWord("JOIN")) {
                st.readWord();
                expectJoinTable = true;
                expectFromTable = false;
                continue;
            }

            // Skip join modifiers so that the next JOIN keyword triggers parsing.
            if (st.peekWord("LEFT") || st.peekWord("RIGHT") || st.peekWord("FULL")
                    || st.peekWord("INNER") || st.peekWord("OUTER") || st.peekWord("CROSS")) {
                st.readWord();
                continue;
            }

            // parse table identifier after FROM/JOIN
            if (expectFromTable || expectJoinTable) {
                // whitespace
                if (Character.isWhitespace(st.peek())) {
                    st.readSpaces();
                    continue;
                }

                // subquery / derived table: FROM (SELECT ...) alias
                if (st.peek() == '(') {
                    st.readParenBlock();
                    // alias may follow, but we can't map derived table to physical table
                    readAndRegisterAliasIfPresent(st, aliasToTable, "(SUBQUERY)");
                    expectFromTable = false;
                    expectJoinTable = false;
                    continue;
                }

                if (!SqlIdentifierUtil.isIdentStart(st.peek())) {
                    // unexpected token, stop expecting
                    expectFromTable = false;
                    expectJoinTable = false;
                    st.read();
                    continue;
                }

                String tableToken = st.readIdentifier();
                if (tableToken == null || tableToken.isBlank()) {
                    expectFromTable = false;
                    expectJoinTable = false;
                    continue;
                }

                String tableUp = tableToken.toUpperCase(Locale.ROOT);

                // optional AS
                st.readSpaces();
                if (st.peekWord("AS")) {
                    st.readWord();
                    st.readSpaces();
                }

                String alias = null;
                if (SqlIdentifierUtil.isIdentStart(st.peek())) {
                    alias = st.readWord();
                }

                // no alias => use last segment of table name
                if (alias == null || alias.isBlank()) {
                    alias = lastSegment(tableUp);
                }

                aliasToTable.put(alias.toUpperCase(Locale.ROOT), tableUp);
                // also map last segment to the same table (helps when schema-qualified)
                aliasToTable.put(lastSegment(tableUp), tableUp);

                expectFromTable = false;
                expectJoinTable = false;
                continue;
            }

            // Once FROM has been seen, we can stop scanning at WHERE to keep it fast.
            if (seenFrom && st.peekWord("WHERE")) {
                break;
            }

            st.read();
        }

        return aliasToTable;
    }

    private static void readAndRegisterAliasIfPresent(SqlScan st, Map<String, String> map, String pseudoTable) {
        st.readSpaces();
        if (st.peekWord("AS")) {
            st.readWord();
            st.readSpaces();
        }
        if (SqlIdentifierUtil.isIdentStart(st.peek())) {
            String alias = st.readWord();
            if (!alias.isBlank()) map.put(alias.toUpperCase(Locale.ROOT), pseudoTable);
        }
    }

    private static String lastSegment(String tableUp) {
        if (tableUp == null) return "";
        int dot = tableUp.lastIndexOf('.');
        return (dot >= 0 && dot + 1 < tableUp.length()) ? tableUp.substring(dot + 1) : tableUp;
    }
}
