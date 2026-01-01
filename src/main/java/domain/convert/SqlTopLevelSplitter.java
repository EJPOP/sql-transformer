package domain.convert;

import java.util.*;

/** Splits SQL by comma or equals at top-level depth, ignoring strings/comments/params. */
final class SqlTopLevelSplitter {
    private SqlTopLevelSplitter() {}

static List<String> splitTopLevelByComma(String s) {
    List<String> out = new ArrayList<>();
    if (s == null || s.isEmpty()) return out;

    StringBuilder cur = new StringBuilder();
    SqlScan st = new SqlScan(s);
    int depth = 0;

    while (st.hasNext()) {
        if (st.peekIsLineComment()) { cur.append(st.readLineComment()); continue; }
        if (st.peekIsBlockComment()) { cur.append(st.readBlockComment()); continue; }
        if (st.peekIsSingleQuotedString()) { cur.append(st.readSingleQuotedString()); continue; }
        if (st.peekIsDoubleQuotedString()) { cur.append(st.readDoubleQuotedString()); continue; }
        if (st.peekIsMyBatisParam()) { cur.append(st.readMyBatisParam()); continue; }
        if (st.peekIsHashParam()) { cur.append(st.readHashParam()); continue; }
        if (st.peekIsCdata()) { cur.append(st.readCdata()); continue; }
        if (st.peekIsXmlTag()) { cur.append(st.readXmlTag()); continue; }

        char ch = st.read();
        if (ch == '(') depth++;
        else if (ch == ')') depth = Math.max(0, depth - 1);

        if (ch == ',' && depth == 0) {
            out.add(cur.toString());
            cur.setLength(0);
        } else {
            cur.append(ch);
        }
    }

    if (cur.length() > 0) out.add(cur.toString());
    return out;
}

static int indexOfTopLevelEquals(String s) {
    SqlScan st = new SqlScan(s);
    int depth = 0;
    int idx = 0;

    while (st.hasNext()) {
        if (st.peekIsLineComment()) { st.readLineComment(); idx = st.pos; continue; }
        if (st.peekIsBlockComment()) { st.readBlockComment(); idx = st.pos; continue; }
        if (st.peekIsSingleQuotedString()) { st.readSingleQuotedString(); idx = st.pos; continue; }
        if (st.peekIsDoubleQuotedString()) { st.readDoubleQuotedString(); idx = st.pos; continue; }
        if (st.peekIsMyBatisParam()) { st.readMyBatisParam(); idx = st.pos; continue; }
        if (st.peekIsHashParam()) { st.readHashParam(); idx = st.pos; continue; }
        if (st.peekIsCdata()) { st.readCdata(); idx = st.pos; continue; }
        if (st.peekIsXmlTag()) { st.readXmlTag(); idx = st.pos; continue; }

        char ch = st.read();
        if (ch == '(') depth++;
        else if (ch == ')') depth = Math.max(0, depth - 1);
        else if (ch == '=' && depth == 0) return idx;

        idx = st.pos;
    }
    return -1;
}
}
