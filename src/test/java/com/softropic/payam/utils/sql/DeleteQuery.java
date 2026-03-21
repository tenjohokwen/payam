package com.softropic.payam.utils.sql;

public class DeleteQuery extends SqlQuery {
    @Override
    protected String getQueryType() {
        return "DELETE";
    }
}
