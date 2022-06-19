package org.reploop.mybatis.sql.dump;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.ognl.BooleanExpression;
import org.apache.ibatis.ognl.Ognl;
import org.apache.ibatis.ognl.OgnlException;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.xmltags.*;
import org.apache.ibatis.session.Configuration;
import org.mybatis.dynamic.sql.SqlTable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.reploop.mybatis.sql.util.CommentUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;
import static org.reploop.mybatis.sql.util.CommentUtils.trimSql;
import static org.springframework.util.Assert.notNull;

@Component
public class DumpSql implements InitializingBean, ApplicationContextAware {
    private static final Logger LOGGER = LoggerFactory.getLogger(DumpSql.class);
    @Autowired
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
        notNull(bean, "Session factory bean is required.");
        var factory = bean.getObject();
        assert factory != null;
        var conf = factory.getConfiguration();
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
        //Set<Class<?>> types = new HashSet<>();
        //types.addAll(typedMap.keySet());
        //types.addAll(typeTable.keySet());
        //sqlTable(typeTable, types);
        all.addAll(read());
        diagram.generate(all);

        output(all);
    }

    private List<String> read() {
        List<String> lines = new ArrayList<>();
        String loc = "classpath:/sql/ai.sql";
        Resource resource = context.getResource(loc);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while (null != (line = reader.readLine())) {
                trimSql(lines, sb, line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return lines;
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

    private <T, K extends SqlNode> T contents(K node) throws NoSuchFieldException, IllegalAccessException {
        return getFieldValue("contents", node);
    }

    private <T, K> T getFieldValue(String name, K node) throws NoSuchFieldException, IllegalAccessException {
        Field cf = node.getClass().getDeclaredField(name);
        cf.setAccessible(true);
        return (T) cf.get(node);
    }

    private Map<String, Object> sqlNode(SqlNode node) throws NoSuchFieldException, IllegalAccessException, OgnlException {
        Map<String, Object> context = new HashMap<>();
        sqlNode(node, context);
        return context;
    }

    private ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.context = applicationContext;
    }

    private static class TokenCollector implements TokenHandler {
        Set<String> tokens = new LinkedHashSet<>();

        @Override
        public String handleToken(String content) {
            tokens.add(content);
            return content;
        }

        public Set<String> getTokens() {
            return tokens;
        }
    }

    private GenericTokenParser createParser(TokenHandler handler) {
        return new GenericTokenParser("${", "}", handler);
    }

    private void sqlNode(SqlNode node, Map<String, Object> context) throws NoSuchFieldException, IllegalAccessException, OgnlException {
        if (node instanceof MixedSqlNode msn) {
            List<SqlNode> contents = contents(msn);
            for (SqlNode content : contents) {
                sqlNode(content, context);
            }
        } else if (node instanceof ForEachSqlNode fes) {
            // We need a list that is not empty, element does not matter, any one will do. so let it be null.
            String name = getFieldValue("collectionExpression", fes);
            List<Object> it = new ArrayList<>();
            it.add(null);
            context.put(name, it);
        } else if (node instanceof StaticTextSqlNode sts) {
            //NO-OP
        } else if (node instanceof TextSqlNode tsn) {
            if (tsn.isDynamic()) {
                String text = getFieldValue("text", tsn);
                TokenCollector collector = new TokenCollector();
                var parser = createParser(collector);
                parser.parse(text);
                Set<String> tokens = collector.tokens;
                StringBuilder sb = new StringBuilder();
                StringCharacterIterator it = new StringCharacterIterator(text);
                String prev;
                String curr = null;
                int idx = 0;
                Object[] args = new Object[0];
                for (char c = it.first(); c != CharacterIterator.DONE; c = it.next()) {
                    if (Character.isAlphabetic(c) || Character.isDigit(c) || c == '.' || c == '_') {
                        sb.append(c);
                    } else if (sb.length() > 0) {
                        prev = curr;
                        curr = sb.toString();
                        if ("from".equalsIgnoreCase(curr)) {
                            args = new Object[]{"tb_placeholder"};
                            idx = 0;
                        } else if ("by".equalsIgnoreCase(curr) && "order".equalsIgnoreCase(prev)) {
                            args = new Object[]{"col_placeholder", "asc", "desc"};
                            idx = 0;
                        } else if ("limit".equalsIgnoreCase(curr)) {
                            args = new Object[]{1, 20};
                            idx = 0;
                        } else if ("having".equalsIgnoreCase(curr)) {
                            args = new Object[]{"count(1) > 0"};
                            idx = 0;
                        } else if ("in".equalsIgnoreCase(curr)) {
                            args = new Object[]{0};
                            idx = 0;
                        } else if ("offset".equalsIgnoreCase(curr)) {
                            args = new Object[]{0, 20};
                            idx = 0;
                        }
                        if (tokens.contains(curr)) {
                            Object val = NULL;
                            if (idx < args.length) {
                                val = args[idx++];
                            }
                            if (curr.equals("orderType") || curr.equals("order_by_record_creation_date")) {
                                val = args[args.length - 1];
                            }
                            context.put(curr, val);
                        }
                        sb.setLength(0);
                    }
                }
            }
        } else if (node instanceof ChooseSqlNode csn) {
            SqlNode defaultSqlNode = getFieldValue("defaultSqlNode", csn);
            sqlNode(defaultSqlNode, context);
            List<SqlNode> ifSqlNodes = getFieldValue("ifSqlNodes", csn);
            for (SqlNode isn : ifSqlNodes) {
                sqlNode(isn, context);
            }
        } else if (node instanceof SetSqlNode ssn) {
        } else if (node instanceof IfSqlNode isn) {
            String test = getFieldValue("test", isn);
            Object o = Ognl.parseExpression(test);
            if (o instanceof BooleanExpression be) {
                LOGGER.info("test expression {}", test);
                traverse.probe(be, context);
            }
            SqlNode contents = getFieldValue("contents", isn);
            sqlNode(contents, context);
        }
    }

    private final OgnlTraverse traverse = new OgnlTraverse();

    private static final Object NULL = null;

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
                                params = sqlNode(root);
                            } else {
                                LOGGER.error("Pure sql");
                            }
                        } catch (Exception e) {
                            LOGGER.error("Cannot get root sql node {}", params, e);
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
                    ParameterMap param = statement.getParameterMap();
                    Class<?> type = param.getType();
                    // We can only get the element type of List or Set at runtime, so just ignore here.
                    // Only handle user defined class in package starts with com.
                    if (!customized(type)) {
                        continue;
                    }
                    Object ins = getObject(type);
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

    private Object getObject(Class<?> type) throws IllegalAccessException {
        Object ins = newInstance(type);
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
                    field.set(ins, "_for_sql_only");
                } else if (ft.isAssignableFrom(int.class)) {
                    field.setInt(ins, 0);
                } else if (ft.isAssignableFrom(Integer.class)) {
                    field.set(ins, 0);
                } else if (ft.isAssignableFrom(Boolean.class)) {
                    field.set(ins, Boolean.TRUE);
                } else if (ft.isAssignableFrom(boolean.class)) {
                    field.setBoolean(ins, true);
                } else if (ft.isAssignableFrom(BigDecimal.class)) {
                    field.set(ins, BigDecimal.valueOf(0L));
                } else if (ft.isAssignableFrom(Long.class)) {
                    field.set(ins, 0L);
                } else if (ft.isAssignableFrom(long.class)) {
                    field.setLong(ins, 0);
                } else if (ft.isAssignableFrom(Double.class)) {
                    field.set(ins, Double.parseDouble("0.0"));
                } else if (ft.isAssignableFrom(double.class)) {
                    field.setDouble(ins, 0.0);
                }
            }
        } else {
            LOGGER.warn("Cannot new instance of {}", type);
        }
        return ins;
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
        return CommentUtils.stripEscape(val);
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

    private String strip2Line(String sql) {
        return CommentUtils.strip2Line(sql);
    }

    private String getSql(SqlSource source, Object parameterObject) {
        String sql = null;
        try {
            var bs = source.getBoundSql(parameterObject);
            sql = strip2Line(bs.getSql());
            if (!isNullOrEmpty(sql)) {
                var stmt = CCJSqlParserUtil.parse(sql);
            }
            return sql;
        } catch (Exception e) {
            LOGGER.error("SQL {}, Context {}", sql, parameterObject, e);
        }
        return "";
    }
}
