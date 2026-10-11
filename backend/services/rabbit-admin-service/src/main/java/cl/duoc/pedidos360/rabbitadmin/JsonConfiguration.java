package cl.duoc.pedidos360.rabbitadmin;

import org.springframework.context.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods=false)
public class JsonConfiguration {
    @Bean JsonMapper jsonMapper() {
        return JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual,c -> {
                for(var shape:java.util.List.of(tools.jackson.databind.cfg.CoercionInputShape.Integer,
                    tools.jackson.databind.cfg.CoercionInputShape.Float,tools.jackson.databind.cfg.CoercionInputShape.Boolean))
                    c.setCoercion(shape,tools.jackson.databind.cfg.CoercionAction.Fail);
            }).build();
    }
}
