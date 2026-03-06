package com.softropic.payam.common.configtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.common.config.CommonConfig;

class JacksonTest {

    //@Test
    void testJackson() {
        ObjectMapper mapper = new CommonConfig().objectMapperBuilder().build();
        //mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        //mapper.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
    }
}
