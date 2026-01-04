package infra.text;

import domain.text.SqlTextProvider;
import domain.text.SqlTextResolution;


/**
 * CSV only SQL text provider.
 * <p>
 * Registry fallback을 완전히 끈(dry-run/성능 최적화) 모드에서 사용한다.
 */
public final class CsvOnlySqlTextProvider implements SqlTextProvider {

    @Override
    public SqlTextResolution resolve(String namespace, String sqlId, String csvSqlText) {
        if (csvSqlText == null || csvSqlText.trim()
                .isEmpty()) {
            return SqlTextResolution.empty("CSV", "CSV_SQL_TEXT_EMPTY");
        }
        return SqlTextResolution.ofCsv(csvSqlText);
    }
}