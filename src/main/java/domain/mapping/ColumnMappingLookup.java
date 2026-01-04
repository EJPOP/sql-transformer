package domain.mapping;

/**
 * Minimal read-only abstraction for column mapping lookup.
 *
 * <p>Used to keep convert/domain layer testable without requiring XLSX loaders.
 */
public interface ColumnMappingLookup {

    ColumnMapping find(String table, String column);

    ColumnMapping findByColumnOnly(String column);

    ColumnMapping findByTobeColumnOnly(String tobeColumnId);

    ColumnMapping findByTobeOnAsisTable(String asisTableId, String tobeColumnId);

    boolean isDeleted(ColumnMapping m);
}
