package com.yuzhua.domain.enums;

import com.yuzhua.common.GlobalExceptionHandler;
import com.yuzhua.common.Result;
import com.yuzhua.config.WebMvcConfig;
import com.yuzhua.props.AppProperties;
import com.yuzhua.security.AccountGuard;
import com.yuzhua.security.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 15 个 CodedEnum 领域枚举的 Jackson 3 编解码、Spring MVC 查询参数转换与非法码 400 映射测试。
 */
class CodedEnumCodecTest {

    private static final List<Class<? extends CodedEnum<?>>> ALL_ENUMS = List.of(
            AdminRole.class,
            AppointmentStatus.class,
            CancelSource.class,
            ConsultModerationStatus.class,
            ConsultMsgType.class,
            ConsultSenderRole.class,
            ConsultSessionStatus.class,
            EnableStatus.class,
            NoticeStatus.class,
            NoticeType.class,
            PayStatus.class,
            SchedulePeriod.class,
            TokenType.class,
            UserRole.class,
            ConsultType.class
    );

    @Test
    void coversAllFifteenDomainEnums() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(CodedEnum.class));
        Set<String> discovered = scanner.findCandidateComponents("com.yuzhua.domain.enums").stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());

        Set<String> listed = ALL_ENUMS.stream().map(Class::getName).collect(Collectors.toSet());
        assertThat(ALL_ENUMS).hasSize(15);
        assertThat(listed).isEqualTo(discovered);
    }

    @Test
    void jackson3RoundTripsAllValuesAndRejectsInvalidCodes() {
        JsonMapper mapper = JsonMapper.builder().build();
        for (Class<? extends CodedEnum<?>> enumType : ALL_ENUMS) {
            CodedEnum<?>[] constants = enumType.getEnumConstants();
            assertThat(constants).isNotEmpty();
            for (CodedEnum<?> constant : constants) {
                String json = mapper.writeValueAsString(constant);
                Object decoded = mapper.readValue(json, enumType);
                assertThat(decoded).isSameAs(constant);
            }
            String badJson = constants[0].code() instanceof Integer ? "999999" : "\"__INVALID__\"";
            assertThatThrownBy(() -> mapper.readValue(badJson, enumType))
                    .isInstanceOf(JacksonException.class);
        }
    }

    @Test
    void webMvcConverterHandlesValidBlankAndInvalidCodes() {
        DefaultFormattingConversionService cs = conversionService();
        for (Class<? extends CodedEnum<?>> enumType : ALL_ENUMS) {
            for (CodedEnum<?> constant : enumType.getEnumConstants()) {
                Object parsed = cs.convert(String.valueOf(constant.code()), enumType);
                assertThat(parsed).isSameAs(constant);
            }
            assertThat(cs.convert("", enumType)).isNull();
            assertThat(cs.convert("   ", enumType)).isNull();
            assertThatThrownBy(() -> cs.convert("__INVALID__", enumType))
                    .isInstanceOf(ConversionFailedException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void globalExceptionHandlerMapsInvalidEnumQueryAndBodyToBusiness400() throws Exception {
        DefaultFormattingConversionService cs = conversionService();
        JsonMapper mapper = JsonMapper.builder().build();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EnumProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setConversionService(cs)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();

        mvc.perform(get("/probe").param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1));

        mvc.perform(get("/probe").param("status", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mvc.perform(get("/probe").param("status", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mvc.perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1));

        mvc.perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    private static DefaultFormattingConversionService conversionService() {
        DefaultFormattingConversionService cs = new DefaultFormattingConversionService();
        WebMvcConfig config = new WebMvcConfig(
                mock(JwtUtils.class), new AppProperties(), mock(AccountGuard.class), new JsonMapper());
        config.addFormatters(cs);
        return cs;
    }

    @RestController
    static class EnumProbeController {
        @GetMapping("/probe")
        public Result<AppointmentStatus> query(@RequestParam(required = false) AppointmentStatus status) {
            return Result.ok(status);
        }

        @PostMapping("/probe")
        public Result<AppointmentStatus> body(@RequestBody ProbeBody body) {
            return Result.ok(body.status);
        }
    }

    static class ProbeBody {
        public AppointmentStatus status;
    }
}
