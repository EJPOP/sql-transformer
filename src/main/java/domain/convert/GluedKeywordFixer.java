package domain.convert;

import java.util.Locale;

/**
 * Fixes cases where whitespace/newline is lost and clause keywords get glued to the previous token
 * (e.g., {@code SYSDATEWHERE}, {@code CURRENT_TIMESTAMPAND}).
 *
 * <p>Rules (compatible with legacy behavior):
 * <ul>
 *   <li>Do not touch strings/comments/MyBatis params/#hash# tokens</li>
 *   <li>Only split when a known "safe prefix token" is immediately followed by a keyword</li>
 *   <li>Clause-starter keywords are split with a newline; others with a space</li>
 * </ul>
 */
final class GluedKeywordFixer {

    private GluedKeywordFixer() {}

    static String fixAfterSpecialTokens(String sql) {
        if (sql == null || sql.isEmpty()) return sql;

        StringBuilder out = new StringBuilder(sql.length() + 64);
        SqlScan st = new SqlScan(sql);

        while (st.hasNext()) {
            // preserve
            if (st.peekIsLineComment()) { out.append(st.readLineComment()); continue; }
            if (st.peekIsBlockComment()) { out.append(st.readBlockComment()); continue; }
            if (st.peekIsSingleQuotedString()) { out.append(st.readSingleQuotedString()); continue; }
            if (st.peekIsDoubleQuotedString()) { out.append(st.readDoubleQuotedString()); continue; }
            if (st.peekIsMyBatisParam()) { out.append(st.readMyBatisParam()); continue; }
            if (st.peekIsHashToken()) { out.append(st.readHashToken()); continue; }
            if (st.peekIsCdata()) { out.append(st.readCdata()); continue; }
            if (st.peekIsXmlTag()) { out.append(st.readXmlTag()); continue; }

            char ch = st.peek();

            if (SqlIdentifierUtil.isIdentStart(ch)) {
                String word = st.readWord();
                if (!word.isEmpty()) {
                    out.append(splitGluedTokenIfNeeded(word));
                    continue;
                }
            }

            out.append(st.read());
        }

        return out.toString();
    }

    private static String splitGluedTokenIfNeeded(String token) {
        if (token == null || token.isEmpty()) return token;

        String up = token.toUpperCase(Locale.ROOT);

        for (String prefixUp : GLUE_SAFE_PREFIXES_UPPER) {
            if (!up.startsWith(prefixUp)) continue;
            if (up.length() <= prefixUp.length()) continue;

            String restUp = up.substring(prefixUp.length());

            for (String kwUp : GLUE_KEYWORDS_UPPER_SORTED) {
                if (!restUp.startsWith(kwUp)) continue;

                int pLen = prefixUp.length();
                int kLen = kwUp.length();

                // preserve original casing
                String pRaw = token.substring(0, Math.min(pLen, token.length()));
                String kRaw = token.substring(Math.min(pLen, token.length()),
                        Math.min(pLen + kLen, token.length()));
                String rem = token.substring(Math.min(pLen + kLen, token.length()));

                String sep1 = isClauseNewlineKeyword(kwUp) ? "\n" : " ";

                if (rem == null || rem.isEmpty()) return pRaw + sep1 + kRaw;
                return pRaw + sep1 + kRaw + " " + rem;
            }
        }

        return token;
    }

    private static boolean isClauseNewlineKeyword(String kwUp) {
        if (kwUp == null) return false;
        switch (kwUp) {
            case "WHERE":
            case "FROM":
            case "GROUP":
            case "ORDER":
            case "HAVING":
            case "UNION":
            case "INTERSECT":
            case "EXCEPT":
            case "MINUS":
            case "VALUES":
            case "SET":
            case "JOIN":
            case "INTO":
                return true;
            default:
                return false;
        }
    }

    private static final String[] GLUE_SAFE_PREFIXES_UPPER = new String[] {
            "SYSDATE",
            "SYSTIMESTAMP",
            "LOCALTIMESTAMP",
            "CURRENT_DATE",
            "CURRENT_TIMESTAMP",
            "CURRENT_TIME",
            "NOW",
            "GETDATE",
            "END"
    };

    private static final String[] GLUE_KEYWORDS_UPPER_SORTED = new String[] {
            "INTERSECT",
            "RETURNING",
            "HAVING",
            "WHERE",
            "GROUP",
            "ORDER",
            "UNION",
            "EXCEPT",
            "MINUS",
            "FROM",
            "VALUES",
            "JOIN",
            "INTO",
            "WHEN",
            "THEN",
            "ELSE",
            "SET",
            "AND",
            "OR",
            "ON",
            "BY"
    };
}
