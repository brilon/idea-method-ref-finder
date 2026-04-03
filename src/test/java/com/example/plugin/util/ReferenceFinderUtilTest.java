package com.example.plugin.util;

import com.intellij.psi.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 测试 {@link ReferenceFinderUtil#getMethodSignature(PsiMethod)} 的签名拼接逻辑。
 *
 * <p>使用 Mockito mock PSI 对象，无需启动 IntelliJ Platform 环境，可在 CI 中直接运行。
 * <br>
 * 注意：{@code findReferences} 依赖 {@link com.intellij.openapi.project.ProjectManager}
 * 等平台单例，属于集成测试，需在已运行 IDE 的沙箱中（{@code ./gradlew runIde}）验证。
 */
@ExtendWith(MockitoExtension.class)
class ReferenceFinderUtilTest {

    @Mock
    private PsiMethod method;

    @Mock
    private PsiClass containingClass;

    @Mock
    private PsiParameterList parameterList;

    // -----------------------------------------------------------------------
    // getMethodSignature — 测试各种参数/类名场景
    // -----------------------------------------------------------------------

    @Test
    void testSignatureNoParams() {
        when(method.getContainingClass()).thenReturn(containingClass);
        when(containingClass.getQualifiedName()).thenReturn("com.example.Foo");
        when(method.getName()).thenReturn("bar");
        when(method.getParameterList()).thenReturn(parameterList);
        when(parameterList.getParameters()).thenReturn(PsiParameter.EMPTY_ARRAY);

        assertEquals("com.example.Foo#bar()", ReferenceFinderUtil.getMethodSignature(method));
    }

    @Test
    void testSignatureWithOneStringParam() {
        PsiParameter param = mockParam("java.lang.String");

        when(method.getContainingClass()).thenReturn(containingClass);
        when(containingClass.getQualifiedName()).thenReturn("com.example.notify.EmailNotifier");
        when(method.getName()).thenReturn("send");
        when(method.getParameterList()).thenReturn(parameterList);
        when(parameterList.getParameters()).thenReturn(new PsiParameter[]{param});

        assertEquals(
                "com.example.notify.EmailNotifier#send(java.lang.String)",
                ReferenceFinderUtil.getMethodSignature(method));
    }

    @Test
    void testSignatureWithTwoStringParams() {
        PsiParameter p1 = mockParam("java.lang.String");
        PsiParameter p2 = mockParam("java.lang.String");

        when(method.getContainingClass()).thenReturn(containingClass);
        when(containingClass.getQualifiedName()).thenReturn("com.example.notify.EmailNotifier");
        when(method.getName()).thenReturn("send");
        when(method.getParameterList()).thenReturn(parameterList);
        when(parameterList.getParameters()).thenReturn(new PsiParameter[]{p1, p2});

        assertEquals(
                "com.example.notify.EmailNotifier#send(java.lang.String, java.lang.String)",
                ReferenceFinderUtil.getMethodSignature(method));
    }

    @Test
    void testSignatureWithPrimitiveParams() {
        PsiParameter p1 = mockParam("int");
        PsiParameter p2 = mockParam("int");

        when(method.getContainingClass()).thenReturn(containingClass);
        when(containingClass.getQualifiedName()).thenReturn("com.example.Calc");
        when(method.getName()).thenReturn("add");
        when(method.getParameterList()).thenReturn(parameterList);
        when(parameterList.getParameters()).thenReturn(new PsiParameter[]{p1, p2});

        assertEquals("com.example.Calc#add(int, int)", ReferenceFinderUtil.getMethodSignature(method));
    }

    @Test
    void testSignatureWithMixedParams() {
        PsiParameter p1 = mockParam("java.lang.String");
        PsiParameter p2 = mockParam("int");
        PsiParameter p3 = mockParam("boolean");

        when(method.getContainingClass()).thenReturn(containingClass);
        when(containingClass.getQualifiedName()).thenReturn("com.example.Util");
        when(method.getName()).thenReturn("process");
        when(method.getParameterList()).thenReturn(parameterList);
        when(parameterList.getParameters()).thenReturn(new PsiParameter[]{p1, p2, p3});

        assertEquals(
                "com.example.Util#process(java.lang.String, int, boolean)",
                ReferenceFinderUtil.getMethodSignature(method));
    }

    @Test
    void testSignatureConstructor() {
        // 必须先创建 mock，不能嵌套在 thenReturn() 内（会触发 UnfinishedStubbingException）
        PsiParameter intParam = mockParam("int");

        when(method.getContainingClass()).thenReturn(containingClass);
        when(containingClass.getQualifiedName()).thenReturn("com.example.Widget");
        when(method.getName()).thenReturn("Widget"); // 构造方法名与类名相同
        when(method.getParameterList()).thenReturn(parameterList);
        when(parameterList.getParameters()).thenReturn(new PsiParameter[]{intParam});

        assertEquals("com.example.Widget#Widget(int)", ReferenceFinderUtil.getMethodSignature(method));
    }

    @Test
    void testSignatureNullContainingClass() {
        // 极端情况：方法没有包含类（如顶层函数？）
        when(method.getContainingClass()).thenReturn(null);
        when(method.getName()).thenReturn("orphan");
        when(method.getParameterList()).thenReturn(parameterList);
        when(parameterList.getParameters()).thenReturn(PsiParameter.EMPTY_ARRAY);

        assertEquals("#orphan()", ReferenceFinderUtil.getMethodSignature(method));
    }

    @Test
    void testSignatureClassHasNoQualifiedName() {
        // 极端情况：匿名/局部类的 qualifiedName 为 null，fallback 到 simpleName
        when(method.getContainingClass()).thenReturn(containingClass);
        when(containingClass.getQualifiedName()).thenReturn(null);
        when(containingClass.getName()).thenReturn("AnonymousClass");
        when(method.getName()).thenReturn("run");
        when(method.getParameterList()).thenReturn(parameterList);
        when(parameterList.getParameters()).thenReturn(PsiParameter.EMPTY_ARRAY);

        assertEquals("AnonymousClass#run()", ReferenceFinderUtil.getMethodSignature(method));
    }

    // -----------------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------------

    /**
     * 创建一个 mock PsiParameter，其类型的 canonicalText 为指定字符串。
     */
    private PsiParameter mockParam(String canonicalTypeName) {
        PsiParameter param = org.mockito.Mockito.mock(PsiParameter.class);
        PsiType type = org.mockito.Mockito.mock(PsiType.class);
        when(param.getType()).thenReturn(type);
        when(type.getCanonicalText()).thenReturn(canonicalTypeName);
        return param;
    }
}
