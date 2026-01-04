package domain.text;

/**
 * SQL 텍스트를 얻는 책임을 캡슐화한다.
 * <p>
 * 호출부는 "namespace, id"를 기준으로 SQL 원문을 얻고, 어떤 소스(CSV/XML)로
 * 해결되었는지(fallback 여부/출처)만 확인한다.
 */
public interface SqlTextProvider {
    SqlTextResolution resolve(String namespace, String sqlId, String csvSqlText);
}