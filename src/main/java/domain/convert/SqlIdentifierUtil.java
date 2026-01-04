package domain.convert;

/**
 * Small identifier helpers shared by transformers.
 */
final class SqlIdentifierUtil {
    private SqlIdentifierUtil() {
    }

    static boolean isIdentStart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    static String lastPart(String ident) {
        if (ident == null) return "";
        String t = ident.trim();
        int p = t.lastIndexOf('.');
        return (p >= 0) ? t.substring(p + 1) : t;
    }
}
