package org.reploop.mybatis.sql.operator;

import java.util.Objects;
import java.util.StringJoiner;

public class Operator {
    private final String name;
    private int count;
    private final int offset;

    public Operator(String name, int count, int offset) {
        this.name = name;
        this.count = count;
        this.offset = offset;
    }

    public Operator(String name, int count) {
        this(name, count, 0);
    }

    public String getName() {
        return name;
    }

    public int getCount() {
        return count;
    }

    public void decreaseCount() {
        count--;
    }

    public void increaseCount() {
        count++;
    }

    public int getOffset() {
        return offset;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Operator operator = (Operator) o;
        return count == operator.count && offset == operator.offset && Objects.equals(name, operator.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, count, offset);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", Operator.class.getSimpleName() + "[", "]")
                .add("name='" + name + "'")
                .add("limit=" + count)
                .add("total=" + offset)
                .toString();
    }
}
