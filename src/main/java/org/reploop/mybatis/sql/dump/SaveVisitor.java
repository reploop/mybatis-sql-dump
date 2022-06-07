package org.reploop.mybatis.sql.dump;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.replace.Replace;

public class SaveVisitor extends StatementVisitorAdapter {
    @Override
    public void visit(Insert insert) {
        Table table = insert.getTable();
        System.out.println(table.getName());
        System.out.println(table.getSchemaName());
        System.out.println(table.getAlias());
        System.out.println(table.getFullyQualifiedName());
    }

    @Override
    public void visit(Replace replace) {
        Table table = replace.getTable();
        System.out.println(table.getName());
        System.out.println(table.getSchemaName());
        System.out.println(table.getAlias());
        System.out.println(table.getFullyQualifiedName());
    }
}
