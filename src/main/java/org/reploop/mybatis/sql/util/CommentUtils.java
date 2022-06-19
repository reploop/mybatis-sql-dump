package org.reploop.mybatis.sql.util;

import org.apache.commons.lang.StringUtils;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.List;
import java.util.Stack;

import static java.lang.Character.isWhitespace;

public class CommentUtils {
    public static String stripEscape(String val) {
        return StringUtils.strip(val, "`");
    }

    public static void trimSql(List<String> lines, StringBuilder sb, String line) {
        if (sb.length() > 0) {
            sb.append(" ");
        }
        sb.append(line.trim());
        if (line.endsWith(";")) {
            lines.add(sb.toString());
            sb.setLength(0);
        }
    }

    private enum Next {
        START,
        LINE_SEPARATOR,
        BLOCK_COMMENT
    }

    public static String strip2Line(String sql) {
        StringCharacterIterator it = new StringCharacterIterator(sql);
        char lf = '\n';
        char cr = '\r';
        char fs = '/';
        char dash = '-';
        char st = '*';
        char prev = 0;
        Stack<Next> ops = new Stack<>();
        ops.push(Next.START);
        char[] chs = new char[sql.length()];
        int idx = 0;
        for (char curr = it.first(); curr != CharacterIterator.DONE; prev = curr, curr = it.next()) {
            // line comment starts --, expects \r\n
            if (prev == dash && curr == dash) {
                idx--;
                ops.push(Next.LINE_SEPARATOR);
                continue;
            }
            // line comment starts //, expects \r\n
            if (prev == fs && curr == fs) {
                idx--;
                ops.push(Next.LINE_SEPARATOR);
                continue;
            }
            // block comment starts /*, expects */
            if (prev == fs && curr == st) {
                idx--;
                ops.push(Next.BLOCK_COMMENT);
                continue;
            }
            // \r\n or \n
            if (prev == cr && curr == lf || prev != cr && curr == lf) {
                boolean found = false;
                while (!ops.isEmpty()) {
                    Next expect = ops.peek();
                    if (expect != Next.LINE_SEPARATOR) {
                        break;
                    }
                    ops.pop();
                    found = true;
                }
                if (found) {
                    continue;
                }
            }
            // */
            if (prev == st && curr == fs) {
                if (!ops.isEmpty()) {
                    Next expect = ops.peek();
                    if (expect == Next.BLOCK_COMMENT) {
                        ops.pop();
                        continue;
                    }
                }
            }
            if (ops.peek() == Next.START) {
                if (isWhitespace(curr)) {
                    int last = idx - 1;
                    if (last >= 0) {
                        if (isWhitespace(chs[last])) {
                            continue;
                        }
                    }
                    curr = space;
                }
                chs[idx++] = curr;
            }
        }
        return new String(chs, 0, idx);
    }

    static final char space = ' ';

}
