package domain.text;

import mybatis.SqlStatementRegistry;


/**
 * SQL 텍스트를 얻는 책임을 캡슐화한다.
 * <pre>
 * - 1순위: CSV의 sql_text
 * - 2순위: (옵션) SqlStatementRegistry fallback
 * </pre>
 */
public interface SqlTextProvider {
    SqlTextResolution resolve(String namespace, String sqlId, String csvSqlText);
}