package domain.convert;

import domain.mapping.ColumnMapping;
import domain.mapping.ColumnMappingRegistry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Infers and applies MyBatis #PARAM# renames based on column mapping.
 */
final class MybatisParamRenamer {

    private final ColumnMappingRegistry registry;

    MybatisParamRenamer(ColumnMappingRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    String renameParamToken(String token, Map<String, String> paramRenameMap) {
        if (token == null) return null;
        if (paramRenameMap == null || paramRenameMap.isEmpty()) return token;

        if (token.length() < 3) return token;
        String name = token.substring(1, token.length() - 1)
                .trim();
        String u = name.toUpperCase();

        String newName = paramRenameMap.get(u);
        if (newName == null) return token;

        return "#" + newName + "#";
    }

    Map<String, String> buildParamRenameMap(String sql, Map<String, String> aliasTableMap) {
        if (sql == null || sql.isEmpty()) return Collections.emptyMap();

        Map<String, String> map = new HashMap<>();

        Pattern p = Pattern.compile("(?i)\\b([A-Z0-9_]{1,30})\\s*\\.\\s*([A-Z0-9_]{1,30})\\s*=\\s*#\\s*([A-Z0-9_]{1,60})\\s*#");
        Matcher m = p.matcher(sql.toUpperCase());
        while (m.find()) {
            String a = m.group(1);
            String col = m.group(2);
            String param = m.group(3);

            String table = aliasTableMap.get(a);
            ColumnMapping cm = (table != null) ? registry.find(table, col) : null;
            if (cm == null) cm = registry.findByColumnOnly(col);
            if (cm == null) continue;
            if (registry.isDeleted(cm)) continue;

            String tobe = (cm.tobeColumnId == null) ? "" : cm.tobeColumnId.trim();
            if (tobe.isBlank()) continue;

            map.put(param, tobe);
        }

        Pattern p2 = Pattern.compile("(?i)\\b([A-Z0-9_]{1,30})\\s*=\\s*#\\s*([A-Z0-9_]{1,60})\\s*#");
        Matcher m2 = p2.matcher(sql.toUpperCase());
        while (m2.find()) {
            String col = m2.group(1);
            String param = m2.group(2);

            ColumnMapping cm = registry.findByColumnOnly(col);
            if (cm == null) continue;
            if (registry.isDeleted(cm)) continue;

            String tobe = (cm.tobeColumnId == null) ? "" : cm.tobeColumnId.trim();
            if (tobe.isBlank()) continue;

            map.put(param, tobe);
        }

        return map;
    }
}