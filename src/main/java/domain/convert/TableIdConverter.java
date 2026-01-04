package domain.convert;

import domain.mapping.ColumnMappingRegistry;
import domain.model.ConversionContext;
import domain.model.ConversionWarning;
import domain.model.ConversionWarningSink;
import domain.model.WarningCode;


/**
 * TOBE 모드: 현행 테이블ID를 TOBE 테이블ID로 치환한다.
 *
 * <p>Only replaces tokens in positions where a table identifier is expected:
 * UPDATE <table>, FROM/JOIN <table>, DELETE FROM <table>, INSERT INTO <table>, MERGE INTO <table>.</p>
 */
final class TableIdConverter {

    private final ColumnMappingRegistry registry;

    TableIdConverter(ColumnMappingRegistry registry) {
        this.registry = registry;
    }

    private static boolean looksLikeAsisTableId(String lastToken) {
        if (lastToken == null) return false;
        String t = lastToken.trim();
        if (t.isEmpty()) return false;
        // Typical naming: TB_..., PTL_..., etc.
        String u = t.toUpperCase();
        String[] prefixes = {"TB", "PTL", "IS", "CTT", "EH", "HE", "EN", "NE", "AM", "BAI", "IR"};
        for (String p : prefixes) {
            if (u.startsWith(p + "_")) return true;
        }
        return false;
    }

    String convertTableIdsToTobe(String sql) {
        return convertTableIdsToTobe(sql, null, ConversionWarningSink.none());
    }

    String convertTableIdsToTobe(String sql, ConversionContext ctx, ConversionWarningSink sink) {
        if (sql == null || sql.isEmpty()) return sql;
        ConversionWarningSink warnSink = (sink == null) ? ConversionWarningSink.none() : sink;


        StringBuilder out = new StringBuilder(sql.length() + 64);
        SqlScan st = new SqlScan(sql);

        boolean expectTable = false;
        boolean inFromClause = false;

        boolean seenDelete = false;
        boolean seenInsert = false;
        boolean seenMerge = false;

        while (st.hasNext()) {
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
            if (st.peekIsMyBatisParam()) {
                out.append(st.readMyBatisParam());
                continue;
            }
            if (st.peekIsHashToken()) {
                out.append(st.readHashToken());
                continue;
            }
            if (st.peekIsCdata()) {
                String raw = st.readCdata();
                out.append(CdataUtil.transform(raw, inner -> convertTableIdsToTobe(inner, ctx, warnSink)));
                continue;
            }
            if (st.peekIsXmlTag()) {
                out.append(st.readXmlTag());
                continue;
            }

            // DELETE FROM
            if (st.peekWord("DELETE")) {
                out.append(st.readWord());
                seenDelete = true;
                expectTable = false;
                inFromClause = false;
                continue;
            }
            if (seenDelete && st.peekWord("FROM")) {
                out.append(st.readWord());
                seenDelete = false;
                expectTable = true;
                inFromClause = true;
                continue;
            }

            // INSERT INTO
            if (st.peekWord("INSERT")) {
                out.append(st.readWord());
                seenInsert = true;
                expectTable = false;
                inFromClause = false;
                continue;
            }
            if (seenInsert && st.peekWord("INTO")) {
                out.append(st.readWord());
                seenInsert = false;
                expectTable = true;
                inFromClause = false;
                continue;
            }

            // MERGE INTO
            if (st.peekWord("MERGE")) {
                out.append(st.readWord());
                seenMerge = true;
                expectTable = false;
                inFromClause = false;
                continue;
            }
            if (seenMerge && st.peekWord("INTO")) {
                out.append(st.readWord());
                seenMerge = false;
                expectTable = true;
                inFromClause = false;
                continue;
            }

            // UPDATE
            if (st.peekWord("UPDATE")) {
                out.append(st.readWord());
                expectTable = true;
                inFromClause = false;
                continue;
            }

            // FROM
            if (st.peekWord("FROM")) {
                out.append(st.readWord());
                expectTable = true;
                inFromClause = true;
                continue;
            }

            // JOIN
            if (st.peekWord("JOIN")) {
                out.append(st.readWord());
                expectTable = true;
                inFromClause = true;
                continue;
            }
            if (st.peekWord("LEFT") || st.peekWord("RIGHT") || st.peekWord("FULL") || st.peekWord("INNER")
                    || st.peekWord("OUTER") || st.peekWord("CROSS")) {
                out.append(st.readWord());
                continue;
            }

            // FROM clause terminator keywords
            if (inFromClause && (st.peekWord("WHERE") || st.peekWord("GROUP") || st.peekWord("ORDER")
                    || st.peekWord("HAVING") || st.peekWord("UNION") || st.peekWord("INTERSECT")
                    || st.peekWord("EXCEPT") || st.peekWord("MINUS"))) {
                inFromClause = false;
                expectTable = false;
                out.append(st.readWord());
                continue;
            }

            if (expectTable) {
                if (st.peek() == '(') {
                    out.append(st.readParenBlock());
                    expectTable = false;
                    continue;
                }

                if (SqlIdentifierUtil.isIdentStart(st.peek())) {
                    String tableToken = st.readIdentifier();
                    out.append(replaceTableTokenToTobe(tableToken, ctx, warnSink));
                    expectTable = false;
                    continue;
                }

                out.append(st.read());
                continue;
            }

            if (inFromClause && st.peek() == ',') {
                out.append(st.read());
                expectTable = true;
                continue;
            }

            out.append(st.read());
        }

        return out.toString();
    }

    private String replaceTableTokenToTobe(String rawTableToken, ConversionContext ctx, ConversionWarningSink sink) {
        if (rawTableToken == null || rawTableToken.isBlank()) return rawTableToken;

        int dot = rawTableToken.lastIndexOf('.');
        String prefix = (dot >= 0) ? rawTableToken.substring(0, dot + 1) : "";
        String last = (dot >= 0) ? rawTableToken.substring(dot + 1) : rawTableToken;

        String mapped = registry.findTobeTableId(last);
        if (mapped == null || mapped.isBlank()) {
            if (looksLikeAsisTableId(last)) {
                sink.warn(new ConversionWarning(
                        WarningCode.TABLE_MAPPING_NOT_FOUND,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "table mapping not found",
                        last
                ));
            }
            return rawTableToken;
        }

        return prefix + mapped;
    }
}