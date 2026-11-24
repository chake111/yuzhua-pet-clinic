package com.yuzhua.it;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.yuzhua.props.AppProperties;
import com.yuzhua.dto.AppointmentForm;
import com.yuzhua.security.LoginUser;
import com.yuzhua.task.AppointmentSweepTask;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试共用底座：真实 MySQL（Testcontainers）+ 每个用例前重建干净夹具。
 *
 * <p>类名刻意不以 IT 结尾，避免被 Failsafe 当成测试类执行（本类没有 @Test）。
 * 容器与 schema 在静态初始化时一次性建好，各实现类共享同一个 Spring 上下文，
 * 数据库状态由 {@link #resetDatabase()} 在每个用例前重置。
 */
// 用真实 Servlet 容器：WebSocketConfig 需要 ServerContainer 属性，MOCK 环境下没有
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // yuzhua 前缀；JWT_SECRET 默认为空，这里必须显式给 >=32 字节的密钥
        "yuzhua.jwt.secret=it-integration-test-jwt-secret-0123456789abcdef",
        "spring.datasource.hikari.maximum-pool-size=50",
        "spring.datasource.hikari.connection-timeout=30000",
        "logging.level.com.yuzhua=WARN"
})
public abstract class MySqlITSupport {

    /** 与 sql/02_data.sql 的种子账号密码一致（明文 123456） */
    protected static final String SEED_PASSWORD = "123456";

    /** sql/02_data.sql 里 123456 的 BCrypt 哈希（强度 10）；子类插第二个账号时可直接复用 */
    protected static final String SEED_PASSWORD_HASH =
            "$2a$10$0yzLXUNo8OV8t9fBdTlYFumqcM4IBAsInS.neeSzPwQvEtLKjvIGm";

    /** schema 里的全部 13 张表；外键检查关闭后按任意顺序清空 */
    private static final String[] ALL_TABLES = {
            "appointment", "consult_message", "consult_session", "medical_record",
            "doctor_review", "notice", "doctor_schedule", "doctor",
            "admin_user", "sys_user", "pet", "department", "scheduler_lease"
    };

    protected static final long IT_USER_ID = 9001L;
    protected static final long IT_DOCTOR_ID = 9001L;
    protected static final long IT_SCHEDULE_ID = 9001L;
    protected static final long FIRST_PET_ID = 90001L;

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("yuzhua")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("serverTimezone", "Asia/Shanghai")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("rewriteBatchedStatements", "true");

    static {
        MYSQL.start();
        // Ryuk 也会清理；这个 hook 让本地直接 STOP 掉的 JVM 也尽量立刻释放容器
        Runtime.getRuntime().addShutdownHook(new Thread(MYSQL::stop, "it-mysql-shutdown"));
        loadSchema();
    }

