package domain.text;

/**
 * SQL 원문(sql_text)을 어디서 가져올지 결정한다.
 * <ul>
 *   <li>{@link #CSV}: service_sql_xref.csv의 sql_text만 사용</li>
 *   <li>{@link #CSV_FIRST}: CSV 우선, 비어있으면 XML(SqlStatementRegistry) fallback</li>
 *   <li>{@link #XML}: XML(SqlStatementRegistry)만 사용</li>
 *   <li>{@link #XML_FIRST}: XML 우선, 없으면 CSV fallback</li>
 * </ul>
 */
public enum SqlTextSource {
    CSV,
    CSV_FIRST,
    XML,
    XML_FIRST
}
