package org.reploop.mybatis.sql.dump;

import freemarker.template.Configuration;
import freemarker.template.Template;
import org.apache.ibatis.mapping.ResultMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.*;

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

    static Configuration cfg = Configuration.getDefaultConfiguration();

    public void generate(Set<Class<?>> types, Map<Class<?>, ResultMap> typedMap, Map<Class<?>, Set<String>> typeTable, List<String> all) throws IOException {
        var associates = analyse(all);
        for (Class<?> type : types) {
            Set<String> tables = typeTable.get(type);
            ResultMap resultMap = typedMap.get(type);
            if (CollectionUtils.isEmpty(tables) || null == resultMap) {
                LOGGER.info("Miss table info {}", type);
                continue;
            }
            Template template = cfg.getTemplate("table.ftl");
            for (String table : tables) {
                var mappings = resultMap.getResultMappings();
                for (var mapping : mappings) {
                    String column = mapping.getColumn();
                    TableColumn tableColumn = new TableColumn(table, column);
                    associates.get(tableColumn);
                }
            }
        }
    }
}
