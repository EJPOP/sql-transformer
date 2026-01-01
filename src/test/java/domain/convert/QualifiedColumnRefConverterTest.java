package domain.convert;

import domain.convert.QualifiedColumnRefConverter;
import domain.mapping.ColumnMapping;
import domain.mapping.ColumnMappingRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QualifiedColumnRefConverterTest {

    @Test
    void should_convert_table_dot_column_to_tobe_table_dot_column() {
        Map<String, ColumnMapping> map = new HashMap<>();

        ColumnMapping m = newColumnMapping(
                "BAI_INF_DB_RCV_001", "IF_SEQ",
                "BAI_INF_DB_RCV_001", "LINK_SQNO"
        );
        map.put("BAI_INF_DB_RCV_001.IF_SEQ", m);

        Map<String, String> tableMap = Map.of("BAI_INF_DB_RCV_001", "BAI_INF_DB_RCV_001");
        ColumnMappingRegistry reg = new ColumnMappingRegistry(map, tableMap);

        QualifiedColumnRefConverter c = new QualifiedColumnRefConverter(reg);

        String in = "SELECT BAI_INF_DB_RCV_001.IF_SEQ FROM BAI_INF_DB_RCV_001";
        String out = c.convert(in, Map.of(), null, null);

        assertEquals("SELECT BAI_INF_DB_RCV_001.LINK_SQNO FROM BAI_INF_DB_RCV_001", out);
    }

    @Test
    void should_convert_alias_dot_column_keep_alias() {
        Map<String, ColumnMapping> map = new HashMap<>();

        ColumnMapping m = newColumnMapping(
                "BAI_INF_DB_RCV_001", "IF_SEQ",
                "BAI_INF_DB_RCV_001", "LINK_SQNO"
        );
        map.put("BAI_INF_DB_RCV_001.IF_SEQ", m);

        ColumnMappingRegistry reg = new ColumnMappingRegistry(map, Map.of("BAI_INF_DB_RCV_001", "BAI_INF_DB_RCV_001"));
        QualifiedColumnRefConverter c = new QualifiedColumnRefConverter(reg);

        String in = "SELECT t.IF_SEQ FROM BAI_INF_DB_RCV_001 t";
        String out = c.convert(in, Map.of("t", "BAI_INF_DB_RCV_001"), null, null);

        assertEquals("SELECT t.LINK_SQNO FROM BAI_INF_DB_RCV_001 t", out);
    }

    @Test
    void should_convert_table_change_when_table_mapping_exists() {
        Map<String, ColumnMapping> map = new HashMap<>();

        ColumnMapping m = newColumnMapping(
                "OLD_TBL", "AAA_SEQ",
                "NEW_TBL", "BBB_SQNO"
        );
        map.put("OLD_TBL.AAA_SEQ", m);

        ColumnMappingRegistry reg = new ColumnMappingRegistry(map, Map.of("OLD_TBL", "NEW_TBL"));
        QualifiedColumnRefConverter c = new QualifiedColumnRefConverter(reg);

        String in = "SELECT OLD_TBL.AAA_SEQ FROM OLD_TBL";
        String out = c.convert(in, Map.of(), null, null);

        // 주의: FROM 테이블명 치환은 다른 단계에서 처리될 수 있음 (여기선 qualified ref만 검증)
        assertEquals("SELECT NEW_TBL.BBB_SQNO FROM OLD_TBL", out);
    }

    // ============================================================
    // Reflection helpers (ColumnMapping is package-private friendly)
    // ============================================================

    private static ColumnMapping newColumnMapping(String asisTable, String asisCol, String tobeTable, String tobeCol) {
        ColumnMapping m = instantiate(ColumnMapping.class);

        // ColumnMappingRegistry가 실제로 쓰는 필드명 우선
        setFieldIfExists(m, "asisTableId", asisTable);
        setFieldIfExists(m, "asisColumnId", asisCol);
        setFieldIfExists(m, "tobeTableId", tobeTable);
        setFieldIfExists(m, "tobeColumnId", tobeCol);

        // 혹시 프로젝트에서 다른 이름을 쓰는 경우도 커버(있으면 set)
        setFieldIfExists(m, "tableId", tobeTable);
        setFieldIfExists(m, "columnId", tobeCol);

        return m;
    }

    private static <T> T instantiate(Class<T> cls) {
        try {
            // 1) no-arg
            Constructor<T> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Exception ignore) {
            // 2) any constructor with defaults
            try {
                Constructor<?>[] ctors = cls.getDeclaredConstructors();
                if (ctors.length == 0) throw new IllegalStateException("No constructors for " + cls.getName());
                Constructor<?> ctor = ctors[0];
                ctor.setAccessible(true);

                Class<?>[] pt = ctor.getParameterTypes();
                Object[] args = new Object[pt.length];
                for (int i = 0; i < pt.length; i++) {
                    args[i] = defaultValue(pt[i]);
                }
                @SuppressWarnings("unchecked")
                T obj = (T) ctor.newInstance(args);
                return obj;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to instantiate " + cls.getName(), e);
            }
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == char.class) return '\0';
        return null;
    }

    private static void setFieldIfExists(Object obj, String fieldName, Object value) {
        if (obj == null) return;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                // ignore set failure
                return;
            }
        }
    }
}
