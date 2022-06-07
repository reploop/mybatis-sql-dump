package org.reploop.mybatis.sql.dump;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.apache.commons.lang.StringUtils;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.ognl.*;
import org.apache.ibatis.scripting.xmltags.*;
import org.apache.ibatis.session.Configuration;
import org.mybatis.dynamic.sql.SqlTable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;
import static org.springframework.util.Assert.notNull;

@Component
public class DumpSql implements InitializingBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(DumpSql.class);
    @Resource
    private SqlSessionFactoryBean bean;
    @Value("${outputSql:/tmp/dump.sql}")
    private Path outputSql;

    private boolean isFullName(String name) {
        return name.contains(".");
    }

    private void table(ResultMap resultMap) {
        System.out.println(resultMap.getId());
        var mappings = resultMap.getResultMappings();
        mappings.forEach(new Consumer<ResultMapping>() {
            @Override
            public void accept(ResultMapping resultMapping) {
                var flags = resultMapping.getFlags();
                boolean isIdColumn = flags.stream().anyMatch(flag -> flag == ResultFlag.ID);
                System.out.println(resultMapping);
            }
        });
    }

    private boolean customized(Class<?> type) {
        return null != type && type.getPackageName().startsWith("com.");
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        notNull(bean, "");
        var factory = bean.getObject();
        assert factory != null;
        var conf = factory.getConfiguration();
        var mappers = conf.getMapperRegistry().getMappers();
        System.out.println(mappers);
        Collection<String> resultMapNames = conf.getResultMapNames();
        Map<Class<?>, ResultMap> typedMap = resultMapNames.stream()
                .filter(this::isFullName)
                .map(conf::getResultMap)
                .filter(rm -> customized(rm.getType()))
                .collect(Collectors.toMap(ResultMap::getType, i -> i, (rm1, rm2) -> {
                    Comparator<ResultMap> cmp = Comparator.comparingInt(value -> value.getResultMappings().size());
                    int r = cmp.compare(rm1, rm2);
                    if (r != 0) {
                        LOGGER.warn("In constant field number of {},{}", rm1.getId(), rm2.getId());
                    }
                    if (r >= 0) {
                        return rm1;
                    } else {
                        return rm2;
                    }
                }));
        Map<Class<?>, Set<String>> typeTable = typeTableInsert(conf);
        List<String> all = new ArrayList<>();
        typeTableSelect(conf, all, typeTable);
        Set<Class<?>> types = new HashSet<>();
        types.addAll(typedMap.keySet());
        types.addAll(typeTable.keySet());
        sqlTable(typeTable, types);
        diagram.generate(types, typedMap, typeTable, all);
        output(all);
    }

    @Autowired
    private EntityDiagram diagram;

    private void output(List<String> all) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputSql, UTF_8, CREATE, TRUNCATE_EXISTING, WRITE)) {
            for (String sql : all) {
                if (sql.toUpperCase().startsWith(SqlCommandType.SELECT.name())) {
                    writer.write(sql);
                    writer.write(";");
                    writer.newLine();
                }
            }
        }
    }


    private void sqlTable(Map<Class<?>, Set<String>> typeTable, Set<Class<?>> types) throws IllegalAccessException {
        for (Class<?> type : types) {
            Field[] fields = type.getDeclaredFields();
            for (Field field : fields) {
                int m = field.getModifiers();
                if (Modifier.isStatic(m)) {
                    field.setAccessible(true);
                    Object val = field.get(null);
                    if (val instanceof SqlTable sqlTable) {
                        String tableName = sqlTable.tableNameAtRuntime();
                        typeTable.compute(type, (key, old) -> {
                            Set<String> names = new LinkedHashSet<>();
                            names.add(tableName);
                            if (null != old) {
                                names.addAll(old);
                            }
                            return names;
                        });
                        break;
                    }
                }
            }
        }
    }

    private Object dss(DynamicSqlSource dss) throws NoSuchFieldException, IllegalAccessException, OgnlException {
        Field field = DynamicSqlSource.class.getField("rootSqlNode");
        SqlNode sqlNode = (SqlNode) field.get(dss);
        return sqlNode(null, sqlNode);
    }

    private <T, K extends SqlNode> T contents(K node) throws NoSuchFieldException, IllegalAccessException {
        return getFieldValue("contents", node);
    }

    private <T, K> T getFieldValue(String name, K node) throws NoSuchFieldException, IllegalAccessException {
        Field cf = node.getClass().getDeclaredField(name);
        cf.setAccessible(true);
        return (T) cf.get(node);
    }

    private Map<String, Object> sqlNode(SqlNode parent, SqlNode node) throws NoSuchFieldException, IllegalAccessException, OgnlException {
        Map<String, Object> context = new HashMap<>();
        sqlNode(parent, node, context);
        return context;
    }

    private void sqlNode(SqlNode parent, SqlNode node, Map<String, Object> context) throws NoSuchFieldException, IllegalAccessException, OgnlException {
        if (node instanceof MixedSqlNode msn) {
            List<SqlNode> contents = contents(msn);
            for (SqlNode content : contents) {
                sqlNode(node, content, context);
            }
        } else if (node instanceof ForEachSqlNode fes) {
            String name = getFieldValue("collectionExpression", fes);
            List<Object> it = new ArrayList<>();
            it.add(null);
            context.put(name, it);
        } else if (node instanceof StaticTextSqlNode sts) {
            //NO-OP
        } else if (node instanceof TextSqlNode tsn) {
        } else if (node instanceof ChooseSqlNode csn) {
        } else if (node instanceof SetSqlNode ssn) {
        } else if (node instanceof IfSqlNode isn) {
            String test = getFieldValue("test", isn);
            Object o = Ognl.parseExpression(test);
            if (o instanceof BooleanExpression be) {
                //prop(be, context);
            }
            SqlNode contents = getFieldValue("contents", isn);
            sqlNode(node, contents, context);
        }
    }

    /**
     * Assume Two operand operators
     */
    private void prop(BooleanExpression root, Map<String, Object> context) {
        int count = root.jjtGetNumChildren();
        String name = null;
        Object val = null;
        for (int i = 0; i < count; i++) {
            Node n = root.jjtGetChild(i);
            if (n instanceof ASTProperty prop) {
                name = prop.toString();
            } else if (n instanceof BooleanExpression sub) {
                prop(sub, context);
            } else if (n instanceof ASTConst c) {
                val = c.getValue();
            } else if (n instanceof ASTChain chain) {

            }
        }
        if (null != name) {
            if (root instanceof ASTNotEq) {
                if (null == val) {
                    val = PLACEHOLDER;
                } else if (val instanceof String s) {
                    if (s.isEmpty()) {
                        val = NOT_EMPTY;
                    } else {
                        val = EMPTY;
                    }
                } else if (val instanceof Integer i32) {
                    val = i32 + 1;
                } else if (val instanceof Long l64) {
                    val = l64 + 1;
                } else if (val instanceof Boolean bi) {
                    val = !bi;
                }
            }
            if (null == val) {
                LOGGER.info("Wrong parameter {}", name);
                val = PLACEHOLDER;
            }
            Object old = context.get(name);
            if (null == old || old == PLACEHOLDER) {
                context.put(name, val);
            }
        }
    }

    private static final Object PLACEHOLDER = new Object();
    private static final String NOT_EMPTY = "__";
    private static final String EMPTY = "";

    private void typeTableSelect(Configuration conf, List<String> all, Map<Class<?>, Set<String>> typeTable) {
        Collection<String> names = conf.getMappedStatementNames();
        for (String name : names) {
            // It's a full name, not a short name.
            if (isFullName(name)) {
                MappedStatement statement = conf.getMappedStatement(name);
                SqlCommandType commandType = statement.getSqlCommandType();
                StatementType statementType = statement.getStatementType();
                if (statementType == StatementType.CALLABLE) {
                    LOGGER.warn("Call proc {} ", statement.getId());
                    continue;
                }
                SqlSource source = statement.getSqlSource();
                if (commandType == SqlCommandType.SELECT) {
                    Map<String, Object> params = null;
                    if (source instanceof DynamicSqlSource dss) {
                        try {
                            SqlNode root = getFieldValue("rootSqlNode", dss);
                            if (null != root) {
                                params = sqlNode(null, root);
                            } else {
                                LOGGER.info("Pure");
                            }
                        } catch (Exception e) {
                            LOGGER.error("Cannot SQL NODE {}", params, e);
                        }
                    }
                    String sql = getSql(source, params);
                    if (isNullOrEmpty(sql)) {
                        LOGGER.warn("Empty sql {}", statement.getId());
                        continue;
                    }
                    all.add(sql);
                    var resultMaps = statement.getResultMaps();
                    resultMaps.forEach(resultMap -> {
                        Class<?> type = resultMap.getType();
                        if (customized(type)) {
                            String tableName = tableNameFromSelect(sql);
                            if (!isNullOrEmpty(tableName)) {
                                typeTable.computeIfAbsent(type, key -> new LinkedHashSet<>()).add(tableName);
                            }
                        }
                    });
                }
            }
        }
    }

    private Map<Class<?>, Set<String>> typeTableInsert(Configuration conf) throws IllegalAccessException {
        Map<Class<?>, Set<String>> typeTable = new HashMap<>();
        Collection<String> names = conf.getMappedStatementNames();
        for (String name : names) {
            // It's a full name, not a short name.
            if (isFullName(name)) {
                MappedStatement statement = conf.getMappedStatement(name);
                SqlCommandType commandType = statement.getSqlCommandType();
                StatementType statementType = statement.getStatementType();
                if (statementType == StatementType.CALLABLE) {
                    LOGGER.warn("Call proc {} ", statement.getId());
                    continue;
                }
                SqlSource source = statement.getSqlSource();
                if (commandType == SqlCommandType.INSERT) {
                    ParameterMap p = statement.getParameterMap();
                    Class<?> type = p.getType();
                    String pn;
                    // We can only get the element type of List or Set at runtime, so just ignore here.
                    // Only handle user defined class in package starts with com.
                    if (!customized(type)) {
                        continue;
                    }
                    Object ins = newInstance(p.getType());
                    if (null != ins) {
                        // Try to set some value
                        Field[] fields = type.getDeclaredFields();
                        for (Field field : fields) {
                            int m = field.getModifiers();
                            if (Modifier.isStatic(m) || Modifier.isFinal(m)) {
                                continue;
                            }
                            field.setAccessible(true);
                            Class<?> ft = field.getType();
                            if (ft.isAssignableFrom(String.class)) {
                                field.set(ins, "_for_g_only");
                            } else if (ft.isAssignableFrom(int.class)) {
                                field.setInt(ins, 0);
                            } else if (ft.isAssignableFrom(Integer.class)) {
                                field.set(ins, 0);
                            } else if (ft.isAssignableFrom(Boolean.class)) {
                                field.set(ins, Boolean.TRUE);
                            } else if (ft.isAssignableFrom(boolean.class)) {
                                field.setBoolean(ins, true);
                            }
                        }
                    } else {
                        LOGGER.warn("Cannot new instance of {}", type);
                    }
                    String sql = getSql(source, ins);
                    if (isNullOrEmpty(sql)) {
                        continue;
                    }
                    typeTable.computeIfAbsent(type, key -> new LinkedHashSet<>()).add(findAndTrimTableNameFromCreate(sql));
                }
            }
        }
        return typeTable;
    }

    private String tableNameFromSelect(String sql) {
        try {
            var stmt = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder finder = new TablesNamesFinder();
            List<String> tables = finder.getTableList(stmt);
            return tables.stream().findFirst().map(this::strip).orElse("");
        } catch (JSQLParserException | RuntimeException e) {
            LOGGER.error("Sql {}", sql, e);
        }
        return "";
    }

    private String findAndTrimTableNameFromCreate(String sql) {
        return strip(tableNameFromCreate(sql));
    }

    private String strip(String val) {
        return StringUtils.strip(val, "`");
    }

    private String tableNameFromCreate(String sql) {
        String[] elements = sql.split("\\s+");
        if (elements.length <= 2) {
            throw new IllegalArgumentException(sql);
        }
        String candidate = elements[2];
        if (candidate.endsWith(";")) {
            return candidate.substring(0, candidate.length() - 1);
        }
        int idx = candidate.indexOf('(');
        if (idx > 0) {
            return candidate.substring(0, idx);
        }
        return candidate;
    }

    private Object newInstance(Class<?> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        for (Constructor<?> constructor : constructors) {
            try {
                return constructor.newInstance();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String getSql(SqlSource source) {
        return getSql(source, null);
    }

    static final String LINE_SEPARATOR = System.getProperty("line.separator");

    private String getSql(SqlSource source, Object parameterObject) {
        String sql = null;
        try {
            var bs = source.getBoundSql(parameterObject);
            sql = bs.getSql();
            String raw = Arrays.stream(sql.split(LINE_SEPARATOR))
                    .map(line -> {
                        // trim line comment
                        int idx = line.indexOf("--");
                        if (idx > 0) {
                            return line.substring(0, idx);
                        }
                        return line;
                    }).collect(Collectors.joining(" "));
            sql = raw.replaceAll("\\s+", " ").trim();
            if (!isNullOrEmpty(sql)) {
                var stmt = CCJSqlParserUtil.parse(sql);
            }
            return sql;
        } catch (Exception e) {
            LOGGER.error("SQL {}", sql, e);
        }
        return "";
    }
}
