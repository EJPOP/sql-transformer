package domain.convert;

import domain.mapping.ColumnMapping;
import domain.mapping.ColumnMappingRegistry;
import domain.model.ConversionContext;
import domain.model.ConversionWarning;
import domain.model.ConversionWarningSink;
import domain.model.WarningCode;

import java.util.*;

/**
 * TOBE 모드에서만 적용되는 fallback 변환.
 *
 * <p>WHERE/ON/HAVING/GROUP BY/ORDER BY/SET 영역에 별칭 없는 ASIS 컬럼ID가 남는 케이스를
 * FROM/JOIN에서 수집한 ASIS 테이블 후보를 기준으로 유일하게 매핑될 때만 치환한다.
 * (모호하면 치환하지 않음)</p>
 */
final class BareColumnClauseConverter {

    private final ColumnMappingRegistry registry;

    BareColumnClauseConverter(ColumnMappingRegistry registry) {
        this.registry = registry;
    }

    private static boolean isTargetClause(Clause c) {
        return c == Clause.WHERE || c == Clause.ON || c == Clause.HAVING
                || c == Clause.GROUP_BY || c == Clause.ORDER_BY || c == Clause.SET;
    }

    private static boolean isClauseBoundaryKeyword(String u) {
        return "FROM".equals(u)
                || "UNION".equals(u) || "INTERSECT".equals(u) || "EXCEPT".equals(u) || "MINUS".equals(u)
                || "CONNECT".equals(u) || "START".equals(u) || "FETCH".equals(u) || "FOR".equals(u);
    }

