package infra.output;

import domain.output.SqlOutputWriter;

import java.nio.file.Path;

/**
 * No-op implementation (feature toggle).
 */
public final class NullSqlOutputWriter implements SqlOutputWriter {
    @Override
    public void write(Path outDir, String serviceClass, String namespace, String sqlId, String sqlText) {
        // intentionally no-op
    }
}