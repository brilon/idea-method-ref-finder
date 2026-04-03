package com.example.plugin.util;

import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 {@link ReferenceFinderUtil} 的方法签名生成和引用查找逻辑。
 *
 * <p>使用 {@link LightJavaCodeInsightFixtureTestCase5}（轻量级内存项目），
 * 无需安装完整 IDE 即可运行。
 */
class ReferenceFinderUtilTest extends LightJavaCodeInsightFixtureTestCase5 {

    // -----------------------------------------------------------------------
    // getMethodSignature
    // -----------------------------------------------------------------------

    @Test
    void testSignatureNoParams() {
        PsiClass cls = myFixture.addClass(
                "package com.example;\n" +
                "public class Foo {\n" +
                "    public void bar() {}\n" +
                "}");
        PsiMethod method = cls.getMethods()[0];
        assertEquals("com.example.Foo#bar()", ReferenceFinderUtil.getMethodSignature(method));
    }

    @Test
    void testSignatureWithPrimitiveParams() {
        PsiClass cls = myFixture.addClass(
                "package com.example;\n" +
                "public class Calc {\n" +
                "    public int add(int a, int b) { return a + b; }\n" +
                "}");
        PsiMethod method = cls.getMethods()[0];
        assertEquals("com.example.Calc#add(int, int)", ReferenceFinderUtil.getMethodSignature(method));
    }

    @Test
    void testSignatureWithFullyQualifiedParams() {
        PsiClass cls = myFixture.addClass(
                "package com.example;\n" +
                "public class Notifier {\n" +
                "    public void send(java.lang.String to, java.lang.String body) {}\n" +
                "}");
        PsiMethod method = cls.getMethods()[0];
        assertEquals(
                "com.example.Notifier#send(java.lang.String, java.lang.String)",
                ReferenceFinderUtil.getMethodSignature(method));
    }

    @Test
    void testSignatureConstructor() {
        PsiClass cls = myFixture.addClass(
                "package com.example;\n" +
                "public class Widget {\n" +
                "    public Widget(int id) {}\n" +
                "}");
        // Constructors are also PsiMethod in IntelliJ PSI
        PsiMethod constructor = Arrays.stream(cls.getMethods())
                .filter(PsiMethod::isConstructor)
                .findFirst()
                .orElseThrow();
        assertEquals("com.example.Widget#Widget(int)", ReferenceFinderUtil.getMethodSignature(constructor));
    }

    // -----------------------------------------------------------------------
    // findReferences — 在同一（轻量级）项目内查找引用
    // -----------------------------------------------------------------------

    @Test
    void testFindReferences_findsCallerInSameProject() {
        // 被查找的目标方法
        myFixture.addClass(
                "package com.example;\n" +
                "public class Greeter {\n" +
                "    public String greet(String name) { return \"Hello \" + name; }\n" +
                "}");

        // 调用方
        myFixture.addClass(
                "package com.example;\n" +
                "public class App {\n" +
                "    private Greeter g = new Greeter();\n" +
                "    public void run() { g.greet(\"world\"); }\n" +
                "}");

        PsiClass greeterClass = myFixture.findClass("com.example.Greeter");
        assertNotNull(greeterClass);
        PsiMethod greetMethod = greeterClass.getMethods()[0];

        List<String[]> refs = ReferenceFinderUtil.findReferences(
                List.of(greetMethod), null);

        assertFalse(refs.isEmpty(), "应至少找到一条引用");

        String[] first = refs.get(0);
        assertEquals("com.example.Greeter#greet(java.lang.String)", first[0], "源方法签名");
        assertEquals("com.example.App#run()", first[2], "调用方签名");
    }

    @Test
    void testFindReferences_noReferences() {
        myFixture.addClass(
                "package com.example;\n" +
                "public class Unused {\n" +
                "    public void doNothing() {}\n" +
                "}");

        PsiClass cls = myFixture.findClass("com.example.Unused");
        assertNotNull(cls);
        PsiMethod method = cls.getMethods()[0];

        List<String[]> refs = ReferenceFinderUtil.findReferences(List.of(method), null);
        assertTrue(refs.isEmpty(), "未被引用的方法应返回空列表");
    }

    @Test
    void testFindReferences_multipleCallers() {
        myFixture.addClass(
                "package com.example;\n" +
                "public class Logger {\n" +
                "    public void log(String msg) {}\n" +
                "}");

        myFixture.addClass(
                "package com.example;\n" +
                "public class ServiceA {\n" +
                "    Logger logger = new Logger();\n" +
                "    public void a() { logger.log(\"a\"); }\n" +
                "}");

        myFixture.addClass(
                "package com.example;\n" +
                "public class ServiceB {\n" +
                "    Logger logger = new Logger();\n" +
                "    public void b() { logger.log(\"b\"); }\n" +
                "}");

        PsiClass loggerClass = myFixture.findClass("com.example.Logger");
        assertNotNull(loggerClass);
        PsiMethod logMethod = loggerClass.getMethods()[0];

        List<String[]> refs = ReferenceFinderUtil.findReferences(List.of(logMethod), null);
        assertEquals(2, refs.size(), "应找到来自 ServiceA 和 ServiceB 的两条引用");
    }

    @Test
    void testFindReferences_classAllMethods() {
        myFixture.addClass(
                "package com.example;\n" +
                "public class MathUtils {\n" +
                "    public int add(int a, int b) { return a + b; }\n" +
                "    public int sub(int a, int b) { return a - b; }\n" +
                "}");

        myFixture.addClass(
                "package com.example;\n" +
                "public class Calculator {\n" +
                "    MathUtils m = new MathUtils();\n" +
                "    public void calc() {\n" +
                "        m.add(1, 2);\n" +
                "        m.sub(3, 1);\n" +
                "    }\n" +
                "}");

        PsiClass mathClass = myFixture.findClass("com.example.MathUtils");
        assertNotNull(mathClass);
        List<PsiMethod> methods = Arrays.asList(mathClass.getMethods());

        List<String[]> refs = ReferenceFinderUtil.findReferences(methods, null);
        assertEquals(2, refs.size(), "两个方法各被调用一次");
    }
}
