package com.example.plugin.util;

import com.intellij.openapi.application.ReadAction;
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
 *
 * <p>所有 PSI 访问均包裹在 {@link ReadAction} 中，可安全地在后台线程调用。
 */
public class ReferenceFinderUtil {

    /**
     * 将 PsiMethod 转换为可被 IDEA 识别的签名字符串：
     * {@code com.example.pkg.ClassName#methodName(param.Type1, param.Type2)}
     *
     * <p>调用方必须持有 ReadAction 或在 EDT 上调用。
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
     * <p>可在后台线程调用；内部通过 {@link ReadAction} 保护所有 PSI 读取操作。
     *
     * @param methods   要查找引用的方法列表
     * @param indicator 进度指示器，可为 null
     * @return 每条记录为 String[3]：{源方法签名, 目标项目名, 引用方法签名}
     */
    public static List<String[]> findReferences(List<PsiMethod> methods, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();

        for (PsiMethod sourceMethod : methods) {
            // --- 读取源方法元信息（需 ReadAction）---
            String[] sourceInfo = ReadAction.compute(() -> {
                PsiClass sourceClass = sourceMethod.getContainingClass();
                if (sourceClass == null) return null;
                String qualifiedClassName = sourceClass.getQualifiedName();
                if (qualifiedClassName == null) return null;
                return new String[]{qualifiedClassName, getMethodSignature(sourceMethod)};
            });
            if (sourceInfo == null) continue;

            String qualifiedClassName = sourceInfo[0];
            String sourceSignature    = sourceInfo[1];

            if (indicator != null) {
                indicator.setText("查找引用: " + sourceSignature);
                if (indicator.isCanceled()) break;
            }

            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (indicator != null && indicator.isCanceled()) break;

                // --- 在目标项目中定位类和方法（需 ReadAction）---
                PsiMethod targetMethod = ReadAction.compute(() -> {
                    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                    PsiClass targetClass = facade.findClass(
                            qualifiedClassName, GlobalSearchScope.allScope(project));
                    if (targetClass == null) return null;
                    return findMatchingMethod(targetClass, sourceMethod);
                });
                if (targetMethod == null) continue;

                String projectName = project.getName();

                // ReferencesSearch 内部自行管理 ReadAction，可直接在后台线程使用
                ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                        .forEach(reference -> {
                            if (indicator != null && indicator.isCanceled()) return false;

                            // 读取引用元信息（需 ReadAction）
                            String callerSignature = ReadAction.compute(() -> {
                                PsiElement refElement = reference.getElement();
                                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(
                                        refElement, PsiMethod.class);
                                if (callerMethod != null) {
                                    return getMethodSignature(callerMethod);
                                }
                                PsiFile file = refElement.getContainingFile();
                                return file != null ? file.getName() : "(unknown)";
                            });

                            results.add(new String[]{sourceSignature, projectName, callerSignature});
                            return true;
                        });
            }
        }

        return results;
    }

    /**
     * 在目标类中查找与 sourceMethod 签名一致的方法（方法名 + 参数类型全限定名均匹配）。
     *
     * <p>调用方必须持有 ReadAction。
     */
    private static PsiMethod findMatchingMethod(PsiClass targetClass, PsiMethod sourceMethod) {
        PsiParameter[] sourceParams = sourceMethod.getParameterList().getParameters();

        for (PsiMethod candidate : targetClass.getMethods()) {
            if (!candidate.getName().equals(sourceMethod.getName())) continue;

            PsiParameter[] candidateParams = candidate.getParameterList().getParameters();
            if (candidateParams.length != sourceParams.length) continue;

            boolean match = true;
            for (int i = 0; i < sourceParams.length; i++) {
                String sourceType    = sourceParams[i].getType().getCanonicalText();
                String candidateType = candidateParams[i].getType().getCanonicalText();
                if (!sourceType.equals(candidateType)) {
                    match = false;
                    break;
                }
            }
            if (match) return candidate;
        }
        return null;
    }
}
