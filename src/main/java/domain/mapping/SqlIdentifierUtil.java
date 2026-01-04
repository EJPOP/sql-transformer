package domain.mapping;

/**
 * mapping 패키지에서 사용하는 SQL 식별자 관련 상수.
 *
 * <p>테이블 ID가 "TBXXXX" 또는 "TB_XXXX"처럼 prefix 뒤 언더스코어 유무가 섞여 들어오는 경우가 있어
 * prefix 뒤에 언더스코어를 삽입/제거하는 정규화에 사용한다.</p>
 */
public final class SqlIdentifierUtil {

    /**
     * 프로젝트에서 사용하는 대표 테이블 prefix 목록.
     * 필요 시 확장 가능.
     */
    public static final String[] TABLE_PREFIXES = {
            "TB", "PTL", "IS", "CTT", "EH", "HE", "EN", "NE", "AM", "BAI", "IR"
    };

    private SqlIdentifierUtil() {
    }
}
