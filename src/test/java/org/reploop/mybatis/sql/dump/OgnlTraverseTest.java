package org.reploop.mybatis.sql.dump;

import org.apache.ibatis.ognl.Node;
import org.apache.ibatis.ognl.Ognl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OgnlTraverseTest {

    @Test
    void exists() throws Exception {
        String exp = "val > 1 && var";
        ProbeContext context = parse(exp);
        Object val = context.param("val");
        assertTrue((Integer) val > 1);
        Object var = context.param("var");
        assertEquals(true, var);
        exp = "var";
        context = parse(exp);
        var = context.param("var");
        assertEquals(true, var);

        exp = "var.next.name";
        context = parse(exp);
        var ctx = (Map<String, Object>) context.param("var");
        ctx = (Map<String, Object>) ctx.get("next");
        var = ctx.get("name");
        assertEquals(true, var);
    }

    @Test
    void value() throws Exception {
        String exp = "!(account == 0)";
        ProbeContext context = parse(exp);
        Object val = context.param("account");
        assertEquals(0, val);
    }

    @Test
    void numeric() throws Exception {
        String exp = "x > 0 && y  < 1";
        ProbeContext context = parse(exp);
        Integer x = (Integer) context.param("x");
        Integer y = (Integer) context.param("y");
        assertTrue(x > 0);
        assertTrue(y < 1);
        exp = "x > 0 || y >1";
        context = parse(exp);
        x = (Integer) context.param("x");
        y = (Integer) context.param("y");
        assertTrue(x > 0);
        assertTrue(y > 1);
    }

    @Test
    void compare() throws Exception {
        String exp = "account.userId != \"0\"";
        ProbeContext context = parse(exp);
        assertTrue(context.params.containsKey("account"));
        Object ctx = context.params.get("account");
        assertTrue(ctx instanceof Map<?, ?>);
        Map<String, Object> v = (Map<String, Object>) ctx;
        String id = (String) v.get("userId");
        assertTrue(id.length() > 0);
    }

    @BeforeEach
    void setUp() {
        traverse = new OgnlTraverse();
    }

    OgnlTraverse traverse;

    @Test
    void testColl() throws Exception {
        String exp = "accounts.size > 0";
        ProbeContext context = parse(exp);
        Object val = context.params().get("accounts");
        assertNotNull(val);
        boolean col = val instanceof Collection<?>;
        assertTrue(col);
        assertTrue(((Collection<?>) val).size() > 0);
        exp = "accounts.size() != 1";
        context = parse(exp);
        val = context.params().get("accounts");
        assertNotNull(val);
        col = val instanceof Collection<?>;
        assertTrue(col);
        assertTrue(((Collection<?>) val).size() != 1);
    }

    ProbeContext parse(String exp) throws Exception {
        Object e = Ognl.parseExpression(exp);
        ProbeContext context = new ProbeContext();
        traverse.probe((Node) e, context);
        context.computeAll();
        return context;
    }

    @Test
    void probeNegate() throws Exception {
        String exp = "size > -1";
        ProbeContext context = parse(exp);
        Object val = context.params.get("size");
        assertTrue(val instanceof Integer);
        assertTrue((Integer) val > -1);
        exp = "m > -0";
        context = parse(exp);
        val = context.param("m");
        assertTrue((Integer) val > 0);

    }

    @Test
    void testProbe() throws Exception {
        String exp = "status != null and status != '' or status == 0 && status > 100";
        ProbeContext context = parse(exp);
        Object val = context.param("status");
        assertTrue((Integer) val > 100);
    }

    @Test
    void reverse() throws Exception {
        String exp = "0 != status";
        ProbeContext context = parse(exp);
        Object val = context.param("status");
        assertTrue((Integer) val != 0);

    }
}