package infra.mapping;

import domain.mapping.ColumnMapping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CSV loader for column mapping.
 *
 * <p>This exists mainly to support "directory-based" mapping input
 * (e.g. mapping_column.csv). Header names can be either Korean or English.</p>
 */
public final class ColumnMappingCsvLoader {

    private static String get(List<String> cols, Map<String, Integer> idx, String... keys) {
        for (String k : keys) {
            Integer i = idx.get(normalizeKey(k));
            if (i != null && i >= 0 && i < cols.size()) return cols.get(i);
        }
        return "";
    }

    private static Map<String, Integer> buildHeaderIndex(List<String> headers) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            if (h == null) continue;
            m.put(normalizeKey(h), i);
        }
        return m;
    }

    private static String normalizeKey(String s) {
        if (s == null) return "";
        return s.trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "");
    }

    // basic CSV splitting (supports quoted values with commas)
    private static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
                continue;
            }
            if (c == ',' && !inQuote) {
                out.add(sb.toString());
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }

    private static String stripBom(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim()
                .isEmpty();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Load mappings from CSV (UTF-8, BOM tolerated).
     */
    public List<ColumnMapping> load(Path csvPath) {
        if (csvPath == null) throw new IllegalArgumentException("csvPath is null");
        if (!Files.exists(csvPath)) throw new IllegalArgumentException("mapping csv not found: " + csvPath);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(csvPath), StandardCharsets.UTF_8))) {
            String headerLine = br.readLine();
            if (headerLine == null) return Collections.emptyList();

            // handle UTF-8 BOM in the first header cell
            headerLine = stripBom(headerLine);

            List<String> headers = splitCsvLine(headerLine);
            Map<String, Integer> idx = buildHeaderIndex(headers);

            List<ColumnMapping> out = new ArrayList<>(4096);

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> cols = splitCsvLine(line);

                String subjectArea = get(cols, idx, "subjectArea", "주제영역");
                String asisTableId = get(cols, idx, "asisTableId", "현행테이블ID");
                String asisTableName = get(cols, idx, "asisTableName", "현행테이블명");
                String asisColId = get(cols, idx, "asisColumnId", "현행컬럼ID");
                String asisColName = get(cols, idx, "asisColumnName", "현행컬럼명");

                String tobeTableId = get(cols, idx, "tobeTableId", "테이블ID");
                String tobeTableName = get(cols, idx, "tobeTableName", "테이블명");
                String tobeColId = get(cols, idx, "tobeColumnId", "컬럼ID");
                String tobeColName = get(cols, idx, "tobeColumnName", "컬럼명");

                // ignore empty rows
                if (isBlank(asisTableId) && isBlank(asisColId) && isBlank(tobeTableId) && isBlank(tobeColId)) continue;

                out.add(new ColumnMapping(
                        nullToEmpty(subjectArea),
                        nullToEmpty(asisTableId),
                        nullToEmpty(asisTableName),
                        nullToEmpty(asisColId),
                        nullToEmpty(asisColName),
                        nullToEmpty(tobeTableId),
                        nullToEmpty(tobeTableName),
                        nullToEmpty(tobeColId),
                        nullToEmpty(tobeColName)
                ));
            }

            return out;
        } catch (Exception e) {
            throw new IllegalStateException("failed to load mapping csv: " + csvPath, e);
        }
    }
}
