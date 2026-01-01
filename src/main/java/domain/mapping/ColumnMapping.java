package domain.mapping;
/**
 * Column mapping row model.
 *
 * <p>Keep it as a simple immutable DTO (no behavior) so that loader/registry/transformers can share
 * the same type without cyclic dependencies.</p>
 */
public final class ColumnMapping {

    public final String subjectArea;

    public final String asisTableId;
    public final String asisTableName;
    public final String asisColumnId;
    public final String asisColumnName;

    public final String tobeTableId;
    public final String tobeTableName;
    public final String tobeColumnId;
    public final String tobeColumnName;

    public ColumnMapping(
            String subjectArea,
            String asisTableId,
            String asisTableName,
            String asisColumnId,
            String asisColumnName,
            String tobeTableId,
            String tobeTableName,
            String tobeColumnId,
            String tobeColumnName
    ) {
        this.subjectArea = subjectArea;
        this.asisTableId = asisTableId;
        this.asisTableName = asisTableName;
        this.asisColumnId = asisColumnId;
        this.asisColumnName = asisColumnName;
        this.tobeTableId = tobeTableId;
        this.tobeTableName = tobeTableName;
        this.tobeColumnId = tobeColumnId;
        this.tobeColumnName = tobeColumnName;
    }

    @Override
    public String toString() {
        return "ColumnMapping{" +
                "asisTableId='" + asisTableId + '\'' +
                ", asisColumnId='" + asisColumnId + '\'' +
                ", tobeTableId='" + tobeTableId + '\'' +
                ", tobeColumnId='" + tobeColumnId + '\'' +
                '}';
    }
}