    private static void loadSchema() {
        // 01_schema.sql 第一句是 DROP DATABASE，必须用“无默认库”的 root 连接执行；
        // 脚本是 UTF-8，而本机平台编码是 GBK，中文 COMMENT 必须显式按 UTF-8 解码。
        String rootUrl = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection conn = DriverManager.getConnection(rootUrl, "root", MYSQL.getPassword())) {
            ScriptUtils.executeSqlScript(conn, new EncodedResource(
                    new FileSystemResource("../sql/01_schema.sql"), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** @EnableScheduling 已开启；停掉 60s 清扫，避免后台定时器干扰过期相关断言 */
    @MockitoBean
    protected AppointmentSweepTask appointmentSweepTask;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected AppProperties appProperties;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute((ConnectionCallback<Void>) conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : ALL_TABLES) {
                    st.execute("TRUNCATE TABLE `" + table + "`");
                }
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        seedFixtures();
    }

    private void seedFixtures() {
        jdbc.update("INSERT INTO department (id, name, code, status) VALUES (?, ?, ?, 1)",
                9001L, "IT内科", "IT9001");

        jdbc.update("INSERT INTO doctor (id, name, department_id, consultation_fee, status) "
                        + "VALUES (?, ?, ?, ?, 1)",
                9001L, "IT医生", 9001L, new BigDecimal("80.00"));

        jdbc.update("INSERT INTO doctor_schedule (id, doctor_id, work_date, period, start_time, end_time, "
                        + "total_num, remain_num, last_queue_no, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                9001L, 9001L, LocalDate.now().plusDays(1), 3,
                LocalTime.of(18, 0), LocalTime.of(21, 30), 20, 20, 0);

        // phone 是生成列 active_phone 的来源列，只插 phone；生成列由 MySQL 自己算
        jdbc.update("INSERT INTO sys_user (id, username, password, nickname, phone, status, token_version) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 1)",
                9001L, "ituser", SEED_PASSWORD_HASH, "IT用户", "13900009001");

        // admin_user.role 默认 DOCTOR，管理员必须显式写 ADMIN
        jdbc.update("INSERT INTO admin_user (id, username, password, real_name, role, status, token_version) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 1)",
                9001L, "itadmin", SEED_PASSWORD_HASH, "IT管理员", "ADMIN");

        List<Object[]> pets = new ArrayList<>(100);
        for (int i = 1; i <= 100; i++) {
            pets.add(new Object[]{90000L + i, 9001L, "IT宠物" + i, "猫"});
        }
        jdbc.batchUpdate("INSERT INTO pet (id, user_id, name, species, deleted) VALUES (?, ?, ?, ?, 0)", pets);
    }

    // ---------------------------------------------------------------- 辅助

    protected static LoginUser clientUser() {
        return new LoginUser(9001L, "ituser", "IT用户", null, "CLIENT", "USER", null, 1, null);
    }

    protected AppointmentForm appointmentForm(long petId) {
        return appointmentForm(petId, IT_DOCTOR_ID, IT_SCHEDULE_ID, "IT 测试");
    }

    protected AppointmentForm appointmentForm(long petId, long doctorId, long scheduleId, String symptom) {
        AppointmentForm form = new AppointmentForm();
        form.setPetId(petId);
        form.setDoctorId(doctorId);
        form.setScheduleId(scheduleId);
        form.setSymptom(symptom);
        return form;
    }

    /** 用户端登录，断言 HTTP 200 + body.code=200，返回 data.token */
    protected String loginToken(MockMvc mvc, String username) throws Exception {
        return tokenOf(mvc.perform(post("/client/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn());
    }

    /** 后台登录（管理员 / 医生共用入口） */
    protected String loginAdminToken(MockMvc mvc, String username) throws Exception {
        return tokenOf(mvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn());
    }

    /** 下单并断言成功，返回完整响应 JSON（调用方从 data.id 取单号） */
    protected JsonNode createAppointment(MockMvc mvc, String token, long petId,
                                         long doctorId, long scheduleId, String symptom) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", petId);
        body.put("doctorId", doctorId);
        body.put("scheduleId", scheduleId);
        body.put("symptom", symptom);
        MvcResult result = mvc.perform(post("/client/appointment")
                        .header(authHeaderName(), bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return responseJson(result);
    }

    /** 与 5 参版本相同，但带上 Idempotency-Key（null 表示不发该头） */
    protected JsonNode createAppointment(MockMvc mvc, String token, long petId,
                                         long doctorId, long scheduleId, String symptom,
                                         String idemKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", petId);
        body.put("doctorId", doctorId);
        body.put("scheduleId", scheduleId);
        body.put("symptom", symptom);
        MockHttpServletRequestBuilder request = post("/client/appointment")
                .header(authHeaderName(), bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (idemKey != null) {
            request.header("Idempotency-Key", idemKey);
        }
        MvcResult result = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return responseJson(result);
    }

    protected String authHeaderName() {
        return appProperties.getJwt().getHeader();
    }

    protected String bearer(String token) {
        return appProperties.getJwt().getPrefix() + token;
    }

    protected JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected int queryInt(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        if (value == null) {
            throw new IllegalStateException("查询无结果: " + sql);
        }
        return value;
    }

    protected String queryString(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    protected LocalDateTime queryDateTime(String sql, Object... args) {
        return jdbc.queryForObject(sql, LocalDateTime.class, args);
    }

    private String loginBody(String username) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", SEED_PASSWORD);
        return objectMapper.writeValueAsString(body);
    }

    private String tokenOf(MvcResult result) throws Exception {
        String token = responseJson(result).path("data").path("token").asString();
        if (token == null || token.isBlank()) {
            throw new AssertionError("登录响应里没有 data.token: "
                    + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        return token;
    }
}