package org.reploop.mybatis.sql.dump;

import org.apache.ibatis.io.Resources;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

@Configuration
public class Config {
    @Value("${workingDirectory:/Users/george/vfx/sources/be/excalibur}")
    private String workingDirectory;
    @Value("${configLocation:classpath:mybatis/mybatis-config.xml}")
    private Resource mybatisConfig;
    @Autowired
    Environment environment;

    @Bean
    public DataSource dummy() {
        return new DummyDataSource();
    }

    private Resource[] mapperLocations(String rootDirectory) throws IOException {
        Path path = Paths.get(rootDirectory);
        Set<Path> files = new HashSet<>();
        Set<Path> projects = new HashSet<>();
        Files.walkFileTree(path, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.endsWith(Path.of("target"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String path = file.toString();
                if (path.endsWith("Mapper.xml")) {
                    files.add(file.normalize());
                }
                if (file.getFileName().toString().equals("pom.xml")) {
                    projects.add(file.normalize());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
        Set<String> cps = new LinkedHashSet<>();
        for (Path pom : projects) {
            Path parent = pom.getParent();
            Path cp = parent.resolve("target").resolve("classes");
            if (Files.exists(cp)) {
                cps.add(cp.normalize().toString());
            }
        }
        setClassLoader(cps);
        var resources = files.stream()
                .map(FileSystemResource::new)
                .collect(Collectors.toList());
        return resources.toArray(new Resource[0]);
    }

    private List<Path> load(String cp) throws IOException {
        Path dir = Paths.get(cp);
        List<Path> classes = new ArrayList<>();
        Files.walkFileTree(dir, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String filename = file.getFileName().toString();
                if (filename.endsWith(".class")) {
                    classes.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
        return classes;
    }

    private void sqlTable(Set<String> cps) throws ClassNotFoundException, IOException {
        String className = "org.mybatis.dynamic.sql.SqlTable";
        Class<?> clazz = Class.forName(className);
        for (String cp : cps) {
            List<Path> paths = load(cp);
        }
    }

    private MapperClassLoader mcl;

    private void setClassLoader(Set<String> cps) {
        ClassLoader parent = Resources.getDefaultClassLoader();
        if (null == parent) {
            parent = Config.class.getClassLoader();
        }
        MapperClassLoader mcl = new MapperClassLoader(parent, cps);
        Resources.setDefaultClassLoader(mcl);
        Properties properties = new Properties();
        properties.put("mcl", mcl);
        PropertiesPropertySource pps = new PropertiesPropertySource("mybatis-sql-dump", properties);
        if (environment instanceof ConfigurableEnvironment ce) {
            ce.getPropertySources().addFirst(pps);
        }
        this.mcl = mcl;
    }

    @Bean
    public SqlSessionFactoryBean sqlSessionFactoryBean(DataSource dummy) throws IOException {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dummy);
        bean.setMapperLocations(mapperLocations(workingDirectory));
        bean.setConfigLocation(mybatisConfig);
        return bean;
    }
}
