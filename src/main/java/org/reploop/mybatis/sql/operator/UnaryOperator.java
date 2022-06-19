package org.reploop.mybatis.sql.operator;

public class UnaryOperator extends Operator {
    public UnaryOperator(String name) {
        this(name, 0);
    }

    public UnaryOperator(String name, int offset) {
        super(name, 1, offset);
    }
}
