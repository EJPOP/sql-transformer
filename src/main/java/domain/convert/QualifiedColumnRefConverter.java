package domain.convert;

import java.util.Locale;

import java.util.Map;

import java.util.Objects;

import domain.mapping.ColumnMapping;

import domain.mapping.ColumnMappingRegistry;

import domain.model.ConversionContext;

import domain.model.ConversionWarning;

import domain.model.ConversionWarningSink;

import domain.model.WarningCode;

/**
 * Converts qualified column references:
 * - ASIS_TABLE.ASIS_COL -> TOBE_TABLE.TOBE_COL
 * - alias.ASIS_COL -> alias.TOBE_COL (alias preserved)
 *
 * Preserves:
 * - comments, strings, xml tags, mybatis params, hash tokens, CDATA
 */
final class QualifiedColumnRefConverter {

    private final ColumnMappingRegistry registry;

    QualifiedColumnRefConverter(ColumnMappingRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
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
            if (st.peekIsCdata()) { out.append(st.readCdata()); continue; }
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
     */
    private String replaceQualified(String schemaPrefix,
                                    String tableOrAlias,
                                    String col,
                                    Map<String, String> aliasTableMap,
                                    ConversionContext ctx,
                                    ConversionWarningSink sink) {

        String qual = tableOrAlias;
        String asisCol = col;

        // 1) Direct table mapping (qualifier is an ASIS table)
        String tobeTable = registry.findTobeTableId(qual);
        ColumnMapping mDirect = registry.find(qual, asisCol);

        if (mDirect != null) {
            // deleted tobe col -> keep original (renderer may add comment elsewhere)
            if (registry.isDeleted(mDirect)) {
                sink.warn(new ConversionWarning(
                        WarningCode.DELETED_COLUMN_SKIPPED,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "deleted column mapping (qualified ref)",
                        upperKey(qual, asisCol)
                ));
                return render(schemaPrefix, qual, asisCol);
            }

            String tobeCol = safeId(mDirect.tobeColumnId);
            if (tobeCol == null) return render(schemaPrefix, qual, asisCol);

            // if table mapping exists, use it; else keep original qualifier
            String tableOut = (tobeTable != null && !tobeTable.isBlank()) ? tobeTable : qual;

            return render(schemaPrefix, tableOut, tobeCol);
        }

        // 2) Alias mapping: alias -> asisTable
        String asisTableFromAlias = findAliasTable(aliasTableMap, qual);
        if (asisTableFromAlias != null && !asisTableFromAlias.isBlank()) {
            ColumnMapping m = registry.find(asisTableFromAlias, asisCol);
            if (m == null) {
                // missing mapping warning (only for "asis-looking" column ids)
                if (looksLikeAsisColumnId(asisCol)) {
                    sink.warn(new ConversionWarning(
                            WarningCode.COLUMN_MAPPING_NOT_FOUND,
                            ctx == null ? "" : ctx.getServiceClass(),
                            ctx == null ? "" : ctx.getNamespace(),
                            ctx == null ? "" : ctx.getSqlId(),
                            "column mapping not found (qualified alias ref)",
                            upperKey(asisTableFromAlias, asisCol)
                    ));
                }
                return render(schemaPrefix, qual, asisCol); // keep alias.col
            }

            if (registry.isDeleted(m)) {
                sink.warn(new ConversionWarning(
                        WarningCode.DELETED_COLUMN_SKIPPED,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "deleted column mapping (qualified alias ref)",
                        upperKey(asisTableFromAlias, asisCol)
                ));
                return render(schemaPrefix, qual, asisCol);
            }

            String tobeCol = safeId(m.tobeColumnId);
            if (tobeCol == null) return render(schemaPrefix, qual, asisCol);

            // alias 유지
            return render(schemaPrefix, qual, tobeCol);
        }

        return render(schemaPrefix, qual, asisCol);
    }

    private static String render(String schema, String a, String b) {
        if (schema == null || schema.isBlank()) {
            return a + "." + b;
        }
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
}
