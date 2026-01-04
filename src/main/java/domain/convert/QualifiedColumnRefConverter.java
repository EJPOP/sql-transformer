package domain.convert;

import domain.mapping.ColumnMapping;
import domain.mapping.ColumnMappingRegistry;
import domain.model.ConversionContext;
import domain.model.ConversionWarning;
import domain.model.ConversionWarningSink;
import domain.model.WarningCode;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Converts qualified column references:
 * - ASIS_TABLE.ASIS_COL -> TOBE_TABLE.TOBE_COL
 * - alias.ASIS_COL      -> alias.TOBE_COL (alias preserved)
 *
 * Preserves:
 * - comments, strings, xml tags, mybatis params, hash tokens, CDATA
 */
final class QualifiedColumnRefConverter {

    private final ColumnMappingRegistry registry;

    QualifiedColumnRefConverter(ColumnMappingRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    private static String render(String schema, String a, String b) {
        if (schema == null || schema.isBlank()) return a + "." + b;
        return schema + "." + a + "." + b;
    }

    private static String findAliasTable(Map<String, String> aliasTableMap, String alias) {
        if (aliasTableMap == null || aliasTableMap.isEmpty() || alias == null) return null;
        for (Map.Entry<String, String> e : aliasTableMap.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(alias)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String safeId(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String upperSafe(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    private static String upperKey(String t, String c) {
        return (t == null ? "" : t.trim().toUpperCase(Locale.ROOT))
                + "."
                + (c == null ? "" : c.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean looksLikeAsisColumnId(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 80) return false;
        if (t.indexOf('_') < 0) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!(Character.isUpperCase(c) || Character.isDigit(c) || c == '_')) return false;
        }
        return true;
    }

    String convert(String sql, Map<String, String> aliasTableMap, ConversionContext ctx, ConversionWarningSink sink) {
        if (sql == null || sql.isEmpty()) return sql;

        ConversionWarningSink warnSink = (sink == null) ? ConversionWarningSink.none() : sink;

        StringBuilder out = new StringBuilder(sql.length() + 64);
        SqlScan st = new SqlScan(sql);

        while (st.hasNext()) {
            // preserve blocks
            if (st.peekIsLineComment()) { out.append(st.readLineComment()); continue; }
            if (st.peekIsBlockComment()) { out.append(st.readBlockComment()); continue; }
            if (st.peekIsSingleQuotedString()) { out.append(st.readSingleQuotedString()); continue; }
            if (st.peekIsDoubleQuotedString()) { out.append(st.readDoubleQuotedString()); continue; }
            if (st.peekIsCdata()) {
                String raw = st.readCdata();
                out.append(CdataUtil.transform(raw, inner -> convert(inner, aliasTableMap, ctx, warnSink)));
                continue;
            }
            if (st.peekIsXmlTag()) { out.append(st.readXmlTag()); continue; }
            if (st.peekIsMyBatisParam()) { out.append(st.readMyBatisParam()); continue; }
            if (st.peekIsHashToken()) { out.append(st.readHashToken()); continue; }

            // keep subquery blocks
            if (st.peek() == '(' && st.peekParenStartsWithSelect()) {
                out.append(st.readParenBlock());
                continue;
            }

            char ch = st.peek();

            if (SqlIdentifierUtil.isIdentStart(ch)) {
                String a = st.readWord();
                if (a.isEmpty()) continue;

                if (st.peek() == '.') {
                    st.read(); // consume '.'

                    // a.b or a.b.c
                    if (!SqlIdentifierUtil.isIdentStart(st.peek())) {
                        out.append(a).append('.');
                        continue;
                    }

                    String b = st.readWord();
                    if (b.isEmpty()) {
                        out.append(a).append('.');
                        continue;
                    }

                    if (st.peek() == '.') {
                        // schema.table.col
                        st.read(); // consume second '.'
                        if (!SqlIdentifierUtil.isIdentStart(st.peek())) {
                            out.append(a).append('.').append(b).append('.');
                            continue;
                        }
                        String c = st.readWord();
                        String replaced = replaceQualified(a, b, c, aliasTableMap, ctx, warnSink);
                        out.append(replaced);
                        continue;
                    }

                    // table.col or alias.col
                    String replaced = replaceQualified(null, a, b, aliasTableMap, ctx, warnSink);
                    out.append(replaced);
                    continue;
                }

                out.append(a);
                continue;
            }

            out.append(st.read());
        }

        return out.toString();
    }

    /**
     * Replace:
     * - (schemaPrefix != null) => schemaPrefix.tableOrAlias.col
     * - (schemaPrefix == null) => tableOrAlias.col
     *
     * Rules:
     *  1) If tableOrAlias is an alias (exists in aliasTableMap), keep alias and convert column by (asisTable, col).
     *  2) If tableOrAlias is an ASIS table id, convert table id to TOBE table id when mapping exists.
     *  3) If tableOrAlias.asisCol maps to tobeTable.tobeCol, convert both.
     *  4) If only table mapping exists (no column mapping), convert table only and keep the column.
     */
    private String replaceQualified(String schemaPrefix,
                                    String tableOrAlias,
                                    String col,
                                    Map<String, String> aliasTableMap,
                                    ConversionContext ctx,
                                    ConversionWarningSink sink) {

        String qualRaw = tableOrAlias;
        String colRaw = col;

        String qualU = upperSafe(qualRaw);
        String colU = upperSafe(colRaw);

        // 0) Alias mapping: alias -> asisTable (alias is preserved)
        String asisTableFromAlias = findAliasTable(aliasTableMap, qualRaw);
        if (asisTableFromAlias != null && !asisTableFromAlias.isBlank()) {
            String asisTableU = upperSafe(asisTableFromAlias);
            ColumnMapping m = (asisTableU == null || colU == null) ? null : registry.find(asisTableU, colU);

            if (m == null) {
                if (looksLikeAsisColumnId(colU)) {
                    sink.warn(new ConversionWarning(
                            WarningCode.COLUMN_MAPPING_NOT_FOUND,
                            ctx == null ? "" : ctx.getServiceClass(),
                            ctx == null ? "" : ctx.getNamespace(),
                            ctx == null ? "" : ctx.getSqlId(),
                            "column mapping not found (qualified alias ref)",
                            upperKey(asisTableU, colU)
                    ));
                }
                return render(schemaPrefix, qualRaw, colRaw); // keep alias.col
            }

            if (registry.isDeleted(m)) {
                sink.warn(new ConversionWarning(
                        WarningCode.DELETED_COLUMN_SKIPPED,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "deleted column mapping (qualified alias ref)",
                        upperKey(asisTableU, colU)
                ));
                return render(schemaPrefix, qualRaw, colRaw);
            }

            String tobeCol = safeId(m.tobeColumnId);
            if (tobeCol == null) return render(schemaPrefix, qualRaw, colRaw);

            return render(schemaPrefix, qualRaw, tobeCol); // alias 유지
        }

        // 1) Direct table mapping (qualifier is an ASIS table)
        String tobeTable = (qualU == null) ? null : registry.findTobeTableId(qualU);

        ColumnMapping mDirect = (qualU == null || colU == null) ? null : registry.find(qualU, colU);
        if (mDirect != null) {
            String tableOut = (tobeTable != null && !tobeTable.isBlank()) ? tobeTable : qualRaw;

            if (registry.isDeleted(mDirect)) {
                sink.warn(new ConversionWarning(
                        WarningCode.DELETED_COLUMN_SKIPPED,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "deleted column mapping (qualified ref)",
                        upperKey(qualU, colU)
                ));
                // deleted column: keep column, but still allow table conversion
                return render(schemaPrefix, tableOut, colRaw);
            }

            String tobeCol = safeId(mDirect.tobeColumnId);
            if (tobeCol == null) {
                // table-only conversion is still useful
                return render(schemaPrefix, tableOut, colRaw);
            }

            return render(schemaPrefix, tableOut, tobeCol);
        }

        // 2) No column mapping. If table mapping exists, convert table only.
        if (tobeTable != null && !tobeTable.isBlank()) {
            if (looksLikeAsisColumnId(colU)) {
                sink.warn(new ConversionWarning(
                        WarningCode.COLUMN_MAPPING_NOT_FOUND,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "column mapping not found (qualified ref)",
                        upperKey(qualU, colU)
                ));
            }
            return render(schemaPrefix, tobeTable, colRaw);
        }

        return render(schemaPrefix, qualRaw, colRaw);
    }
}
