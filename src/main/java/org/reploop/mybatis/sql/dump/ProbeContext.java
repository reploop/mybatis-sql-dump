package org.reploop.mybatis.sql.dump;

import com.google.common.collect.Lists;
import org.reploop.mybatis.sql.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

public class ProbeContext {
    private static final Logger LOG = LoggerFactory.getLogger(ProbeContext.class);
    Stack<Object> operands = new Stack<>();
    Stack<Operator> operators = new Stack<>();

    Map<String, Object> params = new HashMap<>();

    void pushOperand(Object operand) {
        operands.push(operand);
    }

    Object popOperand() {
        return operands.pop();
    }

    /**
     * Lower is higher.
     */
    private static final Map<String, Integer> precedences = Map.ofEntries(
            Map.entry("eval", 6),
            Map.entry("ASTNot", 6),
            Map.entry("ASTOr", 5),
            Map.entry("ASTAnd", 4),
            Map.entry("ASTEq", 3),
            Map.entry("ASTNotEq", 3),
            Map.entry("ASTLess", 3),
            Map.entry("ASTLessEq", 3),
            Map.entry("ASTGreater", 3),
            Map.entry("ASTGreaterEq", 3),
            Map.entry("ASTNegate", 1),
            Map.entry("ASTChain", 1),
            Map.entry("size", 1)
    );

    private String operatorKey(Operator op) {
        return op.getName();
    }

    private int compare(Operator op1, Operator op2) {
        String k1 = operatorKey(op1);
        String k2 = operatorKey(op2);
        Integer p1 = precedences.getOrDefault(k1, Integer.MAX_VALUE);
        Integer p2 = precedences.getOrDefault(k2, Integer.MAX_VALUE);
        return Comparator.<Integer>reverseOrder().compare(p1, p2);
    }

    private void compute() {
        compute(operators.pop());
    }

    public void computeAll() {
        while (!operators.isEmpty()) {
            compute();
        }
    }

    private boolean isJavaIdentifier(Object o) {
        if (o instanceof String s) {
            return Pattern.matches("[_a-zA-Z]+[._a-zA-Z\\d$]*", s);
        }
        return false;
    }

    private void compute(Operator operator) {
        String name = operatorKey(operator);
        switch (name) {
            case "size" -> {
                Object operand = NON_NULL;
                int idx = operands.size() - 1;
                while (idx >= 0) {
                    Object o = operands.get(idx--);
                    if (isJavaIdentifier(o)) {
                        operand = o;
                        break;
                    }
                }
                if (operand != NON_NULL) {
                    put(operand.toString(), new ArrayList<>());
                }
            }
            case "ASTChain" -> {
                Object val = NON_NULL;
                int offset = operator.getOffset();
                int count = operator.getCount();
                Stack<Object> stack = new Stack<>();
                int idx = offset + count;
                List<String> names = new ArrayList<>();
                for (int i = operands.size(); i > offset; i--) {
                    Object operand = operands.pop();
                    if (i > idx) {
                        stack.push(operand);
                    } else {
                        String key = operand.toString();
                        names.add(key);
                        Map<String, Object> ctx = new HashMap<>();
                        ctx.put(key, val);
                        val = ctx;
                    }
                }
                // Merged to one operand
                if (!names.isEmpty()) {
                    pushOperand(String.join(".", Lists.reverse(names)));
                }
                // Restore stack
                while (!stack.isEmpty()) {
                    operands.push(stack.pop());
                }
                if (val instanceof Map<?, ?> m) {
                    m.forEach((BiConsumer<Object, Object>) (key, value) -> put(key.toString(), value));
                }
            }
            case "ASTEq" -> {
                Object operand1 = popOperand();
                Object operand2 = popOperand();
                if (!isJavaIdentifier(operand2)) {
                    Object tmp = operand2;
                    operand2 = operand1;
                    operand1 = tmp;
                }
                put(operand2.toString(), operand1);
                pushOperand(true);
            }
            case "ASTNotEq" -> {
                Object operand1 = popOperand();
                Object operand2 = popOperand();
                if (!isJavaIdentifier(operand2)) {
                    Object tmp = operand2;
                    operand2 = operand1;
                    operand1 = tmp;
                }
                Object val = notEq(operand1);
                put(operand2.toString(), val);
                pushOperand(true);
            }
            case "ASTGreater", "ASTGreaterEq" -> {
                Object operand1 = popOperand();
                Object operand2 = popOperand();
                if (!isJavaIdentifier(operand2)) {
                    Object tmp = operand2;
                    operand2 = operand1;
                    operand1 = tmp;
                }
                Object val = greater(operand1);
                put(operand2.toString(), val);
                pushOperand(true);
            }
            case "ASTLessEq", "ASTLess" -> {
                Object operand1 = popOperand();
                Object operand2 = popOperand();
                if (!isJavaIdentifier(operand2)) {
                    Object tmp = operand2;
                    operand2 = operand1;
                    operand1 = tmp;
                }
                Object val = less(operand1);
                put(operand2.toString(), val);
                pushOperand(true);
            }
            case "ASTNot" -> {
                Object operand1 = popOperand();
                Object val = not(operand1);
                pushOperand(val);
            }
            case "ASTNegate" -> {
                Object operand1 = popOperand();
                Object val = negate(operand1);
                pushOperand(val);
            }
            case "ASTAnd" -> {
                Object operand1 = popOperand();
                Object operand2 = popOperand();
                boolean result = false;
                if (operand2 instanceof Boolean b2 && operand1 instanceof Boolean b1) {
                    result = b1 && b2;
                }
                pushOperand(result);
            }
            case "ASTOr" -> {
                Object operand1 = popOperand();
                Object operand2 = popOperand();
                boolean result = false;
                if (operand2 instanceof Boolean b2 && operand1 instanceof Boolean b1) {
                    result = b1 || b2;
                }
                pushOperand(result);
            }
            case "eval" -> {
                Object operand1 = popOperand();
                put(operand1.toString(), true);
                pushOperand(true);
            }
        }
    }

