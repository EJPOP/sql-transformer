package domain.output;

import java.nio.file.Path;

/** 변환 SQL을 파일로 내보내는 책임. */
public interface SqlOutputWriter {
    void write(Path outDir, String serviceClass, String namespace, String sqlId, String sqlText);
}
