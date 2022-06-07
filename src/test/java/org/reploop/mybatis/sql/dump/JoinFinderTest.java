package org.reploop.mybatis.sql.dump;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class JoinFinderTest {

    JoinFinder finder = new JoinFinder();

    @Test
    void analyse() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("/tmp/dump.sql"));
        Set<Match> matches = finder.analyse(lines);
        List<Match> sorted = matches.stream()
                .sorted(Comparator.comparing(Match::leftTable).thenComparing(Match::leftColumn).thenComparing(Match::rightTable).thenComparing(Match::rightColumn))
                .toList();
        System.out.println(sorted);

        Map<TableColumn, List<TableColumn>> associates = new HashMap<>();
        for (Match match : sorted) {
            TableColumn left = new TableColumn(match.leftTable(), match.leftColumn());
            TableColumn right = new TableColumn(match.rightTable(), match.rightColumn());
            associate(associates, left, right);
            associate(associates, right, left);
        }
        System.out.println(associates);
    }

    private void associate(Map<TableColumn, List<TableColumn>> associates, TableColumn left, TableColumn right) {
        associates.computeIfAbsent(left, key -> new ArrayList<>()).add(right);
    }

    @Test
    void testAnalyse() {
    }
}