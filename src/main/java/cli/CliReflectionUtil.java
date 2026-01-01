package cli;
/** Reflection helpers for reading legacy DTO fields without compile-time coupling. */
public final class CliReflectionUtil {

    private CliReflectionUtil() {}
    public static String invokeString(Object target, String method) {
        try {
            var m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            return (v == null) ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }
}
