package com.example.plugin.util;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 核心工具：在所有已打开的 IntelliJ 项目中查找方法引用。
 *
 * <p>CSV 列说明：
 * <ol>
 *   <li>源方法 — 被查找的方法签名，格式 {@code com.example.Foo#bar(java.lang.String)}</li>
 *   <li>目标项目 — 发现引用所在的 IntelliJ 项目名称</li>
 *   <li>目标模块 — 发现引用所在的 Maven/Gradle 模块名称</li>
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
     * @return 每条记录为 String[4]：{源方法签名, 目标项目名, 目标模块名, 引用方法签名}
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

                            // 读取引用元信息：模块名 + 调用方签名（均需 ReadAction）
                            String[] refInfo = ReadAction.compute(() -> {
                                PsiElement refElement = reference.getElement();

                                Module module = ModuleUtilCore.findModuleForPsiElement(refElement);
                                String moduleName = module != null ? module.getName() : "";

                                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(
                                        refElement, PsiMethod.class);
                                String callerSignature;
                                if (callerMethod != null) {
                                    callerSignature = getMethodSignature(callerMethod);
                                } else {
                                    PsiFile file = refElement.getContainingFile();
                                    callerSignature = file != null ? file.getName() : "(unknown)";
                                }
                                return new String[]{moduleName, callerSignature};
                            });

                            results.add(new String[]{
                                    sourceSignature, projectName, refInfo[0], refInfo[1]});
                            return true;
                        });
            }
        }

        return results;
    }

    /**
     * 递归收集 psiPackage 及其所有子包中属于 project 的 Java 类。
     *
     * <p>调用方必须持有 ReadAction。
     */
    public static List<PsiClass> collectPackageClasses(PsiPackage psiPackage, Project project) {
        // allScope：同时覆盖项目源码和外部库（library）中的类
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        List<PsiClass> result = new ArrayList<>(Arrays.asList(psiPackage.getClasses(scope)));
        for (PsiPackage sub : psiPackage.getSubPackages(scope)) {
            result.addAll(collectPackageClasses(sub, project));
        }
        return result;
    }

    /**
     * 在所有已打开项目中查找给定类列表被引用的位置（类级别引用，非方法级别）。
     *
     * <p>CSV 列：源类全限定名, 目标项目, 目标模块, 引用位置（调用方方法签名或文件名）
     */
    public static List<String[]> findClassReferences(List<PsiClass> classes, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();

        for (PsiClass sourceClass : classes) {
            String qualifiedName = ReadAction.compute(() -> sourceClass.getQualifiedName());
            if (qualifiedName == null) continue;

            if (indicator != null) {
                indicator.setText("查找类引用: " + qualifiedName);
                if (indicator.isCanceled()) break;
            }

            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (indicator != null && indicator.isCanceled()) break;

                PsiClass targetClass = ReadAction.compute(() ->
                        JavaPsiFacade.getInstance(project)
                                .findClass(qualifiedName, GlobalSearchScope.allScope(project)));
                if (targetClass == null) continue;

                String projectName = project.getName();

                ReferencesSearch.search(targetClass, GlobalSearchScope.projectScope(project))
                        .forEach(reference -> {
                            if (indicator != null && indicator.isCanceled()) return false;

                            String[] refInfo = ReadAction.compute(() -> {
                                PsiElement refElement = reference.getElement();
                                Module module = ModuleUtilCore.findModuleForPsiElement(refElement);
                                String moduleName = module != null ? module.getName() : "";
                                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(
                                        refElement, PsiMethod.class);
                                String location = callerMethod != null
                                        ? getMethodSignature(callerMethod)
                                        : (refElement.getContainingFile() != null
                                                ? refElement.getContainingFile().getName()
                                                : "(unknown)");
                                return new String[]{moduleName, location};
                            });

                            results.add(new String[]{qualifiedName, projectName, refInfo[0], refInfo[1]});
                            return true;
                        });
            }
        }

        return results;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 引用链分析（往上追溯 N 层，标记链路是否终止）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 在所有已打开项目中查找方法引用，并对每条结果的引用方法再往上追溯 {@code chainDepth} 层，
     * 判断该引用方法的调用链是否终止（即在 chainDepth 层内无继续引用）。
     *
     * @return 每条记录为 String[5]：{源方法签名, 目标项目, 目标模块, 引用方法, 引用链状态}
     *         引用链状态：{@code "链路终止"} 表示往上 chainDepth 层内无调用者，
     *                     {@code "链路继续"} 表示仍有调用者，
     *                     {@code "非方法体引用"} 表示引用不在方法内（如字段/初始化块）
     */
    public static List<String[]> findReferencesWithChainAnalysis(
            List<PsiMethod> methods, int chainDepth, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();
        Map<String, Boolean> chainCache = new HashMap<>();

        for (PsiMethod sourceMethod : methods) {
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
                indicator.setText("分析引用链: " + sourceSignature);
                if (indicator.isCanceled()) break;
            }

            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (indicator != null && indicator.isCanceled()) break;

                PsiMethod targetMethod = ReadAction.compute(() -> {
                    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                    PsiClass targetClass = facade.findClass(
                            qualifiedClassName, GlobalSearchScope.allScope(project));
                    if (targetClass == null) return null;
                    return findMatchingMethod(targetClass, sourceMethod);
                });
                if (targetMethod == null) continue;

                String projectName = project.getName();

                ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                        .forEach(reference -> {
                            if (indicator != null && indicator.isCanceled()) return false;

                            String[] refInfo = ReadAction.compute(() -> {
                                PsiElement refElement = reference.getElement();
                                Module module = ModuleUtilCore.findModuleForPsiElement(refElement);
                                String moduleName = module != null ? module.getName() : "";
                                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(
                                        refElement, PsiMethod.class);
                                String callerSignature;
                                if (callerMethod != null) {
                                    callerSignature = getMethodSignature(callerMethod);
                                } else {
                                    PsiFile file = refElement.getContainingFile();
                                    callerSignature = file != null ? file.getName() : "(unknown)";
                                }
                                return new String[]{moduleName, callerSignature};
                            });

                            PsiMethod callerMethod = ReadAction.compute(() ->
                                    PsiTreeUtil.getParentOfType(
                                            reference.getElement(), PsiMethod.class));

                            String chainStatus;
                            if (callerMethod == null) {
                                chainStatus = "非方法体引用";
                            } else {
                                String cacheKey = refInfo[1] + "|" + chainDepth;
                                boolean terminated = chainCache.computeIfAbsent(cacheKey,
                                        k -> isCallerChainTerminated(
                                                callerMethod, chainDepth, new HashSet<>()));
                                chainStatus = terminated ? "链路终止" : "链路继续";
                            }

                            results.add(new String[]{
                                    sourceSignature, projectName, refInfo[0], refInfo[1], chainStatus});
                            return true;
                        });
            }
        }
        return results;
    }

    /**
     * 在所有已打开项目中查找类引用，并对每条结果的引用位置往上追溯 {@code chainDepth} 层，
     * 判断该引用方法的调用链是否终止。
     *
     * @return 每条记录为 String[5]：{源类全限定名, 目标项目, 目标模块, 引用位置, 引用链状态}
     */
    public static List<String[]> findClassReferencesWithChainAnalysis(
            List<PsiClass> classes, int chainDepth, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();
        Map<String, Boolean> chainCache = new HashMap<>();

        for (PsiClass sourceClass : classes) {
            String qualifiedName = ReadAction.compute(() -> sourceClass.getQualifiedName());
            if (qualifiedName == null) continue;

            if (indicator != null) {
                indicator.setText("分析类引用链: " + qualifiedName);
                if (indicator.isCanceled()) break;
            }

            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (indicator != null && indicator.isCanceled()) break;

                PsiClass targetClass = ReadAction.compute(() ->
                        JavaPsiFacade.getInstance(project)
                                .findClass(qualifiedName, GlobalSearchScope.allScope(project)));
                if (targetClass == null) continue;

                String projectName = project.getName();

                ReferencesSearch.search(targetClass, GlobalSearchScope.projectScope(project))
                        .forEach(reference -> {
                            if (indicator != null && indicator.isCanceled()) return false;

                            String[] refInfo = ReadAction.compute(() -> {
                                PsiElement refElement = reference.getElement();
                                Module module = ModuleUtilCore.findModuleForPsiElement(refElement);
                                String moduleName = module != null ? module.getName() : "";
                                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(
                                        refElement, PsiMethod.class);
                                String location = callerMethod != null
                                        ? getMethodSignature(callerMethod)
                                        : (refElement.getContainingFile() != null
                                                ? refElement.getContainingFile().getName()
                                                : "(unknown)");
                                return new String[]{moduleName, location};
                            });

                            PsiMethod callerMethod = ReadAction.compute(() ->
                                    PsiTreeUtil.getParentOfType(
                                            reference.getElement(), PsiMethod.class));

                            String chainStatus;
                            if (callerMethod == null) {
                                chainStatus = "非方法体引用";
                            } else {
                                String cacheKey = refInfo[1] + "|" + chainDepth;
                                boolean terminated = chainCache.computeIfAbsent(cacheKey,
                                        k -> isCallerChainTerminated(
                                                callerMethod, chainDepth, new HashSet<>()));
                                chainStatus = terminated ? "链路终止" : "链路继续";
                            }

                            results.add(new String[]{
                                    qualifiedName, projectName, refInfo[0], refInfo[1], chainStatus});
                            return true;
                        });
            }
        }
        return results;
    }

    /**
     * 递归检查方法的调用链是否在指定深度内终止。
     *
     * <ul>
     *   <li>若该方法在所有项目中均无调用者 → 返回 {@code true}（链路终止）</li>
     *   <li>若 depth=0 且仍有调用者 → 返回 {@code false}（链路继续，超出检查范围）</li>
     *   <li>若所有调用者的链路在 depth-1 层内也终止 → 返回 {@code true}</li>
     *   <li>若任意调用者的链路未终止 → 返回 {@code false}</li>
     *   <li>循环引用（已在 visited 中）→ 返回 {@code false}（保守处理，视为链路继续）</li>
     * </ul>
     */
    private static boolean isCallerChainTerminated(
            PsiMethod method, int depth, Set<String> visited) {
        String methodSig = ReadAction.compute(() -> getMethodSignature(method));
        if (visited.contains(methodSig)) return false; // 循环引用，保守处理
        visited.add(methodSig);

        AtomicBoolean foundLiveCaller = new AtomicBoolean(false);

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (foundLiveCaller.get()) break;

            PsiMethod targetMethod = ReadAction.compute(() -> {
                PsiClass cls = method.getContainingClass();
                if (cls == null) return null;
                String qualifiedName = cls.getQualifiedName();
                if (qualifiedName == null) return null;
                JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                PsiClass targetClass = facade.findClass(
                        qualifiedName, GlobalSearchScope.allScope(project));
                if (targetClass == null) return null;
                return findMatchingMethod(targetClass, method);
            });
            if (targetMethod == null) continue;

            ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                    .forEach(ref -> {
                        if (foundLiveCaller.get()) return false;

                        PsiMethod callerMethod = ReadAction.compute(() ->
                                PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));

                        if (callerMethod == null) {
                            // 字段/初始化块等非方法体引用，视为活跃入口
                            foundLiveCaller.set(true);
                            return false;
                        }

                        if (depth <= 0) {
                            // 已到达检查深度上限且仍有调用者 → 链路继续
                            foundLiveCaller.set(true);
                            return false;
                        }

                        // 递归检查上层调用者是否终止
                        if (!isCallerChainTerminated(callerMethod, depth - 1, new HashSet<>(visited))) {
                            foundLiveCaller.set(true);
                            return false;
                        }
                        return true;
                    });
        }

        return !foundLiveCaller.get();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 引用链注释收集
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 获取方法的 Javadoc 描述文本（仅描述部分，不含 @tag 行）。
     * 若方法自身无注释，则向上查找接口或父类的对应方法注释。
     *
     * <p>调用方必须持有 ReadAction。
     */
    public static String getMethodComment(PsiMethod method) {
        PsiDocComment doc = method.getDocComment();
        if (doc != null) {
            String text = extractDocText(doc);
            if (!text.isEmpty()) return text;
        }
        // 回退到接口或父类方法注释
        for (PsiMethod superMethod : method.findSuperMethods()) {
            doc = superMethod.getDocComment();
            if (doc != null) {
                String text = extractDocText(doc);
                if (!text.isEmpty()) return text;
            }
        }
        return "";
    }

    /**
     * 获取类的 Javadoc 描述文本。
     *
     * <p>调用方必须持有 ReadAction。
     */
    public static String getClassComment(PsiClass psiClass) {
        PsiDocComment doc = psiClass.getDocComment();
        if (doc == null) return "";
        return extractDocText(doc);
    }

    /**
     * 从 {@link PsiDocComment} 中提取纯描述文本（去除 * 前缀和多余空白）。
     *
     * <p>调用方必须持有 ReadAction。
     */
    private static String extractDocText(PsiDocComment doc) {
        StringBuilder sb = new StringBuilder();
        for (PsiElement el : doc.getDescriptionElements()) {
            sb.append(el.getText());
        }
        return sb.toString()
                 .replaceAll("(?m)^[ \t]*\\*[ \t]?", "")  // 去掉行首 *
                 .replaceAll("\\s+", " ")
                 .trim();
    }

    /**
     * 在所有已打开项目中查找方法引用，并对每条结果的引用方法（及其往上 {@code chainDepth} 层
     * 的调用链）收集 Javadoc 注释，拼接后作为第 5 列输出。
     *
     * <p>只搜索项目范围内的调用（{@code projectScope}），不包含外部库。
     *
     * @return 每条记录为 String[5]：{源方法签名, 目标项目, 目标模块, 引用方法, 引用链注释}
     *         "引用链注释" 为调用链上所有方法/类注释以 {@code " | "} 拼接的字符串
     */
    public static List<String[]> findReferencesWithComments(
            List<PsiMethod> methods, int chainDepth, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();
        Map<String, String> commentsCache = new HashMap<>();

        for (PsiMethod sourceMethod : methods) {
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
                indicator.setText("收集引用链注释: " + sourceSignature);
                if (indicator.isCanceled()) break;
            }

            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (indicator != null && indicator.isCanceled()) break;

                PsiMethod targetMethod = ReadAction.compute(() -> {
                    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                    PsiClass targetClass = facade.findClass(
                            qualifiedClassName, GlobalSearchScope.allScope(project));
                    if (targetClass == null) return null;
                    return findMatchingMethod(targetClass, sourceMethod);
                });
                if (targetMethod == null) continue;

                String projectName = project.getName();

                ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                        .forEach(reference -> {
                            if (indicator != null && indicator.isCanceled()) return false;

                            String[] refInfo = ReadAction.compute(() -> {
                                PsiElement refElement = reference.getElement();
                                Module module = ModuleUtilCore.findModuleForPsiElement(refElement);
                                String moduleName = module != null ? module.getName() : "";
                                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(
                                        refElement, PsiMethod.class);
                                String callerSig;
                                if (callerMethod != null) {
                                    callerSig = getMethodSignature(callerMethod);
                                } else {
                                    PsiFile file = refElement.getContainingFile();
                                    callerSig = file != null ? file.getName() : "(unknown)";
                                }
                                return new String[]{moduleName, callerSig};
                            });

                            PsiMethod callerMethod = ReadAction.compute(() ->
                                    PsiTreeUtil.getParentOfType(
                                            reference.getElement(), PsiMethod.class));

                            String chainComments;
                            if (callerMethod == null) {
                                // 引用在类体/字段中，取所在类的注释
                                chainComments = ReadAction.compute(() -> {
                                    PsiClass cls = PsiTreeUtil.getParentOfType(
                                            reference.getElement(), PsiClass.class);
                                    return cls != null ? getClassComment(cls) : "";
                                });
                            } else {
                                String cacheKey = refInfo[1] + "|" + chainDepth;
                                chainComments = commentsCache.computeIfAbsent(cacheKey, k -> {
                                    LinkedHashSet<String> commentSet = new LinkedHashSet<>();
                                    collectChainComments(
                                            callerMethod, chainDepth, new HashSet<>(), commentSet);
                                    return String.join(" | ", commentSet);
                                });
                            }

                            results.add(new String[]{
                                    sourceSignature, projectName, refInfo[0], refInfo[1], chainComments});
                            return true;
                        });
            }
        }
        return results;
    }

    /**
     * 在所有已打开项目中查找类引用，并对每条结果的引用位置往上追溯 {@code chainDepth} 层，
     * 收集 Javadoc 注释拼接为第 5 列。
     *
     * @return 每条记录为 String[5]：{源类全限定名, 目标项目, 目标模块, 引用位置, 引用链注释}
     */
    public static List<String[]> findClassReferencesWithComments(
            List<PsiClass> classes, int chainDepth, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();
        Map<String, String> commentsCache = new HashMap<>();

        for (PsiClass sourceClass : classes) {
            String qualifiedName = ReadAction.compute(() -> sourceClass.getQualifiedName());
            if (qualifiedName == null) continue;

            if (indicator != null) {
                indicator.setText("收集类引用链注释: " + qualifiedName);
                if (indicator.isCanceled()) break;
            }

            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (indicator != null && indicator.isCanceled()) break;

                PsiClass targetClass = ReadAction.compute(() ->
                        JavaPsiFacade.getInstance(project)
                                .findClass(qualifiedName, GlobalSearchScope.allScope(project)));
                if (targetClass == null) continue;

                String projectName = project.getName();

                ReferencesSearch.search(targetClass, GlobalSearchScope.projectScope(project))
                        .forEach(reference -> {
                            if (indicator != null && indicator.isCanceled()) return false;

                            String[] refInfo = ReadAction.compute(() -> {
                                PsiElement refElement = reference.getElement();
                                Module module = ModuleUtilCore.findModuleForPsiElement(refElement);
                                String moduleName = module != null ? module.getName() : "";
                                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(
                                        refElement, PsiMethod.class);
                                String location = callerMethod != null
                                        ? getMethodSignature(callerMethod)
                                        : (refElement.getContainingFile() != null
                                                ? refElement.getContainingFile().getName()
                                                : "(unknown)");
                                return new String[]{moduleName, location};
                            });

                            PsiMethod callerMethod = ReadAction.compute(() ->
                                    PsiTreeUtil.getParentOfType(
                                            reference.getElement(), PsiMethod.class));

                            String chainComments;
                            if (callerMethod == null) {
                                chainComments = ReadAction.compute(() -> {
                                    PsiClass cls = PsiTreeUtil.getParentOfType(
                                            reference.getElement(), PsiClass.class);
                                    return cls != null ? getClassComment(cls) : "";
                                });
                            } else {
                                String cacheKey = refInfo[1] + "|" + chainDepth;
                                chainComments = commentsCache.computeIfAbsent(cacheKey, k -> {
                                    LinkedHashSet<String> commentSet = new LinkedHashSet<>();
                                    collectChainComments(
                                            callerMethod, chainDepth, new HashSet<>(), commentSet);
                                    return String.join(" | ", commentSet);
                                });
                            }

                            results.add(new String[]{
                                    qualifiedName, projectName, refInfo[0], refInfo[1], chainComments});
                            return true;
                        });
            }
        }
        return results;
    }

    /**
     * 递归收集方法及其向上 {@code depth} 层调用链上所有方法/类的 Javadoc 注释。
     *
     * <ul>
     *   <li>只搜索项目范围（{@code projectScope}），不含外部库</li>
     *   <li>使用共享 {@code visited} 集合避免循环引用和重复计算</li>
     *   <li>注释按首次发现顺序放入 {@code comments}（LinkedHashSet 保序去重）</li>
     * </ul>
     */
    private static void collectChainComments(
            PsiMethod method, int depth, Set<String> visited, LinkedHashSet<String> comments) {
        if (depth <= 0) return;

        String methodSig = ReadAction.compute(() -> getMethodSignature(method));
        if (visited.contains(methodSig)) return;
        visited.add(methodSig);

        // 收集该方法自身的注释
        String comment = ReadAction.compute(() -> getMethodComment(method));
        if (!comment.isEmpty()) {
            comments.add(comment);
        }

        // 在各项目中查找该方法的调用者，继续向上收集
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            PsiMethod targetMethod = ReadAction.compute(() -> {
                PsiClass cls = method.getContainingClass();
                if (cls == null) return null;
                String qualifiedName = cls.getQualifiedName();
                if (qualifiedName == null) return null;
                JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                PsiClass targetClass = facade.findClass(
                        qualifiedName, GlobalSearchScope.allScope(project));
                if (targetClass == null) return null;
                return findMatchingMethod(targetClass, method);
            });
            if (targetMethod == null) continue;

            List<PsiMethod> callerMethods = new ArrayList<>();
            ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                    .forEach(ref -> {
                        PsiMethod callerMethod = ReadAction.compute(() ->
                                PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));
                        if (callerMethod != null) {
                            callerMethods.add(callerMethod);
                        } else {
                            // 不在方法体内的引用（字段/初始化块）→ 取所在类注释
                            String classComment = ReadAction.compute(() -> {
                                PsiClass cls = PsiTreeUtil.getParentOfType(
                                        ref.getElement(), PsiClass.class);
                                return cls != null ? getClassComment(cls) : "";
                            });
                            if (!classComment.isEmpty()) {
                                comments.add(classComment);
                            }
                        }
                        return true;
                    });

            for (PsiMethod caller : callerMethods) {
                collectChainComments(caller, depth - 1, visited, comments);
            }
        }
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
