package com.yuzhua.dto;

import com.yuzhua.common.GlobalExceptionHandler;
import com.yuzhua.common.Result;
import com.yuzhua.config.JacksonConfig;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FormValidationAndPatchSemanticsTest {

    private JsonMapper mapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().jsr310FormatCustomizer().customize(builder);
        mapper = builder.build();

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new ValidationProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
    }

    @Test
    void petFormDistinguishesOmittedFromExplicitNullForBirthdayAndWeight() {
        PetForm omitted = mapper.readValue("""
                {"name":"奶糖","species":"猫"}
                """, PetForm.class);
        assertFalse(omitted.isBirthdaySubmitted());
        assertFalse(omitted.isWeightSubmitted());
        assertNull(omitted.getBirthday());
        assertNull(omitted.getWeight());

        PetForm explicitNull = mapper.readValue("""
                {"name":"奶糖","species":"猫","birthday":null,"weight":null}
                """, PetForm.class);
        assertTrue(explicitNull.isBirthdaySubmitted());
        assertTrue(explicitNull.isWeightSubmitted());
        assertNull(explicitNull.getBirthday());
        assertNull(explicitNull.getWeight());

        PetForm populated = mapper.readValue("""
                {"name":"奶糖","species":"猫","birthday":"2023-05-01","weight":4.25}
                """, PetForm.class);
        assertTrue(populated.isBirthdaySubmitted());
        assertTrue(populated.isWeightSubmitted());
        assertEquals(LocalDate.of(2023, 5, 1), populated.getBirthday());
        assertEquals(new BigDecimal("4.25"), populated.getWeight());
    }

    @Test
    void medicalRecordFormDistinguishesOmittedFromExplicitNullForNextVisitDate() {
        MedicalRecordForm omitted = mapper.readValue("""
                {"diagnosis":"肠胃炎"}
                """, MedicalRecordForm.class);
        assertFalse(omitted.isNextVisitDateSubmitted());
        assertNull(omitted.getNextVisitDate());

        MedicalRecordForm explicitNull = mapper.readValue("""
                {"diagnosis":"肠胃炎","nextVisitDate":null}
                """, MedicalRecordForm.class);
        assertTrue(explicitNull.isNextVisitDateSubmitted());
        assertNull(explicitNull.getNextVisitDate());
    }

    @Test
    void scheduleBatchFormHandlesNullAndEmptyListsViaValidation400WithoutNpe() throws Exception {
        mockMvc.perform(post("/probe/schedule-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doctorId":1,"startDate":"2026-10-01","endDate":"2026-10-07","weekDays":null,"periods":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/probe/schedule-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doctorId":1,"startDate":"2026-10-01","endDate":"2026-10-07","weekDays":[],"periods":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void formDtosAndOptionalBodiesRejectInvalidPayloadsWithBusiness400() throws Exception {
        mockMvc.perform(post("/probe/pet")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" ","species":"猫"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/probe/record")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diagnosis":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/probe/department")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        String over200 = "a".repeat(201);
        mockMvc.perform(post("/probe/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + over200 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/probe/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String over500 = "b".repeat(501);
        mockMvc.perform(post("/probe/consult-close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"" + over500 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/probe/notice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"草稿","status":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/probe/notice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":" ","status":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @RestController
    static class ValidationProbeController {

        @PostMapping("/probe/notice")
        public Result<Void> notice(@Valid @RequestBody NoticeForm form) {
            return Result.ok(null);
        }

        @PostMapping("/probe/schedule-batch")
        public Result<Void> scheduleBatch(@Valid @RequestBody ScheduleBatchForm form) {
            return Result.ok(null);
        }

        @PostMapping("/probe/pet")
        public Result<Void> pet(@Valid @RequestBody PetForm form) {
            return Result.ok(null);
        }

        @PostMapping("/probe/record")
        public Result<Void> record(@Valid @RequestBody MedicalRecordForm form) {
            return Result.ok(null);
        }

        @PostMapping("/probe/department")
        public Result<Void> department(@Valid @RequestBody DepartmentForm form) {
            return Result.ok(null);
        }

        @PostMapping("/probe/cancel")
        public Result<Void> cancel(@RequestBody(required = false) @Valid @Nullable CancelForm form) {
            return Result.ok(null);
        }

        @PostMapping("/probe/consult-close")
        public Result<Void> consultClose(@RequestBody(required = false) @Valid @Nullable ConsultCloseForm form) {
            return Result.ok(null);
        }
    }
}
