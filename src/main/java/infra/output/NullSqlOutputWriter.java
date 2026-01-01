package infra.output;

import java.nio.file.Path;

import domain.output.SqlOutputWriter;

/** No-op implementation (feature toggle). */
public final class NullSqlOutputWriter implements SqlOutputWriter {
    @Override
    public void write(Path outDir, String serviceClass, String namespace, String sqlId, String sqlText) {
        // intentionally no-op
    }
}