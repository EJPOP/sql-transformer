package domain.mapping;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ColumnMappingRegistryTest {

    @Test
    void findByTobeOnAsisTable_shouldWork_evenWhenInputTableIsAlreadyTobe() throws Exception {
        ColumnMapping m = newMapping("TB_ASIS", "ASIS_COL", "TOBE_ID", "TB_TOBE");

        Map<String, ColumnMapping> map = new HashMap<>();
        map.put("TB_ASIS.ASIS_COL", m);

        ColumnMappingRegistry registry = new ColumnMappingRegistry(
                map,
                Map.of("TB_ASIS", "TB_TOBE")
        );

        // 기존 호출부가 "ASIS_TABLE" 자리에 이미 치환된 "TOBE_TABLE"을 넘겨도 매핑을 찾아야 함
        ColumnMapping found = registry.findByTobeOnAsisTable("TB_TOBE", "TOBE_ID");
        assertNotNull(found);
        assertSame(m, found);
    }

    @Test
    void findByTobeOnAsisTable_shouldReturnNull_whenTobeTableAndTobeColIndexIsAmbiguous() throws Exception {
        ColumnMapping m1 = newMapping("TB_A1", "C1", "TOBE_COL", "TB_T");
        ColumnMapping m2 = newMapping("TB_A2", "C2", "TOBE_COL", "TB_T"); // same (TOBE_TABLE.TOBE_COL) -> ambiguous

        Map<String, ColumnMapping> map = new HashMap<>();
        map.put("TB_A1.C1", m1);
        map.put("TB_A2.C2", m2);

        ColumnMappingRegistry registry = new ColumnMappingRegistry(
                map,
                Map.of("TB_A1", "TB_T", "TB_A2", "TB_T")
        );

        ColumnMapping found = registry.findByTobeOnAsisTable("TB_T", "TOBE_COL");
        assertNull(found);
    }

    // -----------------------
    // reflection helper
    // -----------------------

    private static ColumnMapping newMapping(String asisTable, String asisCol, String tobeCol, String tobeTable) throws Exception {
        Class<?> clazz = Class.forName("domain.mapping.ColumnMapping");

        // 가장 파라미터 적은 ctor로 생성 (no-arg가 없을 수도 있으니)
        Constructor<?> best = Arrays.stream(clazz.getDeclaredConstructors())
                .min(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow();
        best.setAccessible(true);

        Object[] args = new Object[best.getParameterCount()];
        Class<?>[] types = best.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = defaultValue(types[i]);
        }

        Object obj = best.newInstance(args);

        setFieldIfExists(obj, "asisTableId", asisTable);
        setFieldIfExists(obj, "asisColumnId", asisCol);
        setFieldIfExists(obj, "tobeColumnId", tobeCol);

        // ColumnMappingRegistry가 readAnyString 후보로 읽는 필드명 중 하나로 세팅
        setFirstExistingField(obj,
                new String[]{"tobeTableId", "tableId", "tobeTblId", "tobeTable", "TOBE_TABLE_ID", "TABLE_ID"},
                tobeTable
        );

        return (ColumnMapping) obj;
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

    private static void setFirstExistingField(Object obj, String[] candidates, String value) throws Exception {
        for (String c : candidates) {
            if (setFieldIfExists(obj, c, value)) return;
        }
    }

    private static boolean setFieldIfExists(Object obj, String fieldName, String value) throws Exception {
        Field f = findField(obj.getClass(), fieldName);
        if (f == null) return false;
        f.setAccessible(true);
        f.set(obj, value);
        return true;
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
            }
            cur = cur.getSuperclass();
        }
        return null;
    }
}
