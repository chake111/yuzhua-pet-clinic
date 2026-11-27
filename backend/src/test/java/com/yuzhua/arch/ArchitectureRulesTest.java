package com.yuzhua.arch;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.repository.AbstractRepository;
import com.baomidou.mybatisplus.extension.repository.IRepository;
import com.baomidou.mybatisplus.spring.repository.CrudRepository;
import com.baomidou.mybatisplus.spring.service.IService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.yuzhua.task.SchedulerLease;
import io.swagger.v3.oas.annotations.Operation;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureRulesTest {

    private static final JavaClasses PROD_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.yuzhua");

    private static final JavaClasses ARCH02_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(
                    "com.yuzhua",
                    "com.baomidou.mybatisplus.spring.service",
                    "com.baomidou.mybatisplus.spring.repository",
                    "com.baomidou.mybatisplus.extension.repository");

    @Test
    void arch02_controllersAndCrossServiceCallsDoNotUseGenericCrud() {
        ArchRule rule = arch02Rule("com.yuzhua.controller..", "com.yuzhua.service..");
        rule.check(ARCH02_CLASSES);
    }

    @Test
    void arch02_negativeFixtureDetectsGenericCrudLeak() {
        JavaClasses controllerFixture = new ClassFileImporter().importClasses(
                IRepository.class,
                AbstractRepository.class,
                CrudRepository.class,
                IService.class,
                ServiceImpl.class,
                BadControllerFixture.class,
                SampleServiceFixture.class,
                SampleMapperFixture.class,
                SampleEntityFixture.class);
        assertThrows(AssertionError.class, () ->
                arch02Rule("com.yuzhua.arch..").check(controllerFixture));

        JavaClasses crossServiceFixture = new ClassFileImporter().importClasses(
                IRepository.class,
                AbstractRepository.class,
                CrudRepository.class,
                IService.class,
                ServiceImpl.class,
                CrossServiceFixture.class,
                SampleServiceFixture.class,
                SampleMapperFixture.class,
                SampleEntityFixture.class);
        assertThrows(AssertionError.class, () ->
                arch02Rule("com.yuzhua.arch..").check(crossServiceFixture));
    }

    @Test
    void arch07_topLevelPackagesAreFreeOfCycles() {
        ArchRule rule = slices().matching("com.yuzhua.(*)..").should().beFreeOfCycles();
        rule.check(PROD_CLASSES);
    }

    @Test
    void arch08_scheduledTasksAndControllersFollowPlacementAndLeaseRules() {
        ArchRule scheduledRule = classes().that().containAnyMethodsThat(annotatedWithScheduled())
                .should().resideInAPackage("com.yuzhua.task..")
                .andShould().dependOnClassesThat().areAssignableTo(SchedulerLease.class);
        ArchRule controllerRule = classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage("com.yuzhua.controller..");
        for (ArchRule rule : java.util.List.of(scheduledRule, controllerRule)) {
            rule.check(PROD_CLASSES);
        }
    }

    @Test
    void arch09_everyProductionPackageHasNullMarkedPackageInfo() throws ClassNotFoundException {
        Set<String> packages = new TreeSet<>();
        for (JavaClass clazz : PROD_CLASSES) {
            if (!"package-info".equals(clazz.getSimpleName())) {
                packages.add(clazz.getPackageName());
            }
        }
        assertTrue(!packages.isEmpty(), "expected production packages");
        for (String pkg : packages) {
            Class<?> pkgInfo = Class.forName(pkg + ".package-info");
            assertTrue(pkgInfo.isAnnotationPresent(NullMarked.class),
                    "package missing @NullMarked package-info: " + pkg);
        }
    }

    @Test
    void arch10_everyRestControllerMappingHasOperationAnnotation() {
        ArchRule rule = methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .and(annotatedAsRequestHandler())
                .should().beAnnotatedWith(Operation.class);
        rule.check(PROD_CLASSES);
    }

    private static ArchRule arch02Rule(String... callerPackages) {
        return classes().that().resideInAnyPackage(callerPackages)
                .should(notCallGenericServiceCrud());
    }

    private static ArchCondition<JavaClass> notCallGenericServiceCrud() {
        return new ArchCondition<>("not invoke IService or ServiceImpl generic CRUD methods") {
            @Override
            public void check(JavaClass caller, ConditionEvents events) {
                for (JavaMethodCall call : caller.getMethodCallsFromSelf()) {
                    JavaClass receiver = call.getTarget().getOwner();
                    if (!receiver.isAssignableTo(IService.class)) {
                        continue;
                    }
                    // 原因: Service 自身（含内部类）调用父类模板方法属于正常用法，仅拦截跨类直调
                    if (!caller.isAnnotatedWith(RestController.class)
                            && !caller.getPackageName().startsWith("com.yuzhua.controller")
                            && isSameOrEnclosedBy(caller, receiver)) {
                        continue;
                    }
                    boolean declaredOnMyBatis = call.getTarget().resolveMember()
                            .map(member -> isMyBatisServiceBase(member.getOwner()))
                            .orElseGet(() -> isMyBatisServiceBase(receiver));
                    if (declaredOnMyBatis) {
                        events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                    }
                }
            }
        };
    }

    private static boolean isMyBatisServiceBase(JavaClass owner) {
        return owner.getPackageName().startsWith("com.baomidou.mybatisplus.");
    }

    private static boolean isSameOrEnclosedBy(JavaClass caller, JavaClass receiver) {
        if (caller.equals(receiver)) {
            return true;
        }
        return caller.getEnclosingClass()
                .map(enclosing -> isSameOrEnclosedBy(enclosing, receiver))
                .orElse(false);
    }

    private static DescribedPredicate<JavaMethod> annotatedWithScheduled() {
        return new DescribedPredicate<>("annotated with @Scheduled") {
            @Override
            public boolean test(JavaMethod method) {
                return method.isAnnotatedWith(Scheduled.class);
            }
        };
    }

    private static DescribedPredicate<JavaMethod> annotatedAsRequestHandler() {
        return new DescribedPredicate<>("annotated as Spring request handler") {
            @Override
            public boolean test(JavaMethod method) {
                return method.isAnnotatedWith(RequestMapping.class)
                        || method.isMetaAnnotatedWith(RequestMapping.class);
            }
        };
    }

    static class SampleEntityFixture {
    }

    interface SampleMapperFixture extends BaseMapper<SampleEntityFixture> {
    }

    static class SampleServiceFixture extends ServiceImpl<SampleMapperFixture, SampleEntityFixture> {
    }

    @RestController
    static class BadControllerFixture {
        private final SampleServiceFixture service = new SampleServiceFixture();

        void trigger() {
            service.removeById(1L);
        }
    }

    static class CrossServiceFixture {
        private final SampleServiceFixture service = new SampleServiceFixture();

        void trigger() {
            service.getById(1L);
        }
    }
}
