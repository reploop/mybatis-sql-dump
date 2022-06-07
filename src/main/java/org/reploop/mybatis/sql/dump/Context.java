package org.reploop.mybatis.sql.dump;

import java.util.ArrayList;
import java.util.List;

public class Context {
    List<Match> matches = new ArrayList<>();

    public List<Match> getMatches() {
        return matches;
    }

    public void setMatches(List<Match> matches) {
        this.matches = matches;
    }
}