    /**
     * ASIS 컬럼ID처럼 보이는지(보수적)
     * - 대문자/숫자/_ 로만 구성
     * - '_' 포함
     */
    private static boolean looksLikeAsisColumnId(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        if (t.length() > 80) return false;

        if (t.indexOf('_') < 0) return false;

        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!(Character.isUpperCase(c) || Character.isDigit(c) || c == '_')) return false;
        }
        return true;
    }

    String convertBareColumnsInClausesToTobe(String sql) {
        return convertBareColumnsInClausesToTobe(sql, null, ConversionWarningSink.none());
    }

    String convertBareColumnsInClausesToTobe(String sql, ConversionContext ctx, ConversionWarningSink sink) {
        if (sql == null || sql.isEmpty()) return sql;

        ConversionWarningSink warnSink = (sink == null) ? ConversionWarningSink.none() : sink;


        // FROM/JOIN 기준 ASIS 테이블 후보 수집 (테이블명이 이미 tobe로 바뀌기 전 단계에서 수행)
        List<String> asisTables = collectAsisTableIds(sql);
        if (asisTables.isEmpty()) return sql;

        StringBuilder out = new StringBuilder(sql.length() + 64);

        Clause clause = Clause.NONE;
        boolean pendingGroup = false;
        boolean pendingOrder = false;

        SqlScan st = new SqlScan(sql);
        int depth = 0;

        while (st.hasNext()) {
            // preserve
            if (st.peekIsLineComment()) {
                out.append(st.readLineComment());
                continue;
            }
            if (st.peekIsBlockComment()) {
                out.append(st.readBlockComment());
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                out.append(st.readSingleQuotedString());
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                out.append(st.readDoubleQuotedString());
                continue;
            }
            if (st.peekIsCdata()) {
                String raw = st.readCdata();
                out.append(CdataUtil.transform(raw, inner -> convertBareColumnsInClausesToTobe(inner, ctx, warnSink)));
                continue;
            }
            if (st.peekIsXmlTag()) {
                out.append(st.readXmlTag());
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                out.append(st.readMyBatisParam());
                continue;
            }
            if (st.peekIsHashToken()) {
                out.append(st.readHashToken());
                continue;
            }

            char ch = st.peek();

            // depth
            if (ch == '(') {
                // (SELECT ...) 같은 서브쿼리는 통째로 copy
                if (st.peekParenStartsWithSelect()) {
                    out.append(st.readParenBlock());
                    continue;
                }
                depth++;
                out.append(st.read());
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                out.append(st.read());
                continue;
            }

            // word
            if (SqlIdentifierUtil.isIdentStart(ch)) {
                String word = st.readWord();
                if (word.isEmpty()) continue;

                String u = word.toUpperCase(Locale.ROOT);

                // GROUP BY / ORDER BY
                if (pendingGroup) {
                    pendingGroup = false;
                    if ("BY".equals(u) && depth == 0) clause = Clause.GROUP_BY;
                    out.append(word);
                    continue;
                }
                if (pendingOrder) {
                    pendingOrder = false;
                    if ("BY".equals(u) && depth == 0) clause = Clause.ORDER_BY;
                    out.append(word);
                    continue;
                }

                // clause enter/exit (top-level에서만 안정적으로 전환)
                if (depth == 0) {
                    if ("WHERE".equals(u)) {
                        clause = Clause.WHERE;
                        out.append(word);
                        continue;
                    }
                    if ("ON".equals(u)) {
                        clause = Clause.ON;
                        out.append(word);
                        continue;
                    }
                    if ("HAVING".equals(u)) {
                        clause = Clause.HAVING;
                        out.append(word);
                        continue;
                    }
                    if ("SET".equals(u)) {
                        clause = Clause.SET;
                        out.append(word);
                        continue;
                    }

                    if ("GROUP".equals(u)) {
                        pendingGroup = true;
                        out.append(word);
                        continue;
                    }
                    if ("ORDER".equals(u)) {
                        pendingOrder = true;
                        out.append(word);
                        continue;
                    }

                    // boundary: 새로운 큰 절로 넘어가면 clause 종료
                    if (isClauseBoundaryKeyword(u)) {
                        clause = Clause.NONE;
                        out.append(word);
                        continue;
                    }

                    // JOIN 나오면 ON 절 종료로도 간주
                    if ("JOIN".equals(u) && clause == Clause.ON) {
                        clause = Clause.NONE;
                        out.append(word);
                        continue;
                    }
                }

                // qualified identifier (alias.col) => 그대로 둠
                if (st.peek() == '.') {
                    out.append(word);
                    continue;
                }

                // fallback 치환 대상
                if (isTargetClause(clause) && looksLikeAsisColumnId(word)) {
                    String mapped = resolveTobeColumnFromTables(asisTables, word, ctx, warnSink);
                    if (mapped != null && !mapped.isBlank()) {
                        out.append(mapped);
                        continue;
                    }
                }

                out.append(word);
                continue;
            }

            out.append(st.read());
        }

        return out.toString();
    }

    /**
     * 테이블 후보들 기준으로 컬럼 매핑이 유일할 때만 치환
     * - 이미 TOBE 컬럼으로 인식되는 경우(asisTable + tobeCol)면 안전하게 스킵
     */
    private String resolveTobeColumnFromTables(List<String> asisTables, String asisCol, ConversionContext ctx,
                                               ConversionWarningSink sink) {
        if (asisTables == null || asisTables.isEmpty()) return null;
        if (asisCol == null || asisCol.isBlank()) return null;

        // already tobe? => do not touch
        for (String t : asisTables) {
            if (t == null || t.isBlank()) continue;
            if (registry.findByTobeOnAsisTable(t, asisCol) != null) {
                return null;
            }
        }

        String found = null;
        boolean sawDeleted = false;
        int matched = 0;

        for (String t : asisTables) {
            if (t == null || t.isBlank()) continue;

            ColumnMapping m = registry.find(t, asisCol);
            if (m == null) continue;

            matched++;

            if (registry.isDeleted(m)) {
                sawDeleted = true;
                continue;
            }

            String tobe = (m.tobeColumnId == null) ? null : m.tobeColumnId.trim();
            if (tobe == null || tobe.isBlank()) continue;

            if (found == null) {
                found = tobe;
            } else if (!found.equalsIgnoreCase(tobe)) {
                // ambiguous
                sink.warn(new ConversionWarning(
                        WarningCode.AMBIGUOUS_COLUMN,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "ambiguous bare column mapping",
                        asisCol
                ));
                return null;
            }
        }

        if (found == null) {
            if (registry.isAsisColumnAmbiguous(asisCol)) {
                sink.warn(new ConversionWarning(
                        WarningCode.AMBIGUOUS_COLUMN,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "ambiguous bare column (column-only index)",
                        asisCol
                ));
                return null;
            }

            if (sawDeleted) {
                sink.warn(new ConversionWarning(
                        WarningCode.DELETED_COLUMN_SKIPPED,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "deleted column in mapping (tobe empty)",
                        asisCol
                ));
                return null;
            }

            if (matched == 0) {
                sink.warn(new ConversionWarning(
                        WarningCode.COLUMN_MAPPING_NOT_FOUND,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "column mapping not found (bare clause)",
                        asisCol
                ));
            }
        }

        return found;
    }

    /**
     * FROM/JOIN 기준 ASIS 테이블ID 후보 수집 (schema.table이면 table만)
     */
    private List<String> collectAsisTableIds(String sql) {
        if (sql == null || sql.isEmpty()) return Collections.emptyList();

        LinkedHashSet<String> set = new LinkedHashSet<>();
        SqlScan st = new SqlScan(sql);

        boolean expectTable = false;
        boolean inFromClause = false;

        boolean seenDelete = false;
        boolean seenInsert = false;
        boolean seenMerge = false;

        while (st.hasNext()) {
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
            if (st.peekIsCdata()) {
                String raw = st.readCdata();
                String inner = CdataUtil.innerOf(raw);
                set.addAll(collectAsisTableIds(inner));
                continue;
            }
            if (st.peekIsXmlTag()) {
                st.readXmlTag();
                continue;
            }

            // DELETE FROM
            if (st.peekWord("DELETE")) {
                st.readWord();
                seenDelete = true;
                expectTable = false;
                inFromClause = false;
                continue;
            }
            if (seenDelete && st.peekWord("FROM")) {
                st.readWord();
                seenDelete = false;
                expectTable = true;
                inFromClause = true;
                continue;
            }

            // INSERT INTO
            if (st.peekWord("INSERT")) {
                st.readWord();
                seenInsert = true;
                expectTable = false;
                inFromClause = false;
                continue;
            }
            if (seenInsert && st.peekWord("INTO")) {
                st.readWord();
                seenInsert = false;
                expectTable = true;
                inFromClause = false;
                continue;
            }

            // MERGE INTO
            if (st.peekWord("MERGE")) {
                st.readWord();
                seenMerge = true;
                expectTable = false;
                inFromClause = false;
                continue;
            }
            if (seenMerge && st.peekWord("INTO")) {
                st.readWord();
                seenMerge = false;
                expectTable = true;
                inFromClause = false;
                continue;
            }

            // UPDATE
            if (st.peekWord("UPDATE")) {
                st.readWord();
                expectTable = true;
                inFromClause = false;
                continue;
            }

            // FROM
            if (st.peekWord("FROM")) {
                st.readWord();
                expectTable = true;
                inFromClause = true;
                continue;
            }

            // JOIN
            if (st.peekWord("JOIN")) {
                st.readWord();
                expectTable = true;
                inFromClause = true;
                continue;
            }
            if (st.peekWord("LEFT") || st.peekWord("RIGHT") || st.peekWord("FULL") || st.peekWord("INNER")
                    || st.peekWord("OUTER") || st.peekWord("CROSS")) {
                st.readWord();
                continue;
            }

            // FROM 종료 키워드
            if (inFromClause && (st.peekWord("WHERE") || st.peekWord("GROUP") || st.peekWord("ORDER")
                    || st.peekWord("HAVING") || st.peekWord("UNION") || st.peekWord("INTERSECT")
                    || st.peekWord("EXCEPT") || st.peekWord("MINUS"))) {
                inFromClause = false;
                expectTable = false;
                st.readWord();
                continue;
            }

            if (expectTable) {
                if (st.peek() == '(') {
                    st.readParenBlock();
                    expectTable = false;
                    continue;
                }

                if (SqlIdentifierUtil.isIdentStart(st.peek())) {
                    String tok = st.readIdentifier(); // schema.table
                    String last = SqlIdentifierUtil.lastPart(tok);
                    if (last != null && !last.isBlank()) set.add(last.toUpperCase(Locale.ROOT));
                    expectTable = false;
                    continue;
                }

                st.read();
                continue;
            }

            if (inFromClause && st.peek() == ',') {
                st.read();
                expectTable = true;
                continue;
            }

            st.read();
        }

        return new ArrayList<>(set);
    }

    private enum Clause {
        NONE, WHERE, ON, HAVING, GROUP_BY, ORDER_BY, SET
    }
}