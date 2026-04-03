package com.example.plugin.util;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 核心工具：在所有已打开的 IntelliJ 项目中查找方法引用。
 *
 * <p>CSV 列说明：
 * <ol>
 *   <li>源方法 — 被查找的方法签名，格式 {@code com.example.Foo#bar(java.lang.String)}</li>
 *   <li>目标项目 — 发现引用所在的 IntelliJ 项目名称</li>
 *   <li>引用方法 — 调用方的方法签名（若调用在初始化块/字段中则为文件名）</li>
 * </ol>
 */
public class ReferenceFinderUtil {

    /**
     * 将 PsiMethod 转换为可被 IDEA 识别的签名字符串：
     * {@code com.example.pkg.ClassName#methodName(param.Type1, param.Type2)}
     */
    public static String getMethodSignature(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        String className;
        if (containingClass != null) {
            className = containingClass.getQualifiedName();
            if (className == null) {
                className = containingClass.getName();
            }
        } else {
            className = "";
        }

        StringBuilder params = new StringBuilder();
        for (PsiParameter param : method.getParameterList().getParameters()) {
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append(param.getType().getCanonicalText());
        }

        return className + "#" + method.getName() + "(" + params + ")";
    }

    /**
     * 在所有已打开项目中查找给定方法列表的引用。
     *
     * @param methods   要查找引用的方法列表
     * @param indicator 进度指示器，可为 null
     * @return 每条记录为 String[3]：{源方法签名, 目标项目名, 引用方法签名}
     */
    public static List<String[]> findReferences(List<PsiMethod> methods, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();

        for (PsiMethod sourceMethod : methods) {
            PsiClass sourceClass = sourceMethod.getContainingClass();
            if (sourceClass == null) continue;

            String qualifiedClassName = sourceClass.getQualifiedName();
            if (qualifiedClassName == null) continue;

            String sourceSignature = getMethodSignature(sourceMethod);

            if (indicator != null) {
                indicator.setText("查找引用: " + sourceSignature);
                if (indicator.isCanceled()) break;
            }

            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (indicator != null && indicator.isCanceled()) break;

                // 在目标项目中查找同一个类
                JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                PsiClass targetClass = facade.findClass(qualifiedClassName, GlobalSearchScope.allScope(project));
                if (targetClass == null) continue;

                // 匹配同签名的方法
                PsiMethod targetMethod = findMatchingMethod(targetClass, sourceMethod);
                if (targetMethod == null) continue;

                String projectName = project.getName();

                // 在该项目的 project scope 内搜索引用（排除 library/JDK 中的引用）
                ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                        .forEach(reference -> {
                            if (indicator != null && indicator.isCanceled()) return false;

                            PsiElement refElement = reference.getElement();
                            // 获取引用所在的方法；若在字段/初始化块中则记录文件名
                            PsiMethod callerMethod = PsiTreeUtil.getParentOfType(refElement, PsiMethod.class);
                            String callerSignature;
                            if (callerMethod != null) {
                                callerSignature = getMethodSignature(callerMethod);
                            } else {
                                PsiFile file = refElement.getContainingFile();
                                callerSignature = file != null ? file.getName() : "(unknown)";
                            }

                            results.add(new String[]{sourceSignature, projectName, callerSignature});
                            return true;
                        });
            }
        }

        return results;
    }

    /**
     * 在目标类中查找与 sourceMethod 签名一致的方法（方法名 + 参数类型全限定名均匹配）。
     */
    private static PsiMethod findMatchingMethod(PsiClass targetClass, PsiMethod sourceMethod) {
        PsiParameter[] sourceParams = sourceMethod.getParameterList().getParameters();

        for (PsiMethod candidate : targetClass.getMethods()) {
            if (!candidate.getName().equals(sourceMethod.getName())) continue;

            PsiParameter[] candidateParams = candidate.getParameterList().getParameters();
            if (candidateParams.length != sourceParams.length) continue;

            boolean match = true;
            for (int i = 0; i < sourceParams.length; i++) {
                String sourceType = sourceParams[i].getType().getCanonicalText();
                String targetType = candidateParams[i].getType().getCanonicalText();
                if (!sourceType.equals(targetType)) {
                    match = false;
                    break;
                }
            }
            if (match) return candidate;
        }
        return null;
    }
}
