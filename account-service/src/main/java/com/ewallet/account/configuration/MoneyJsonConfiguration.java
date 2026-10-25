package com.ewallet.account.configuration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MoneyJsonConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer exactMoneyJson() {
        return builder -> {
            builder.serializerByType(BigDecimal.class, new StdSerializer<BigDecimal>(BigDecimal.class) {
                @Override
                public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                    generator.writeString(value.toPlainString());
                }
            });
            builder.postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
        };
    }
}
