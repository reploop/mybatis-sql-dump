package org.reploop.mybatis.sql.dump;

import freemarker.template.Configuration;
import freemarker.template.Template;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;

import static java.lang.System.lineSeparator;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newBufferedWriter;
import static java.nio.file.StandardOpenOption.*;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
public class EntityDiagram {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityDiagram.class);
    @Autowired
    private JoinFinder finder;

    private void associate(Map<TableColumn, List<TableColumn>> associates, TableColumn left, TableColumn right) {
        associates.computeIfAbsent(left, key -> new ArrayList<>()).add(right);
    }

    private Map<TableColumn, List<TableColumn>> analyse(List<String> lines) {
        Set<Match> matches = finder.analyse(lines);
        Map<TableColumn, List<TableColumn>> associates = new HashMap<>();
        for (Match match : matches) {
            TableColumn left = new TableColumn(match.leftTable(), match.leftColumn());
            TableColumn right = new TableColumn(match.rightTable(), match.rightColumn());
            associate(associates, left, right);
            associate(associates, right, left);
        }
        return associates;
    }

    @Resource
    private Configuration cfg;

    public void generate(List<String> all) {
        String nodeFormat = "%s [shape=plain label=<%s>];";
        String edgeFormat = "%s:%s -- %s:%s;";
        StringBuilder digraph = new StringBuilder();
        digraph.append("graph ERD {").append(lineSeparator());
        var associates = analyse(all);
        // all table and it's columns.
        Map<String, Set<String>> tables = collect(associates);

        // All nodes
        tables.forEach((table, columns) -> {
            String label = label(table, columns);
            String node = String.format(nodeFormat, table, label);
            digraph.append(node).append(lineSeparator());
        });

        // All edges
        tables.forEach((table, columns) -> {
            for (String column : columns) {
                TableColumn left = new TableColumn(table, column);
                List<TableColumn> tcs = associates.get(left);
                if (!isEmpty(tcs)) {
                    for (TableColumn right : tcs) {
                        if (markIfAbsent(left, right) || markIfAbsent(right, left)) {
                            continue;
                        }
                        String edge = String.format(edgeFormat, left.table(), left.column(), right.table(), right.column());
                        digraph.append(edge).append(lineSeparator());
                    }
                }
            }
        });
        digraph.append("}").append(lineSeparator());

        try (BufferedWriter writer = newBufferedWriter(Paths.get("/tmp/dump.dot"), UTF_8, CREATE, TRUNCATE_EXISTING, WRITE)) {
            writer.write(digraph.toString());
        } catch (Exception e) {
            LOGGER.error("Digraph {}", digraph, e);
        }
        System.out.println(digraph);
        System.out.println();
    }

    private final Map<TableColumn, Map<TableColumn, Boolean>> drawn = new HashMap<>();

    private boolean markIfAbsent(TableColumn left, TableColumn right) {
        Map<TableColumn, Boolean> exists = drawn.computeIfAbsent(left, tc -> new HashMap<>());
        Boolean old = exists.putIfAbsent(right, Boolean.TRUE);
        return null != old;
    }

    /**
     * Collect table and it's columns
     *
     * @param associates associates
     * @return tables has associates with other tables;
     */
    private Map<String, Set<String>> collect(Map<TableColumn, List<TableColumn>> associates) {
        Map<String, Set<String>> tables = new HashMap<>();
        associates.forEach((key, values) -> {
            collect(tables, key);
            values.forEach(tc -> collect(tables, tc));
        });
        return tables;
    }

    private void collect(Map<String, Set<String>> tables, TableColumn key) {
        tables.computeIfAbsent(key.table(), k -> new HashSet<>()).add(key.column());
    }

    private void handle(Set<Class<?>> types,
                        Map<Class<?>, ResultMap> typedMap,
                        Map<Class<?>, Set<String>> typeTable,
                        BiConsumer<String, List<ResultMapping>> tf) {
        for (Class<?> type : types) {
            Set<String> tables = typeTable.get(type);
            ResultMap resultMap = typedMap.get(type);
            if (isEmpty(tables) || null == resultMap) {
                LOGGER.info("Miss table info {}", type);
                continue;
            }
            for (String table : tables) {
                var mappings = resultMap.getResultMappings();
                tf.accept(table, mappings);
            }
        }
    }

    private String label(String table, Set<String> columns) {
        Map<String, Object> model = new HashMap<>();
        model.put("name", table);
        model.put("columns", columns);
        try {
            Template template = cfg.getTemplate("table.ftl");
            StringWriter writer = new StringWriter();
            template.process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException(model.toString(), e);
        }
    }

    private String label(String table, List<ResultMapping> mappings, Set<String> foreignColumns) {
        Map<String, Object> model = new HashMap<>();
        model.put("name", table);
        List<String> columns = mappings.stream()
                .filter(mapping -> {
                    var flags = mapping.getFlags();
                    boolean id = null != flags && flags.stream().anyMatch(f -> f == ResultFlag.ID);
                    String column;
                    return id || null != (column = mapping.getColumn()) && foreignColumns.contains(column);
                })
                .map(ResultMapping::getColumn)
                .filter(Objects::nonNull)
                .toList();
        model.put("columns", columns);
        try {
            Template template = cfg.getTemplate("table.ftl");
            StringWriter writer = new StringWriter();
            template.process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException(model.toString(), e);
        }
    }
}
