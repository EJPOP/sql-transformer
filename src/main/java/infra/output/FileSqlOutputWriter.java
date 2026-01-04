package infra.output;

import domain.output.SqlFileNamePolicy;
import domain.output.SqlOutputWriter;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link SqlOutputWriter} that stores generated SQL into files.
 * <p>
 * Output layout (NEW):
 * <outDir>/<serviceClass>/<namespace>/<sqlId>.sql
 */
public final class FileSqlOutputWriter implements SqlOutputWriter {

    private final AliasSqlFileWriter delegate;

    // ✅ 기존 팩토리 시그니처 유지: new FileSqlOutputWriter(new AliasSqlFileWriter())
    public FileSqlOutputWriter(AliasSqlFileWriter delegate) {
        this.delegate = (delegate == null) ? new AliasSqlFileWriter() : delegate;
    }

    private static String simpleClassName(String serviceClass) {
        String s = serviceClass == null ? "" : serviceClass.trim();
        if (s.isEmpty()) return "unknownService";
        int p = s.lastIndexOf('.');
        if (p >= 0 && p + 1 < s.length()) return s.substring(p + 1);
        return s;
    }

    private static String safeDir(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) s = fallback;
        // Windows 폴더 불가 문자 제거
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        s = s.replaceAll("\\s+", "_");
        if (s.startsWith(".")) s = "_" + s.substring(1);
        return s;
    }

    @Override
    public void write(Path outDir, String serviceClass, String namespace, String sqlId, String sqlText) {
        if (outDir == null) throw new IllegalArgumentException("outDir is null");

        String svcDir = safeDir(simpleClassName(serviceClass), "unknownService");
        // namespace는 폴더에 들어가므로 Mapper simpleName으로 정규화 (FQCN이면 뒤 토큰 사용)
        String nsDir = safeDir(SqlFileNamePolicy.extractMapperSimpleName(namespace), "UnknownMapper");
        Path targetDir = outDir.resolve(svcDir)
                .resolve(nsDir);

        try {
            Files.createDirectories(targetDir);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create output directory: " + targetDir, e);
        }

        // delegate 내부에서 파일명을 SqlFileNamePolicy로 생성함 (우리가 build를 sqlId.sql로 바꿀 예정)
        delegate.write(targetDir, serviceClass, namespace, sqlId, sqlText);
    }
}
