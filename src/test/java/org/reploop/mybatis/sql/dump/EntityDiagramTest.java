package org.reploop.mybatis.sql.dump;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.reploop.mybatis.sql.util.CommentUtils.trimSql;

class EntityDiagramTest {
    EntityDiagram diagram;

    @BeforeEach
    void setUp() {
        diagram = new EntityDiagram();
    }

    JoinFinder finder = new JoinFinder();

    @Test
    void generate() throws Exception {
        List<String> lines = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        URL url = EntityDiagramTest.class.getResource("/sql/ai.sql");
        assert url != null;
        try (Stream<String> s = Files.lines(Path.of(url.toURI()))) {
            s.forEach(line -> trimSql(lines, sb, line));
        }
        finder.analyse(lines);
    }
}