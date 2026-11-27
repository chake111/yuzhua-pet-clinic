package com.yuzhua.arch;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CommentGuardrailsTest {

    private static final String MSG_RISK_DOC =
            "高风险边界（事务/并发/鉴权/协议）缺少契约说明。禁止复述名称或类型；仅写锁顺序、前置条件、幂等或失败语义（<= 16 行）。";
    private static final String MSG_UNEXPECTED_DOC =
            "普通类型/方法/导出及 package-info.java 默认不写 Javadoc/JSDoc。若仅复述名称请直接删除；确有非显然约束请改写为 <= 6 行的「// 原因: ...」。";
    private static final String MSG_INLINE_PREFIX =
            "源码默认不写注释。若仅解说代码/模板动作请直接删除；仅当记录非显然原因时独立成行改写为「// 原因: ...」（模板用「<!-- 原因: ... -->」）或「TODO(#n): ...」。";
    private static final String MSG_DEAD_CODE =
            "禁止保留被注释掉的代码或模板片段，请直接删除（历史版本由 Git 保留）。";
    private static final String MSG_CHANGE_LOG =
            "禁止在源码中写「本次修改/修复/以前是…」类变更叙述，请删去或迁入提交说明。";
    private static final String MSG_DENSITY =
            "注释密度或单块长度超限。请删复述解说；长背景原理请迁入 docs/adr/，源码仅留核心不变量与文档路径。";
    private static final String MSG_SUPPRESSION_RATCHET =
            "抑制超基线或关联 Issue 无效。请修复根因；仅编译器已知误报可在 open Issue 获人类维护者打标 guardrail:tracked 及 guardrail:budget-approved 后登记。";

    private static final int MAX_DOC_LINES = 16;
    private static final int MAX_INLINE_LINES = 6;
    private static final int MIN_CODE_LINES_FOR_DENSITY = 20;
    private static final double MAX_COMMENT_DENSITY = 0.35;
    private static final int FALLBACK_MAIN_SUPPRESSION_BUDGET = 0;
    private static final int FALLBACK_TEST_SUPPRESSION_BUDGET = 15;

    private static final Pattern REASON_PREFIX = Pattern.compile("^ 原因: \\S.*$");
    private static final Pattern TODO_PREFIX = Pattern.compile("^ TODO\\(#\\d+\\): \\S.*$");
    private static final Pattern ISSUE_REF = Pattern.compile("#\\d+");
    private static final Pattern FORBIDDEN_SUPPRESSION_DIRECTIVE =
            Pattern.compile("(?i://\\s*(?:CHECKSTYLE|spotless):(?:OFF|ON))|\\b(?:FIXME|HACK|XXX)\\b");
    private static final Pattern UNNUMBERED_TODO = Pattern.compile("\\bTODO\\b(?!\\(#\\d+\\):)");
    private static final Pattern CHANGE_LOG_PATTERN = Pattern.compile(
            "本次(修改|新增|修复|优化|重构|调整|删除)|这次改|改成.*了|以前是.*现在|\\bhotfix\\b|临时(改|加|绕过)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEAD_CODE_LINE = Pattern.compile(
            "(?:[;{}]$"
                    + "|^(?:if|for|while)\\s*\\("
                    + "|^return(?:\\s+.+)?;$"
                    + "|^return\\b"
                    + "|^(?:import|export|const|let)\\s+[A-Za-z_$]"
                    + "|^(?:private|public|protected)\\s+(?:static\\s+|final\\s+)?[A-Za-z_<>\\[\\]]+\\s+[A-Za-z_$]+\\s*[=(;]"
                    + "|\\.[A-Za-z0-9_]+\\([^)]*\\)\\s*;"
                    + "|\\.set[A-Z][A-Za-z0-9_]*\\("
                    + "|\\b(?:System\\.(?:out|err)\\.|console\\.|log\\.(?:info|warn|error|debug|trace)\\()"
                    + "|\\.printStackTrace\\("
                    + "|<(?:el-|div\\b|span\\b))");
    private static final Pattern BACKTICK_OR_CODE_SPAN = Pattern.compile("`[^`]*`|\\{@code\\s+[^}]*\\}");

    private static final Set<String> DATA_CARRIER_PACKAGES = Set.of(
            "com.yuzhua.vo", "com.yuzhua.dto", "com.yuzhua.entity", "com.yuzhua.props");
    private static final Set<String> CONTROLLER_MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory");

    private static final JavaParser JAVA_PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25));
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void mainSourcesShouldComplyWithCommentAndJavadocGuardrails() throws IOException {
        Path backendDir = resolveBackendDir();
        Path mainRoot = backendDir.resolve("src/main/java");
        Budget budget = loadSuppressionBudget(backendDir);
        List<String> bannedTerms = loadBannedTerms(backendDir);

        List<String> violations = new ArrayList<>();
        int suppressions = 0;

        for (Path file : listJavaFiles(mainRoot)) {
            if (isExcludedGuardrailsPath(file)) {
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String relPath = backendDir.relativize(file).toString().replace('\\', '/');
            AnalysisResult result = analyzeMainSource(relPath, source, bannedTerms);
            violations.addAll(result.violations());
            suppressions += result.suppressionCount();
        }

        if (suppressions > budget.backendMain()) {
            violations.add("[COMMENT-SUPPRESSION-RATCHET] backend/src/main @SuppressWarnings 数量 "
                    + suppressions + " 超过配额 " + budget.backendMain() + "。" + MSG_SUPPRESSION_RATCHET);
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void testSourcesShouldComplyWithCommentSuppressionAndAssertionGuardrails() throws IOException {
        Path backendDir = resolveBackendDir();
        Path testRoot = backendDir.resolve("src/test/java");
        Budget budget = loadSuppressionBudget(backendDir);
        List<String> bannedTerms = loadBannedTerms(backendDir);

        List<String> violations = new ArrayList<>();
        int suppressions = 0;

        for (Path file : listJavaFiles(testRoot)) {
            if (isExcludedGuardrailsPath(file)) {
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String relPath = backendDir.relativize(file).toString().replace('\\', '/');
            AnalysisResult result = analyzeTestSource(relPath, source, bannedTerms);
            violations.addAll(result.violations());
            suppressions += result.suppressionCount();
        }

        if (suppressions > budget.backendTest()) {
            violations.add("[COMMENT-SUPPRESSION-RATCHET] backend/src/test @SuppressWarnings 数量 "
                    + suppressions + " 超过配额 " + budget.backendTest() + "。" + MSG_SUPPRESSION_RATCHET);
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void checkstyleRegexesShouldMatchPositiveAndIgnoreNegativeCases() throws Exception {
        Path checkstylePath = resolveBackendDir().resolve("config/checkstyle/checkstyle.xml");
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(checkstylePath.toFile());

        List<String> singlelineFormats = new ArrayList<>();
        String emptyCatchCommentFormat = null;
        NodeList modules = doc.getElementsByTagName("module");
        for (int i = 0; i < modules.getLength(); i++) {
            Element mod = (Element) modules.item(i);
            String name = mod.getAttribute("name");
            if ("RegexpSingleline".equals(name)) {
                singlelineFormats.add(readProperty(mod, "format"));
            } else if ("EmptyCatchBlock".equals(name)) {
                emptyCatchCommentFormat = readProperty(mod, "commentFormat");
            }
        }

        assertThat(singlelineFormats).hasSize(3);
        Pattern sysOutPattern = Pattern.compile(singlelineFormats.get(0));
        Pattern printStackPattern = Pattern.compile(singlelineFormats.get(1));
        Pattern inlineTogglePattern = Pattern.compile(singlelineFormats.get(2));
        Pattern emptyCatchPattern = Pattern.compile(emptyCatchCommentFormat);

        assertThat(sysOutPattern.matcher("System.out.println(1);").find()).isTrue();
        assertThat(sysOutPattern.matcher("System.err.print(1);").find()).isTrue();
        assertThat(sysOutPattern.matcher("log.info(\"ok\");").find()).isFalse();

        assertThat(printStackPattern.matcher("e.printStackTrace();").find()).isTrue();
        assertThat(printStackPattern.matcher("log.error(\"err\", e);").find()).isFalse();

        assertThat(inlineTogglePattern.matcher("// CHECKSTYLE:OFF").find()).isTrue();
        assertThat(inlineTogglePattern.matcher("// spotless:off").find()).isTrue();
        assertThat(inlineTogglePattern.matcher("// 原因: 合法注释").find()).isFalse();

        assertThat(emptyCatchPattern.matcher(" 原因: 连接已关闭时忽略").find()).isTrue();
        assertThat(emptyCatchPattern.matcher(" ignore").find()).isFalse();
    }

    @Test
    void commentGuardrailsShouldRejectAllViolationCategoriesAndAcceptWhitelistedContracts() {
        List<String> banned = List.of("axios");

        String missingTransactionalDoc = """
                package com.yuzhua.service;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                @Service
                public class DemoService {
                    @Transactional
                    public void run() {}
                }
                """;
        assertThat(analyzeMainSource("DemoService.java", missingTransactionalDoc, banned).violations())
                .anyMatch(v -> v.contains("COMMENT-RISK-DOC"));

        String unexpectedVoDoc = """
                package com.yuzhua.vo;
                /** 禁止的 VO 文档注释 */
                public record DemoVo(Long id) {}
                """;
        assertThat(analyzeMainSource("DemoVo.java", unexpectedVoDoc, banned).violations())
                .anyMatch(v -> v.contains("COMMENT-UNEXPECTED-DOC"));

        String badInlineAndTrailing = """
                package com.yuzhua.service;
                public class DemoService {
                    // 复述下面的代码
                    public int add(int a, int b) {
                        return a + b; // 行尾注释
                    }
                }
                """;
        List<String> inlineViolations = analyzeMainSource("DemoService.java", badInlineAndTrailing, banned).violations();
        assertThat(inlineViolations).hasSize(2)
                .allMatch(v -> v.contains("COMMENT-INLINE-PREFIX"));

        String deadCodeAndChangeLog = """
                package com.yuzhua.service;
                public class DemoService {
                    // 原因: 本次修改增加了校验
                    // 原因: int old = 1;
                    public int value() { return 1; }
                }
                """;
        List<String> noiseViolations = analyzeMainSource("DemoService.java", deadCodeAndChangeLog, banned).violations();
        assertThat(noiseViolations).anyMatch(v -> v.contains("COMMENT-CHANGE-LOG"));
        assertThat(noiseViolations).anyMatch(v -> v.contains("COMMENT-DEAD-CODE"));

        String bannedAndSuppression = """
                package com.yuzhua.service;
                public class DemoService {
                    // 原因: 前端 axios 拦截器读取
                    @SuppressWarnings("unchecked")
                    public void run() {}
                }
                """;
        List<String> termAndSupp = analyzeMainSource("DemoService.java", bannedAndSuppression, banned).violations();
        assertThat(termAndSupp).anyMatch(v -> v.contains("COMMENT-BANNED-TERM"));
        assertThat(termAndSupp).anyMatch(v -> v.contains("COMMENT-SUPPRESSION-RATCHET"));

        String highDensityAndLongBlock = """
                package com.yuzhua.security;
                /**
                 * 1
                 * 2
                 * 3
                 * 4
                 * 5
                 * 6
                 * 7
                 * 8
                 * 9
                 * 10
                 * 11
                 * 12
                 * 13
                 * 14
                 * 15
                 * 16
                 * 17
                 */
                public class DemoGuard {
                    /** 校验令牌。 */
                    public boolean check() { return true; }
                }
                """;
        assertThat(analyzeMainSource("DemoGuard.java", highDensityAndLongBlock, banned).violations())
                .anyMatch(v -> v.contains("COMMENT-DENSITY"));

        String emptyTest = """
                package com.yuzhua.service;
                import org.junit.jupiter.api.Test;
                class DemoTest {
                    @Test
                    void emptyShouldFail() {
                        int x = 1 + 1;
                    }
                }
                """;
        assertThat(analyzeTestSource("DemoTest.java", emptyTest, banned).violations())
                .anyMatch(v -> v.contains("TEST-EMPTY-ASSERTION"));

        String validWhitelisted = """
                package com.yuzhua.security;
                /** 鉴权上下文契约。 */
                public class DemoContext {
                    /** 读取并校验当前登录人，未登录抛 401。 */
                    public String requireUser() {
                        // 原因: 仅示例允许的理由前缀
                        return "ok";
                    }
                }
                """;
        assertThat(analyzeMainSource("DemoContext.java", validWhitelisted, banned).violations()).isEmpty();
    }

    @Test
    void javaParserSupportsJava25Syntax() {
        String java25Source = """
                package com.yuzhua.dto;
                public sealed interface Frame permits Frame.Ok, Frame.Fail {
                    record Ok(int code) implements Frame {}
                    record Fail(String msg) implements Frame {}
                    static int handle(Frame f) {
                        return switch (f) {
                            case Ok(var code) -> code;
                            case Fail(_) -> -1;
                        };
                    }
                }
                """;
        assertThat(analyzeMainSource("Frame.java", java25Source, List.of("axios")).violations()).isEmpty();
    }

    private AnalysisResult analyzeMainSource(String relPath, String source, List<String> bannedTerms) {
        List<String> violations = new ArrayList<>();
        CompilationUnit cu = parse(relPath, source, violations);
        if (cu == null) {
            return new AnalysisResult(violations, 0);
        }

        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        if (relPath.endsWith("/package-info.java") || relPath.equals("package-info.java")) {
            for (Comment c : cu.getAllComments()) {
                if (c instanceof JavadocComment) {
                    violations.add(formatViolation("COMMENT-UNEXPECTED-DOC", relPath, lineOf(c),
                            "package-info.java", MSG_UNEXPECTED_DOC));
                }
            }
        }

        checkMainJavadocWhitelist(relPath, pkg, cu, violations);
        checkCommentsCommon(relPath, source, cu, bannedTerms, true, violations);
        checkDensity(relPath, source, cu, violations);
        int suppressions = checkSuppressions(relPath, source, cu, true, violations);

        return new AnalysisResult(violations, suppressions);
    }

    private AnalysisResult analyzeTestSource(String relPath, String source, List<String> bannedTerms) {
        List<String> violations = new ArrayList<>();
        CompilationUnit cu = parse(relPath, source, violations);
        if (cu == null) {
            return new AnalysisResult(violations, 0);
        }

        checkCommentsCommon(relPath, source, cu, bannedTerms, false, violations);
        int suppressions = checkSuppressions(relPath, source, cu, false, violations);

        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            if (isTestMethod(method) && !hasWhitelistedAssertion(method)) {
                violations.add("[TEST-EMPTY-ASSERTION] " + relPath + ":" + lineOf(method) + " "
                        + method.getNameAsString() + ": 测试方法缺少有效断言（assert*/Assertions.*/assertThat/verify/rule.check/andExpect/assertThrows/expect）。");
            }
        }
        return new AnalysisResult(violations, suppressions);
    }

    private CompilationUnit parse(String relPath, String source, List<String> violations) {
        ParseResult<CompilationUnit> parsed = JAVA_PARSER.parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            violations.add("[PARSE-ERROR] " + relPath + ": JavaParser 解析失败: " + parsed.getProblems());
            return null;
        }
        return parsed.getResult().get();
    }

    private void checkMainJavadocWhitelist(String relPath, String pkg, CompilationUnit cu, List<String> violations) {
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            boolean allowed = isWhitelistedType(pkg, type);
            boolean hasDoc = type.getJavadocComment().isPresent();
            if (allowed && !hasDoc) {
                violations.add(formatViolation("COMMENT-RISK-DOC", relPath, lineOf(type),
                        type.getNameAsString(), MSG_RISK_DOC));
            } else if (!allowed && hasDoc) {
                violations.add(formatViolation("COMMENT-UNEXPECTED-DOC", relPath, lineOf(type),
                        type.getNameAsString(), MSG_UNEXPECTED_DOC));
            }
        }

        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            boolean allowed = isWhitelistedMethod(pkg, method);
            boolean hasDoc = method.getJavadocComment().isPresent();
            if (allowed && !hasDoc) {
                violations.add(formatViolation("COMMENT-RISK-DOC", relPath, lineOf(method),
                        method.getNameAsString() + "()", MSG_RISK_DOC));
            } else if (!allowed && hasDoc) {
                violations.add(formatViolation("COMMENT-UNEXPECTED-DOC", relPath, lineOf(method),
                        method.getNameAsString() + "()", MSG_UNEXPECTED_DOC));
            }
        }

        forbidJavadocOnNonMethodMembers(relPath, cu, violations);

        for (Comment comment : cu.getAllComments()) {
            if (comment instanceof JavadocComment && comment.getCommentedNode().isEmpty()) {
                violations.add(formatViolation("COMMENT-UNEXPECTED-DOC", relPath, lineOf(comment),
                        "孤立 Javadoc", MSG_UNEXPECTED_DOC));
            }
        }
    }

    private void forbidJavadocOnNonMethodMembers(String relPath, CompilationUnit cu, List<String> violations) {
        List<NodeWithJavadoc<?>> forbiddenNodes = new ArrayList<>();
        forbiddenNodes.addAll(cu.findAll(FieldDeclaration.class));
        forbiddenNodes.addAll(cu.findAll(ConstructorDeclaration.class));
        forbiddenNodes.addAll(cu.findAll(EnumConstantDeclaration.class));
        forbiddenNodes.addAll(cu.findAll(AnnotationMemberDeclaration.class));
        for (CompactConstructorDeclaration ccd : cu.findAll(CompactConstructorDeclaration.class)) {
            if (ccd.getComment().filter(JavadocComment.class::isInstance).isPresent()) {
                violations.add(formatViolation("COMMENT-UNEXPECTED-DOC", relPath, lineOf(ccd),
                        ccd.getNameAsString(), MSG_UNEXPECTED_DOC));
            }
        }
        for (InitializerDeclaration init : cu.findAll(InitializerDeclaration.class)) {
            if (init.getComment().filter(JavadocComment.class::isInstance).isPresent()) {
                violations.add(formatViolation("COMMENT-UNEXPECTED-DOC", relPath, lineOf(init),
                        "static/instance initializer", MSG_UNEXPECTED_DOC));
            }
        }
        for (NodeWithJavadoc<?> node : forbiddenNodes) {
            if (node.getJavadocComment().isPresent()) {
                violations.add(formatViolation("COMMENT-UNEXPECTED-DOC", relPath, lineOf((Node) node),
                        node.getClass().getSimpleName(), MSG_UNEXPECTED_DOC));
            }
        }
    }

    private boolean isWhitelistedType(String pkg, TypeDeclaration<?> type) {
        if (!type.isTopLevelType() || DATA_CARRIER_PACKAGES.contains(pkg)) {
            return false;
        }
        String name = type.getNameAsString();
        if ("com.yuzhua.task".equals(pkg)) {
            return "SchedulerLease".equals(name) || usesType(type, "SchedulerLease");
        }
        if ("com.yuzhua.security".equals(pkg) && type.isPublic()) {
            return true;
        }
        if ("com.yuzhua.consult.ws".equals(pkg) && isSealedType(type)) {
            return true;
        }
        if ("com.yuzhua.websocket".equals(pkg) && type.isPublic()) {
            return true;
        }
        if ("com.yuzhua.common".equals(pkg)) {
            return "Result".equals(name) || "ApiResult".equals(name)
                    || "BatchLookup".equals(name) || "TraceIdFilter".equals(name);
        }
        return "com.yuzhua.config".equals(pkg) && "SlowSqlInterceptor".equals(name);
    }

    private boolean isWhitelistedMethod(String pkg, MethodDeclaration method) {
        if (DATA_CARRIER_PACKAGES.contains(pkg)) {
            return false;
        }
        if (hasPublicEndpointAllowMutation(method)) {
            return true;
        }
        if (isControllerMappingMethod(method)) {
            return false;
        }
        if (hasAnnotation(method, "Transactional") || hasAnnotation(method, "Scheduled")) {
            return true;
        }
        String name = method.getNameAsString();
        if ("com.yuzhua.mapper".equals(pkg)) {
            return name.endsWith("ForUpdate")
                    || "tryOccupy".equals(name)
                    || "selectByUserAndIdemKey".equals(name);
        }
        boolean topLevelOwner = method.getParentNode()
                .filter(TypeDeclaration.class::isInstance)
                .map(n -> ((TypeDeclaration<?>) n).isTopLevelType())
                .orElse(false);
        if ("com.yuzhua.security".equals(pkg) && topLevelOwner) {
            if (method.isPublic() && !hasAnnotation(method, "Override") && !isSimpleGetter(method)) {
                return true;
            }
        }
        if ("com.yuzhua.websocket".equals(pkg) && topLevelOwner) {
            boolean wsPublic = method.isPublic() && !isSimpleGetter(method);
            boolean wsHandleText = hasAnnotation(method, "Override")
                    && method.isProtected() && "handleTextMessage".equals(name);
            if (wsPublic || wsHandleText) {
                return true;
            }
        }
        return directPublicCheckedThrows(method, topLevelOwner);
    }

    private static boolean directPublicCheckedThrows(MethodDeclaration method, boolean topLevelOwner) {
        return topLevelOwner && method.isPublic() && !method.getThrownExceptions().isEmpty();
    }

    private void checkCommentsCommon(String relPath, String source, CompilationUnit cu,
                                     List<String> bannedTerms, boolean enforceInlinePrefix,
                                     List<String> violations) {
        String[] lines = source.split("\\r?\\n", -1);
        List<LineComment> lineComments = new ArrayList<>();

        for (Comment comment : cu.getAllComments()) {
            int startLine = lineOf(comment);
            int endLine = endLineOf(comment);
            String raw = comment.getContent();

            checkCommentSemantics(relPath, startLine, raw, bannedTerms, violations);

            if (comment instanceof BlockComment) {
                if (enforceInlinePrefix) {
                    violations.add(formatViolation("COMMENT-INLINE-PREFIX", relPath, startLine,
                            "/* ... */", MSG_INLINE_PREFIX));
                }
            } else if (comment instanceof JavadocComment) {
                if (enforceInlinePrefix && (endLine - startLine + 1) > MAX_DOC_LINES) {
                    violations.add(formatViolation("COMMENT-DENSITY", relPath, startLine,
                            "Javadoc(" + (endLine - startLine + 1) + "行)", MSG_DENSITY));
                }
            } else if (comment instanceof LineComment lc) {
                if (enforceInlinePrefix && isTrailingComment(lines, lc)) {
                    violations.add(formatViolation("COMMENT-INLINE-PREFIX", relPath, startLine,
                            "行尾 //", MSG_INLINE_PREFIX));
                } else {
                    lineComments.add(lc);
                }
            }
        }

        if (enforceInlinePrefix) {
            checkLineCommentBlocks(relPath, lines, lineComments, violations);
        }
    }

    private void checkLineCommentBlocks(String relPath, String[] lines, List<LineComment> lineComments,
                                        List<String> violations) {
        lineComments.sort(Comparator.comparingInt(this::lineOf));
        int idx = 0;
        while (idx < lineComments.size()) {
            int blockStartIdx = idx;
            while (idx + 1 < lineComments.size()
                    && lineOf(lineComments.get(idx + 1)) == lineOf(lineComments.get(idx)) + 1) {
                idx++;
            }
            List<LineComment> block = lineComments.subList(blockStartIdx, idx + 1);
            LineComment first = block.get(0);
            int startLine = lineOf(first);
            String firstContent = first.getContent();

            boolean validPrefix = REASON_PREFIX.matcher(firstContent).matches()
                    || TODO_PREFIX.matcher(firstContent).matches();
            if (!validPrefix) {
                violations.add(formatViolation("COMMENT-INLINE-PREFIX", relPath, startLine,
                        "//" + firstContent, MSG_INLINE_PREFIX));
            } else {
                String expectedIndent = indentOf(lines, first);
                boolean sameIndent = block.stream().allMatch(c -> indentOf(lines, c).equals(expectedIndent));
                if (!sameIndent) {
                    violations.add(formatViolation("COMMENT-INLINE-PREFIX", relPath, startLine,
                            "缩进不一致 // 块", MSG_INLINE_PREFIX));
                }
            }

            if (block.size() > MAX_INLINE_LINES) {
                violations.add(formatViolation("COMMENT-DENSITY", relPath, startLine,
                        "// 块(" + block.size() + "行)", MSG_DENSITY));
            }
            idx++;
        }
    }

    private void checkCommentSemantics(String relPath, int line, String content,
                                       List<String> bannedTerms, List<String> violations) {
        String lower = content.toLowerCase(Locale.ROOT);
        for (String term : bannedTerms) {
            if (!term.isBlank() && lower.contains(term.toLowerCase(Locale.ROOT))) {
                violations.add("[COMMENT-BANNED-TERM] " + relPath + ":" + line
                        + ": 注释中禁止使用已废弃术语「" + term + "」。");
            }
        }
        if (FORBIDDEN_SUPPRESSION_DIRECTIVE.matcher(content).find()
                || UNNUMBERED_TODO.matcher(content).find()) {
            violations.add(formatViolation("COMMENT-INLINE-PREFIX", relPath, line,
                    "禁止指令/未编号标记", MSG_INLINE_PREFIX));
        }
        if (CHANGE_LOG_PATTERN.matcher(content).find()) {
            violations.add(formatViolation("COMMENT-CHANGE-LOG", relPath, line,
                    "变更叙述", MSG_CHANGE_LOG));
        }
        checkDeadCodeInComment(relPath, line, content, violations);
    }

    private void checkDeadCodeInComment(String relPath, int startLine, String rawContent, List<String> violations) {
        String[] rawLines = rawContent.split("\\r?\\n", -1);
        for (int i = 0; i < rawLines.length; i++) {
            String stripped = rawLines[i]
                    .replaceFirst("^\\s*\\*\\s?", "")
                    .trim();
            if (stripped.startsWith("@")) {
                continue;
            }
            stripped = stripped
                    .replaceFirst("^原因:\\s*", "")
                    .replaceFirst("^TODO\\(#\\d+\\):\\s*", "");
            stripped = BACKTICK_OR_CODE_SPAN.matcher(stripped).replaceAll("").trim();
            if (!stripped.isEmpty() && DEAD_CODE_LINE.matcher(stripped).find()) {
                violations.add(formatViolation("COMMENT-DEAD-CODE", relPath, startLine + i,
                        stripped, MSG_DEAD_CODE));
                return;
            }
        }
    }

    private void checkDensity(String relPath, String source, CompilationUnit cu, List<String> violations) {
        String[] lines = source.split("\\r?\\n", -1);
        boolean[] commentOnlyLine = new boolean[lines.length + 1];
        for (Comment comment : cu.getAllComments()) {
            int s = lineOf(comment);
            int e = endLineOf(comment);
            if (comment instanceof LineComment && isTrailingComment(lines, comment)) {
                continue;
            }
            for (int line = s; line <= e && line <= lines.length; line++) {
                commentOnlyLine[line] = true;
            }
        }

        int codeLines = 0;
        int commLines = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) {
                continue;
            }
            if (commentOnlyLine[i + 1]) {
                commLines++;
            } else {
                codeLines++;
            }
        }
        if (codeLines >= MIN_CODE_LINES_FOR_DENSITY) {
            double ratio = (double) commLines / (codeLines + commLines);
            if (ratio > MAX_COMMENT_DENSITY) {
                violations.add(formatViolation("COMMENT-DENSITY", relPath, 1,
                        String.format(Locale.ROOT, "密度 %.1f%% (%d/%d)", ratio * 100, commLines, codeLines + commLines),
                        MSG_DENSITY));
            }
        }
    }

    private int checkSuppressions(String relPath, String source, CompilationUnit cu,
                                boolean isMain, List<String> violations) {
        String[] lines = source.split("\\r?\\n", -1);
        int count = 0;
        for (AnnotationExpr ann : cu.findAll(AnnotationExpr.class)) {
            if (!"SuppressWarnings".equals(ann.getNameAsString())) {
                continue;
            }
            count++;
            int line = lineOf(ann);
            List<String> values = extractSuppressionValues(ann);
            boolean hasAll = values.stream().anyMatch(v -> "all".equalsIgnoreCase(v));
            boolean hasUnchecked = values.stream().anyMatch(v -> "unchecked".equalsIgnoreCase(v));
            if (hasAll || (isMain && hasUnchecked)) {
                violations.add(formatViolation("COMMENT-SUPPRESSION-RATCHET", relPath, line,
                        "@SuppressWarnings(" + String.join(",", values) + ")", MSG_SUPPRESSION_RATCHET));
            }
            if (isMain && !hasPrecedingReasonWithIssue(lines, line)) {
                violations.add(formatViolation("COMMENT-SUPPRESSION-RATCHET", relPath, line,
                        "@SuppressWarnings 缺少 // 原因: ... (#<n>)", MSG_SUPPRESSION_RATCHET));
            }
        }
        return count;
    }

    private boolean hasPrecedingReasonWithIssue(String[] lines, int annotationLine) {
        for (int i = annotationLine - 2; i >= 0 && i >= annotationLine - 4; i--) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            return t.startsWith("// 原因:") && ISSUE_REF.matcher(t).find();
        }
        return false;
    }

    private List<String> extractSuppressionValues(AnnotationExpr ann) {
        List<String> out = new ArrayList<>();
        if (ann instanceof SingleMemberAnnotationExpr sm) {
            collectStringLiterals(sm.getMemberValue(), out);
        } else if (ann instanceof NormalAnnotationExpr nm) {
            for (MemberValuePair pair : nm.getPairs()) {
                if ("value".equals(pair.getNameAsString())) {
                    collectStringLiterals(pair.getValue(), out);
                }
            }
        }
        return out;
    }

    private void collectStringLiterals(Expression expr, List<String> out) {
        if (expr instanceof StringLiteralExpr s) {
            out.add(s.getValue());
        } else if (expr instanceof ArrayInitializerExpr arr) {
            for (Expression e : arr.getValues()) {
                if (e instanceof StringLiteralExpr s) {
                    out.add(s.getValue());
                }
            }
        }
    }

    private static boolean hasWhitelistedAssertion(MethodDeclaration method) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            String scope = call.getScope().map(Expression::toString).orElse("");
            if (name.startsWith("assert")
                    || name.startsWith("expect")
                    || "verify".equals(name)
                    || "andExpect".equals(name)
                    || "Assertions".equals(scope)
                    || ("check".equals(name) && scope.toLowerCase(Locale.ROOT).contains("rule"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTestMethod(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .anyMatch(TEST_ANNOTATIONS::contains);
    }

    private static boolean isSealedType(TypeDeclaration<?> type) {
        return type.getModifiers().stream()
                .anyMatch(mod -> mod.getKeyword() == Modifier.Keyword.SEALED);
    }

    private static boolean usesType(TypeDeclaration<?> type, String simpleName) {
        return type.getFields().stream()
                .anyMatch(f -> f.getElementType().asString().contains(simpleName));
    }

    private static boolean isSimpleGetter(MethodDeclaration method) {
        if (method.isStatic() || !method.getParameters().isEmpty()) {
            return false;
        }
        String name = method.getNameAsString();
        if (!name.startsWith("get") && !name.startsWith("is")) {
            return false;
        }
        Optional<com.github.javaparser.ast.stmt.BlockStmt> body = method.getBody();
        if (body.isEmpty() || body.get().getStatements().size() != 1) {
            return false;
        }
        Statement stmt = body.get().getStatements().get(0);
        if (!(stmt instanceof ReturnStmt ret) || ret.getExpression().isEmpty()) {
            return false;
        }
        Expression expr = ret.getExpression().get();
        return expr.isNameExpr() || expr.isFieldAccessExpr();
    }

    private static boolean isControllerMappingMethod(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .anyMatch(CONTROLLER_MAPPING_ANNOTATIONS::contains);
    }

    private static boolean hasPublicEndpointAllowMutation(MethodDeclaration method) {
        Optional<AnnotationExpr> ann = method.getAnnotationByName("PublicEndpoint");
        if (ann.isEmpty() || !(ann.get() instanceof NormalAnnotationExpr nm)) {
            return false;
        }
        return nm.getPairs().stream()
                .anyMatch(p -> "allowMutation".equals(p.getNameAsString())
                        && p.getValue() instanceof BooleanLiteralExpr b && b.getValue());
    }

    private static boolean hasAnnotation(NodeWithAnnotations<?> node, String simpleName) {
        return node.getAnnotationByName(simpleName).isPresent();
    }

    private static boolean isTrailingComment(String[] lines, Comment comment) {
        int line = comment.getBegin().map(p -> p.line).orElse(1);
        int col = comment.getBegin().map(p -> p.column).orElse(1);
        if (line < 1 || line > lines.length) {
            return false;
        }
        String prefix = lines[line - 1].substring(0, Math.min(col - 1, lines[line - 1].length()));
        return !prefix.trim().isEmpty();
    }

    private static String indentOf(String[] lines, Comment comment) {
        int line = comment.getBegin().map(p -> p.line).orElse(1);
        int col = comment.getBegin().map(p -> p.column).orElse(1);
        String raw = lines[line - 1];
        return raw.substring(0, Math.min(col - 1, raw.length()));
    }

    private int lineOf(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    private int endLineOf(Node node) {
        return node.getEnd().map(p -> p.line).orElse(0);
    }

    private static String formatViolation(String ruleId, String relPath, int line, String target, String template) {
        return "[" + ruleId + "] " + relPath + ":" + line + " (" + target + "): " + template;
    }

    private static boolean isExcludedGuardrailsPath(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/.guardrails/") || normalized.startsWith(".guardrails/");
    }

    private static List<Path> listJavaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static Path resolveBackendDir() {
        Path direct = Path.of("src/main/java");
        if (Files.isDirectory(direct)) {
            return Path.of("").toAbsolutePath().normalize();
        }
        Path nested = Path.of("backend/src/main/java");
        if (Files.isDirectory(nested)) {
            return Path.of("backend").toAbsolutePath().normalize();
        }
        throw new IllegalStateException("无法定位 backend/src/main/java");
    }

    private static Budget loadSuppressionBudget(Path backendDir) throws IOException {
        Path path = backendDir.resolve("../.guardrails/suppression-budget.json").normalize();
        if (!Files.isRegularFile(path)) {
            return new Budget(FALLBACK_MAIN_SUPPRESSION_BUDGET, FALLBACK_TEST_SUPPRESSION_BUDGET);
        }
        JsonNode root = JSON.readTree(path.toFile());
        int mainBudget = root.path("backend_main").asInt(FALLBACK_MAIN_SUPPRESSION_BUDGET);
        int testBudget = root.path("backend_test").asInt(FALLBACK_TEST_SUPPRESSION_BUDGET);
        return new Budget(mainBudget, testBudget);
    }

    private static List<String> loadBannedTerms(Path backendDir) throws IOException {
        Path path = backendDir.resolve("../.guardrails/banned-terms.json").normalize();
        if (!Files.isRegularFile(path)) {
            return List.of("axios");
        }
        return JSON.readValue(path.toFile(), new TypeReference<>() {
        });
    }

    private static String readProperty(Element module, String propName) {
        NodeList props = module.getElementsByTagName("property");
        for (int i = 0; i < props.getLength(); i++) {
            Element p = (Element) props.item(i);
            if (propName.equals(p.getAttribute("name"))) {
                return p.getAttribute("value");
            }
        }
        throw new IllegalStateException("Missing Checkstyle property: " + propName);
    }

    private record Budget(int backendMain, int backendTest) {
    }

    private record AnalysisResult(List<String> violations, int suppressionCount) {
    }
}
