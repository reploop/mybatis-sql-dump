package org.reploop.mybatis.sql.dump;

import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;
import static org.springframework.util.Assert.notNull;

@Component
public class DumpSql implements InitializingBean {
    @Resource
    private SqlSessionFactoryBean bean;
    @Value("${outputSql:/tmp/dump.sql}")
    private Path outputSql;

    @Override
    public void afterPropertiesSet() throws Exception {
        notNull(bean, "");
        var factory = bean.getObject();
        assert factory != null;
        var conf = factory.getConfiguration();
        Collection<String> names = conf.getMappedStatementNames();
        List<String> all = new ArrayList<>();
        for (String name : names) {
            // It's a full name, not a short name.
            if (name.contains(".")) {
                MappedStatement statement = conf.getMappedStatement(name);
                SqlCommandType type = statement.getSqlCommandType();
                if (type == SqlCommandType.SELECT) {
                    SqlSource source = statement.getSqlSource();
                    if (source instanceof RawSqlSource) {
                        var bs = source.getBoundSql(null);
                        String sql = bs.getSql();
                        String s = sql.replaceAll("\\s+", " ") + ";";
                        all.add(s);
                    }
                }
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputSql, UTF_8, CREATE, TRUNCATE_EXISTING, WRITE)) {
            for (String sql : all) {
                if (sql.toUpperCase().startsWith(SqlCommandType.SELECT.name())) {
                    writer.write(sql);
                    writer.newLine();
                }
            }
        }
    }
}
