package org.reploop.mybatis.sql.operator;

public class BinaryOperator extends Operator {
    public BinaryOperator(String name) {
        this(name, 0);
    }

    public BinaryOperator(String name, int offset) {
        super(name, 2, offset);
    }
}
