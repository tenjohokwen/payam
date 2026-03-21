package com.softropic.payam.utils.sql;

public class UpdateQuery extends SqlQuery {
    @Override
    protected String getQueryType() {
        return "UPDATE";
    }
}
