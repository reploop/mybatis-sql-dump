package org.reploop.mybatis.sql.dump;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class JoinFinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(JoinFinder.class);

    public Set<Match> analyse(List<String> all) {
        Set<Match> matches = new HashSet<>();
        for (String sql : all) {
            matches.addAll(analyse(sql));
        }
        return matches;
    }

    public Set<Match> analyse(String sql) {
        Set<Match> matches = new HashSet<>();
        try {
            var stmt = (Select) CCJSqlParserUtil.parse(sql);
            JoinVisitor join = new JoinVisitor();
            stmt.accept(join);
            // Tables
            Stack<Table> tables = join.tables;
            Stack<Column> columns = join.columns;
            int cs = columns.size();
            Map<String, String> aliasTable = new HashMap<>();
            tables.forEach(table -> {
                Alias alias = table.getAlias();
                if (null != alias) {
                    aliasTable.put(alias.getName(), table.getName());
                } else {
                    aliasTable.put(table.getName(), table.getName());
                }
            });
            Set<String> alias = columns.stream()
                    .map(this::tableAlias)
                    .collect(Collectors.toSet());
            Set<String> total = new HashSet<>();
            total.addAll(alias);
            total.addAll(aliasTable.keySet());
            if (total.size() == alias.size() && alias.size() == aliasTable.size()) {
                LOGGER.info("SQL has join: {}", sql);
                while (cs > 0 && cs % 2 == 0) {
                    Column right = columns.pop();
                    String rt = aliasTable.get(tableAlias(right));
                    Column left = columns.pop();
                    String lt = aliasTable.get(tableAlias(left));
                    Match match = new Match(lt, left.getColumnName(), rt, right.getColumnName());
                    LOGGER.info("Found a match {}", match);
                    matches.add(match);
                    cs /= 2;
                }
            } else {
                LOGGER.info("SQL has no join: {}", sql);
            }
        } catch (Exception e) {
            LOGGER.error("SQL {}", sql, e);
        }
        return matches;
    }

    private String tableAlias(Column column) {
        Table table = column.getTable();
        return table.toString();
    }
}
