package domain.convert;

import domain.mapping.ColumnMapping;
import domain.mapping.ColumnMappingRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MybatisBraceParamRenamerTest {

    @Test
    void rename_shouldRenameMyBatisParams_toLowerCamelOfTobeColumns_forCommonOperators() throws Exception {
        ColumnMapping mId = newMapping("TB_ASIS", "ASIS_COL1", "TOBE_ID", "TB_TOBE");
        ColumnMapping mNm = newMapping("TB_ASIS", "ASIS_COL2", "TOBE_NM", "TB_TOBE");
        ColumnMapping mCd = newMapping("TB_ASIS", "ASIS_COL3", "TOBE_CD", "TB_TOBE");

        Map<String, ColumnMapping> map = new HashMap<>();
        map.put("TB_ASIS.ASIS_COL1", mId);
        map.put("TB_ASIS.ASIS_COL2", mNm);
        map.put("TB_ASIS.ASIS_COL3", mCd);

        // aliasTableMap may already contain TOBE table ids after conversion.
        ColumnMappingRegistry registry = new ColumnMappingRegistry(map, Map.of("TB_ASIS", "TB_TOBE"));

        MybatisBraceParamRenamer renamer = new MybatisBraceParamRenamer(registry);

        Map<String, String> aliasTableMap = Map.of("A", "TB_TOBE");

        String sql = ""
                + "SELECT *\n"
                + "FROM SOME_TABLE A\n"
                + "WHERE A.TOBE_ID >= #{auditYear, jdbcType=INTEGER}\n"
                + "  AND A.TOBE_NM LIKE #{keyword}\n"
                + "  AND A.TOBE_CD <> #{fromValue}\n"
                + "  AND A.TOBE_ID IN (#{ids})\n";

        String out = renamer.rename(sql, aliasTableMap);

        assertTrue(out.contains("#{tobeId, jdbcType=INTEGER}"), out);
        assertTrue(out.contains("LIKE #{tobeNm}"), out);
        assertTrue(out.contains("<> #{tobeCd}"), out);
        assertTrue(out.contains("IN (#{tobeId})"), out);

        assertFalse(out.contains("#{auditYear"), out);
        assertFalse(out.contains("#{keyword}"), out);
        assertFalse(out.contains("#{fromValue}"), out);
        assertFalse(out.contains("#{ids}"), out);
    }

    // -----------------------
    // reflection helper
    // -----------------------

    private static ColumnMapping newMapping(String asisTable, String asisCol, String tobeCol, String tobeTable) throws Exception {
        Class<?> clazz = Class.forName("domain.mapping.ColumnMapping");

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
            } catch (NoSuchFieldException ignore) {}
            cur = cur.getSuperclass();
        }
        return null;
    }
}
