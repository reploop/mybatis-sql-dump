package org.reploop.mybatis.sql.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.*;
import java.util.Calendar;

public class CalendarTypeHandler extends BaseTypeHandler<Calendar> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Calendar parameter, JdbcType jdbcType) throws SQLException {
        ps.setTimestamp(i, new Timestamp(parameter.getTimeInMillis()));
    }

    @Override
    public Calendar getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Timestamp ts = rs.getTimestamp(columnName);
        var cal = Calendar.getInstance();
        if (null != ts) {
            cal.setTimeInMillis(ts.getTime());
        }
        return cal;
    }

    @Override
    public Calendar getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Timestamp ts = rs.getTimestamp(columnIndex);
        var cal = Calendar.getInstance();
        if (null != ts) {
            cal.setTimeInMillis(ts.getTime());
        }
        return cal;
    }

    @Override
    public Calendar getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Timestamp ts = cs.getTimestamp(columnIndex);
        var cal = Calendar.getInstance();
        if (null != ts) {
            cal.setTimeInMillis(ts.getTime());
        }
        return cal;
    }
}
