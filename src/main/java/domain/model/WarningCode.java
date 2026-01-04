package domain.model;

/**
 * Standard warning codes for conversion/reporting.
 *
 * <p>Keep the set small and stable. Add codes only when the meaning is clear
 * and actionable for operators.</p>
 */
public enum WarningCode {

    /**
     * sqlsDir is missing while registry fallback is enabled.
     */
    SQLS_DIR_MISSING,

    /**
     * SQL text is empty and the row is skipped.
     */
    SQL_TEXT_EMPTY,

    /**
     * CSV sql_text is empty and registry fallback is disabled, so the row is skipped.
     */
    CSV_SQL_TEXT_EMPTY_FALLBACK_DISABLED,

    /**
     * SQL text was resolved using registry fallback (CSV was blank).
     */
    FALLBACK_USED,

    /**
     * Table id could not be mapped from ASIS to TOBE.
     */
    TABLE_MAPPING_NOT_FOUND,

    /**
     * Column mapping could not be found from ASIS to TOBE.
     */
    COLUMN_MAPPING_NOT_FOUND,

    /**
     * Column mapping is ambiguous (multiple candidates).
     */
    AMBIGUOUS_COLUMN,

    /**
     * Column is marked as deleted (TOBE column id is blank) and was not converted.
     */
    DELETED_COLUMN_SKIPPED,

    /**
     * SQL contains patterns that are not fully supported by the current converter.
     */
    UNSUPPORTED_SYNTAX_DETECTED,

    /**
     * AS-IS table/column ids remain in the converted SQL (residue validation).
     */
    ASIS_RESIDUE_DETECTED,

    /**
     * MyBatis dynamic tags appear to be lost during conversion.
     */
    MYBATIS_TAG_LOST,

    /**
     * MyBatis dynamic tags are unbalanced (e.g., missing closing tag) after conversion.
     */
    MYBATIS_TAG_UNBALANCED,

    /**
     * MyBatis dynamic tag boundaries look suspicious (e.g., comma moved outside <if>).
     */
    MYBATIS_TAG_BOUNDARY_SUSPICIOUS,

    /**
     * Conversion failed with an exception.
     */
    TRANSFORM_ERROR,

    /**
     * Processing time exceeded the configured slow threshold.
     */
    SLOW_SQL
}
