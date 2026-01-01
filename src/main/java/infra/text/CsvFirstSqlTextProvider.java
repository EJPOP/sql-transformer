package infra.text;

import domain.sql.SqlStatement;
import domain.text.SqlTextProvider;

import domain.text.SqlTextResolution;

import mybatis.SqlStatementRegistry;

/**
 * CSV 우선 + Registry fallback 구현체.
 * - 문자열 리터럴 훼손 금지 원칙을 지키기 위해, 여기서는 "선택"만 하고 변환/파싱은 하지 않는다.
 */
public final class CsvFirstSqlTextProvider implements SqlTextProvider {

    private final SqlStatementRegistry registry;

    public CsvFirstSqlTextProvider(SqlStatementRegistry registry) {
        this.registry = registry;
    }

    @Override
    public SqlTextResolution resolve(String namespace, String sqlId, String csvSqlText) {
        if (!isBlank(csvSqlText)) {
            return SqlTextResolution.ofCsv(csvSqlText);
        }
        if (registry == null) {
            return SqlTextResolution.empty();
        }

        try {
            SqlStatement stmt = registry.get(namespace, sqlId);
            if (stmt == null) return SqlTextResolution.empty();

            String txt = stmt.getSqlText();
            if (isBlank(txt)) return SqlTextResolution.empty();

            // mapperFile은 String/Path 어느 타입이든 toString()으로 안전하게 처리
            Object mf = null;
            try { mf = stmt.getMapperFile(); } catch (Exception ignored) {}
            String src = (mf == null) ? null : String.valueOf(mf);

            return SqlTextResolution.ofFallback(txt, src);
        } catch (Exception ignored) {
            return SqlTextResolution.empty();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}