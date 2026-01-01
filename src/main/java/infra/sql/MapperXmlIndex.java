package infra.sql;

import domain.convert.SqlStatement;

import java.nio.file.Path;
import java.util.*;

public class MapperXmlIndex {

    private final Map<String, SqlStatement> index = new HashMap<>();
    private final MapperXmlLoader loader = new MapperXmlLoader();

    /**
     * namespace.sqlId → SqlStatement
     */
    public void buildIndex(List<Path> xmlFiles) {

        if (xmlFiles == null || xmlFiles.isEmpty()) {
            return;
        }

        // ✅ Files.walk() 반환 순서는 환경에 따라 달라질 수 있어
        // "중복이면 최초 1건 유지" 정책이 흔들리지 않도록 정렬 후 처리
        List<Path> ordered = new ArrayList<>(xmlFiles);
        ordered.sort(Comparator.comparing(p -> p.toAbsolutePath()
                .normalize()
                .toString()));

        for (Path xml : ordered) {
            Map<String, SqlStatement> parsed = loader.loadFromFile(xml);

            for (SqlStatement stmt : parsed.values()) {
                String key = stmt.getNamespace() + "." + stmt.getSqlId();

                // 중복 발생 시 최초 1건 유지 (현행 시스템 기준)
                index.putIfAbsent(key, stmt);
            }
        }
    }

    public SqlStatement get(String namespace, String sqlId) {
        return index.get(namespace + "." + sqlId);
    }

    public int size() {
        return index.size();
    }
}