    private Object negate(Object n) {
        if (n instanceof Byte b) {
            return -b;
        } else if (n instanceof Short s) {
            return -s;
        } else if (n instanceof Integer i) {
            return -i;
        } else if (n instanceof Float f) {
            return -f;
        } else if (n instanceof Long l) {
            return -l;
        } else if (n instanceof Double d) {
            return -d;
        }
        return n;
    }

    private Object not(Object val) {
        if (null == val) {
            return true;
        } else if (val instanceof Boolean b) {
            return !b;
        } else if (val instanceof String s) {
            return !s.isEmpty();
        }
        return val;
    }

    private Object less(Object val) {
        if (val instanceof Number n) {
            return add(n, -1);
        } else if (val instanceof Boolean) {
            return false;
        } else if (val instanceof String s) {
            return s + "_less";
        }
        return val;
    }

    private Object greater(Object val) {
        if (val instanceof Number n) {
            return add(n, 1);
        } else if (val instanceof Boolean) {
            return true;
        } else if (val instanceof String s) {
            return s + "_greater";
        }
        return val;
    }

    private Object notEq(Object val) {
        if (val instanceof Number n) {
            return add(n, 1);
        } else if (val instanceof Boolean bool) {
            return !bool;
        } else if (val instanceof String s) {
            return s + "_not_eq";
        } else if (null == val) {
            return NON_NULL;
        } else if (val == NON_NULL) {
            return NULL;
        }
        return val;
    }

    private Object add(Number n, int delta) {
        if (n instanceof Byte b) {
            return b + delta;
        } else if (n instanceof Short s) {
            return s + delta;
        } else if (n instanceof Integer i) {
            return i + delta;
        } else if (n instanceof Float f) {
            return f + delta;
        } else if (n instanceof Long l) {
            return l + delta;
        } else if (n instanceof Double d) {
            return d + delta;
        }
        return n;
    }

    public void pushOperator(Operator current) {
        if (!operators.isEmpty()) {
            Operator previous = operators.peek();
            int result = compare(current, previous);
            if (result <= 0) {
                compute();
            }
        }
        operators.push(current);
    }

    Operator popOperator() {
        return operators.pop();
    }

    Optional<Operator> peekOperator() {
        if (operators.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(operators.peek());
    }

    @SuppressWarnings("unchecked")
    void put(String key, Object value) {
        String[] keys = key.split("[.]+");
        Map<String, Object> ctx = params;
        for (int i = 0; i < keys.length - 1; i++) {
            Object val = ctx.get(keys[i]);
            if (val instanceof Map<?, ?>) {
                ctx = (Map<String, Object>) val;
            }
        }
        // The last key into the last context
        key = keys[keys.length - 1];
        put(ctx, key, value);
    }

    void put(Map<String, Object> params, String key, Object value) {
        Object old = params.get(key);
        if (null == old || value instanceof Collection<?> || value instanceof Map<?, ?>) {
            params.put(key, value);
        }
        old = params.get(key);
        if (old instanceof Collection<?> coll) {
            LOG.warn("Use the most complex collection value");
            if (value instanceof Integer s) {
                for (int i = 0; i < s; i++) {
                    coll.add(null);
                }
            }
        } else if (old instanceof Map<?, ?> m) {
            LOG.warn("Use the most complex map value");
            if (value instanceof Integer s) {
                for (int i = 0; i < s - m.size(); i++) {
                    m.put(null, null);
                }
            }
        } else if (null != old && null == value) {
            LOG.warn("Nullable value should not replace a non-null value {} -> {}", old, value);
        } else if (null != old && NON_NULL == value) {
            LOG.warn("Use most specific value {} -> {}", old, value);
        } else {
            params.put(key, value);
        }
    }

    Map<String, Object> params() {
        return params;
    }

    public Object param(String key) {
        return params.get(key);
    }

    private static final String EMPTY = "";
    private static final Object NULL = null;
    private static final Object NON_NULL = new Object();
    private static final String NOT_EMPTY = "str_placeholder";
}
