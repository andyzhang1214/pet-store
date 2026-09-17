package cn.petshop.admin.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * JSON 序列化约定。
 *
 * <h3>Long 一律序列化成字符串</h3>
 * 主键用 MyBatis-Plus 的雪花算法，是 19 位十进制数（如 {@code 2099772780119552001}）。
 * JavaScript 的 {@code Number} 只能安全表示到 2^53-1（16 位），
 * 因此这个 id 一进浏览器就会被<b>静默截断</b>成 {@code 2099772780119552000} ——
 * 不报错、不告警，只是从此对不上任何一条真实数据。
 *
 * <p>症状通常表现为「后台点了审核但提示单据不存在」「列表能看不能操作」，
 * 而后端的日志里是一堆查不到的 id。调试时极易误判为并发或数据被删。
 *
 * <p>修法只能放在序列化层：把 Long 输出成 JSON 字符串，让 id 以文本形态穿过 JS。
 * 代价是计数器（如 {@code total} / {@code pendingCount}）也变成字符串，
 * 展示无影响，但前端不得再对它做算术 —— 需要算术时先 {@code Number()}。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .simpleDateFormat("yyyy-MM-dd HH:mm:ss")
                // 雪花 id 必须走字符串，否则 JS 端精度丢失（见类注释）
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance)
                .serializers(new com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .serializers(new com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .serializers(new com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer(
                        DateTimeFormatter.ofPattern("HH:mm")))
                .deserializers(new com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .deserializers(new com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .deserializers(new com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer(
                        DateTimeFormatter.ofPattern("HH:mm")));
    }
}
