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
    // 包引用 + Cache 方法检查
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 查找包内所有类及其方法的引用，并对每条引用的调用方检查是否调用了含 "cache" 关键词的方法。
     *
     * <p>输出 6 列：
     * <ol>
     *   <li>源类（全限定名或方法签名）</li>
     *   <li>目标项目</li>
     *   <li>目标模块</li>
     *   <li>引用位置（调用方方法签名或文件名）</li>
     *   <li>含Cache方法：{@code "是"} / {@code "否"} / {@code "-"}（非方法体引用时）</li>
     *   <li>Cache方法列表：含 cache 的被调用方法签名，以 {@code " | "} 分隔；无则 {@code "-"}</li>
     * </ol>
     */
    public static List<String[]> findPackageReferencesWithCacheCheck(
            List<PsiClass> classes, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();

        for (PsiClass sourceClass : classes) {
            String qualifiedName = ReadAction.compute(() -> sourceClass.getQualifiedName());
            if (qualifiedName == null) continue;

            // ── 类级别引用 ──
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
                        .forEach(ref -> {
                            if (indicator != null && indicator.isCanceled()) return false;
                            results.add(buildCacheRow(qualifiedName, projectName, ref));
                            return true;
                        });
            }

            // ── 方法级别引用 ──
            List<PsiMethod> methods = ReadAction.compute(() ->
                    Arrays.asList(sourceClass.getMethods()));

            for (PsiMethod sourceMethod : methods) {
                String[] sourceInfo = ReadAction.compute(() -> {
                    String qName = sourceClass.getQualifiedName();
                    if (qName == null) return null;
                    return new String[]{qName, getMethodSignature(sourceMethod)};
                });
                if (sourceInfo == null) continue;
                String methodSig = sourceInfo[1];

                if (indicator != null) {
                    indicator.setText("查找方法引用: " + methodSig);
                    if (indicator.isCanceled()) break;
                }

                for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                    if (indicator != null && indicator.isCanceled()) break;
                    PsiMethod targetMethod = ReadAction.compute(() -> {
                        JavaPsiFacade f = JavaPsiFacade.getInstance(project);
                        PsiClass cls = f.findClass(sourceInfo[0], GlobalSearchScope.allScope(project));
                        return cls == null ? null : findMatchingMethod(cls, sourceMethod);
                    });
                    if (targetMethod == null) continue;
                    String projectName = project.getName();

                    ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                            .forEach(ref -> {
                                if (indicator != null && indicator.isCanceled()) return false;
                                results.add(buildCacheRow(methodSig, projectName, ref));
                                return true;
                            });
                }
            }
        }
        return results;
    }

    /** 构造一行 cache 检查结果：{源目标, 项目, 模块, 引用位置, 含Cache方法, Cache方法列表}。 */
    private static String[] buildCacheRow(String sourceLabel, String projectName, PsiReference ref) {
        String[] meta = ReadAction.compute(() -> {
            PsiElement refElement = ref.getElement();
            Module module = ModuleUtilCore.findModuleForPsiElement(refElement);
            String moduleName = module != null ? module.getName() : "";
            PsiMethod callerMethod = PsiTreeUtil.getParentOfType(refElement, PsiMethod.class);
            String location = callerMethod != null
                    ? getMethodSignature(callerMethod)
                    : (refElement.getContainingFile() != null
                            ? refElement.getContainingFile().getName()
                            : "(unknown)");
            return new String[]{moduleName, location};
        });

        PsiMethod callerMethod = ReadAction.compute(() ->
                PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));

        String hasCacheFlag, cacheList;
        if (callerMethod == null) {
            hasCacheFlag = "-";
            cacheList    = "-";
        } else {
            List<String> cacheCalls = ReadAction.compute(() ->
                    findCacheCallsInMethod(callerMethod));
            hasCacheFlag = cacheCalls.isEmpty() ? "否" : "是";
            cacheList    = cacheCalls.isEmpty() ? "-" : String.join(" | ", cacheCalls);
        }

        return new String[]{sourceLabel, projectName, meta[0], meta[1], hasCacheFlag, cacheList};
    }

    /**
     * 在方法体内查找所有方法名含 "cache"（不区分大小写）的调用，返回它们的签名列表。
     *
     * <p>调用方必须持有 ReadAction。
     */
    private static List<String> findCacheCallsInMethod(PsiMethod method) {
        List<String> result = new ArrayList<>();
        java.util.Collection<PsiMethodCallExpression> calls =
                PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression.class);
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (PsiMethodCallExpression call : calls) {
            String refName = call.getMethodExpression().getReferenceName();
            if (refName == null || !refName.toLowerCase().contains("cache")) continue;
            PsiMethod resolved = call.resolveMethod();
            String sig = resolved != null ? getMethodSignature(resolved) : refName + "()";
            if (seen.add(sig)) result.add(sig);
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 包引用 + RestController 接口检查
    // ──────────────────────────────────────────────────────────────────────────

    /** Mapping 注解简单名集合（不依赖 Spring JAR，用短名匹配）。 */
    private static final Set<String> MAPPING_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping", "RequestMapping"
    ));

    /**
     * 查找包内所有类及其方法的引用，并对每条引用的调用方检查：
     * 该调用方法是否为 {@code @RestController} / {@code @Controller} 的接口方法；
     * 若是，则输出完整 HTTP 接口路径（类路径 + 方法路径）。
     *
     * <p>输出 6 列：
     * <ol>
     *   <li>源类（全限定名或方法签名）</li>
     *   <li>目标项目</li>
     *   <li>目标模块</li>
     *   <li>引用位置（调用方方法签名或文件名）</li>
     *   <li>是否Controller接口：{@code "是"} / {@code "否"} / {@code "-"}（非方法体引用时）</li>
     *   <li>完整接口路径：例如 {@code "GET /api/v1/users/{id}"}；非接口时为 {@code "-"}</li>
     * </ol>
     */
    public static List<String[]> findPackageReferencesWithControllerCheck(
            List<PsiClass> classes, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();

        for (PsiClass sourceClass : classes) {
            String qualifiedName = ReadAction.compute(() -> sourceClass.getQualifiedName());
            if (qualifiedName == null) continue;

            // ── 类级别引用 ──
            if (indicator != null) {
                indicator.setText("查找类引用(Controller检查): " + qualifiedName);
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
                        .forEach(ref -> {
                            if (indicator != null && indicator.isCanceled()) return false;
                            results.add(buildControllerRow(qualifiedName, projectName, ref));
                            return true;
                        });
            }

            // ── 方法级别引用 ──
            List<PsiMethod> methods = ReadAction.compute(() ->
                    Arrays.asList(sourceClass.getMethods()));

            for (PsiMethod sourceMethod : methods) {
                String[] sourceInfo = ReadAction.compute(() -> {
                    String qn = sourceClass.getQualifiedName();
                    if (qn == null) return null;
                    return new String[]{qn, getMethodSignature(sourceMethod)};
                });
                if (sourceInfo == null) continue;
                String methodSig = sourceInfo[1];

                if (indicator != null) {
                    indicator.setText("查找方法引用(Controller检查): " + methodSig);
                    if (indicator.isCanceled()) break;
                }

                for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                    if (indicator != null && indicator.isCanceled()) break;
                    PsiMethod targetMethod = ReadAction.compute(() -> {
                        JavaPsiFacade f = JavaPsiFacade.getInstance(project);
                        PsiClass cls = f.findClass(sourceInfo[0], GlobalSearchScope.allScope(project));
                        return cls == null ? null : findMatchingMethod(cls, sourceMethod);
                    });
                    if (targetMethod == null) continue;
                    String projectName = project.getName();

                    ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                            .forEach(ref -> {
                                if (indicator != null && indicator.isCanceled()) return false;
                                results.add(buildControllerRow(methodSig, projectName, ref));
                                return true;
                            });
                }
            }
        }
        return results;
    }

    /** 构造一行 Controller 检查结果：{源目标, 项目, 模块, 引用位置, 是否Controller接口, 完整接口路径}。 */
    private static String[] buildControllerRow(String sourceLabel, String projectName,
                                               PsiReference ref) {
        String[] meta = ReadAction.compute(() -> {
            PsiElement refElement = ref.getElement();
            Module module = ModuleUtilCore.findModuleForPsiElement(refElement);
            String moduleName = module != null ? module.getName() : "";
            PsiMethod callerMethod = PsiTreeUtil.getParentOfType(refElement, PsiMethod.class);
            String location = callerMethod != null
                    ? getMethodSignature(callerMethod)
                    : (refElement.getContainingFile() != null
                            ? refElement.getContainingFile().getName()
                            : "(unknown)");
            return new String[]{moduleName, location};
        });

        PsiMethod callerMethod = ReadAction.compute(() ->
                PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));

        String[] endpointInfo = callerMethod == null
                ? new String[]{"-", "-"}
                : ReadAction.compute(() -> getEndpointInfo(callerMethod));

        return new String[]{sourceLabel, projectName, meta[0], meta[1],
                endpointInfo[0], endpointInfo[1]};
    }

    /**
     * 判断 callerMethod 是否为 RestController/Controller 的接口方法，并返回完整接口路径。
     *
     * @return String[2]：{是否Controller接口("是"/"否"), 完整接口路径("GET /api/v1/users/{id}" 或 "-")}
     *         调用方必须持有 ReadAction。
     */
    private static String[] getEndpointInfo(PsiMethod callerMethod) {
        PsiClass containingClass = callerMethod.getContainingClass();
        if (containingClass == null) return new String[]{"否", "-"};

        // 检查类是否有 @RestController 或 @Controller
        boolean isController = false;
        for (PsiAnnotation ann : containingClass.getAnnotations()) {
            String name = shortName(ann);
            if ("RestController".equals(name) || "Controller".equals(name)) {
                isController = true;
                break;
            }
        }
        if (!isController) return new String[]{"否", "-"};

        // 检查方法是否有 Mapping 注解
        PsiAnnotation mappingAnnotation = null;
        for (PsiAnnotation ann : callerMethod.getAnnotations()) {
            if (MAPPING_ANNOTATIONS.contains(shortName(ann))) {
                mappingAnnotation = ann;
                break;
            }
        }
        if (mappingAnnotation == null) return new String[]{"否", "-"};

        // 推断 HTTP 方法
        String httpMethod = resolveHttpMethod(mappingAnnotation);

        // 读取类级别 @RequestMapping 路径
        String classPath = "";
        PsiAnnotation classMapping = containingClass.getAnnotation(
                "org.springframework.web.bind.annotation.RequestMapping");
        if (classMapping == null) {
            // 用短名兜底（适配未完整索引的项目）
            for (PsiAnnotation ann : containingClass.getAnnotations()) {
                if ("RequestMapping".equals(shortName(ann))) {
                    classMapping = ann;
                    break;
                }
            }
        }
        if (classMapping != null) {
            classPath = extractAnnotationPath(classMapping);
        }

        // 读取方法级别 Mapping 路径
        String methodPath = extractAnnotationPath(mappingAnnotation);

        // 拼接完整路径
        String fullPath = normalizePath(classPath) + normalizePath(methodPath);
        if (fullPath.isEmpty()) fullPath = "/";

        return new String[]{"是", httpMethod + " " + fullPath};
    }

    /** 根据注解名推断 HTTP 方法；{@code @RequestMapping} 则读 {@code method} 属性，默认 GET。 */
    private static String resolveHttpMethod(PsiAnnotation annotation) {
        String sn = shortName(annotation);
        switch (sn) {
            case "GetMapping":    return "GET";
            case "PostMapping":   return "POST";
            case "PutMapping":    return "PUT";
            case "DeleteMapping": return "DELETE";
            case "PatchMapping":  return "PATCH";
            default: // RequestMapping — 读 method 属性
                PsiAnnotationMemberValue methodAttr =
                        annotation.findAttributeValue("method");
                if (methodAttr != null) {
                    String text = methodAttr.getText();
                    // e.g. "RequestMethod.POST" or "{RequestMethod.GET, RequestMethod.POST}"
                    for (String m : new String[]{"POST","PUT","DELETE","PATCH","GET"}) {
                        if (text.contains(m)) return m;
                    }
                }
                return "GET";
        }
    }

    /**
     * 从 Mapping 注解中提取路径字符串（优先 {@code value}，其次 {@code path}）。
     * 支持数组形式（取第一个元素）。
     * 调用方必须持有 ReadAction。
     */
    private static String extractAnnotationPath(PsiAnnotation annotation) {
        for (String attr : new String[]{"value", "path"}) {
            PsiAnnotationMemberValue val = annotation.findAttributeValue(attr);
            if (val == null) continue;
            // 数组形式 e.g. {"/users", "/user"}
            if (val instanceof PsiArrayInitializerMemberValue) {
                PsiAnnotationMemberValue[] initializers =
                        ((PsiArrayInitializerMemberValue) val).getInitializers();
                if (initializers.length > 0) {
                    return stripQuotes(initializers[0].getText());
                }
            } else {
                String text = stripQuotes(val.getText());
                if (!text.isEmpty()) return text;
            }
        }
        return "";
    }

    /** 从 PsiAnnotation 的全限定名中提取短名（最后一个 . 之后的部分）。 */
    private static String shortName(PsiAnnotation annotation) {
        String qn = annotation.getQualifiedName();
        if (qn == null) return "";
        int dot = qn.lastIndexOf('.');
        return dot >= 0 ? qn.substring(dot + 1) : qn;
    }

    /** 去掉字符串字面量两侧的引号。 */
    private static String stripQuotes(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 确保路径以 / 开头，末尾不加 /。空字符串原样返回。 */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "";
        if (!path.startsWith("/")) path = "/" + path;
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        return path;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 包引用 + 引用位置自身被引用检查
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 查找包内所有类及其方法的引用，并对每条引用的调用方检查：
     * 该调用方法（或所在类）自身在目标项目中是否有调用方（即是否被其他代码引用）。
     *
     * <p>输出 5 列：
     * <ol>
     *   <li>引用位置是否被引用：{@code "是"} / {@code "否"} / {@code "-"}（拿不到方法或类时）</li>
     *   <li>源类（全限定名或方法签名）</li>
     *   <li>目标项目</li>
     *   <li>目标模块</li>
     *   <li>引用位置（调用方方法签名或文件名）</li>
     * </ol>
     */
    public static List<String[]> findPackageReferencesWithUsageCheck(
            List<PsiClass> classes, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();

        for (PsiClass sourceClass : classes) {
            String qualifiedName = ReadAction.compute(() -> sourceClass.getQualifiedName());
            if (qualifiedName == null) continue;

            // ── 类级别引用 ──
            if (indicator != null) {
                indicator.setText("查找类引用(被引用检查): " + qualifiedName);
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
                        .forEach(ref -> {
                            if (indicator != null && indicator.isCanceled()) return false;
                            results.add(buildUsageCheckRow(qualifiedName, projectName, project, ref));
                            return true;
                        });
            }

            // ── 方法级别引用 ──
            List<PsiMethod> methods = ReadAction.compute(() ->
                    Arrays.asList(sourceClass.getMethods()));

            for (PsiMethod sourceMethod : methods) {
                String[] sourceInfo = ReadAction.compute(() -> {
                    String qn = sourceClass.getQualifiedName();
                    if (qn == null) return null;
                    return new String[]{qn, getMethodSignature(sourceMethod)};
                });
                if (sourceInfo == null) continue;
                String methodSig = sourceInfo[1];

                if (indicator != null) {
                    indicator.setText("查找方法引用(被引用检查): " + methodSig);
                    if (indicator.isCanceled()) break;
                }

                for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                    if (indicator != null && indicator.isCanceled()) break;
                    PsiMethod targetMethod = ReadAction.compute(() -> {
                        JavaPsiFacade f = JavaPsiFacade.getInstance(project);
                        PsiClass cls = f.findClass(sourceInfo[0], GlobalSearchScope.allScope(project));
                        return cls == null ? null : findMatchingMethod(cls, sourceMethod);
                    });
                    if (targetMethod == null) continue;
                    String projectName = project.getName();

                    ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                            .forEach(ref -> {
                                if (indicator != null && indicator.isCanceled()) return false;
                                results.add(buildUsageCheckRow(methodSig, projectName, project, ref));
                                return true;
                            });
                }
            }
        }
        return results;
    }

    /** 构造一行被引用检查结果：{引用位置是否被引用, 源目标, 项目, 模块, 引用位置}。 */
    private static String[] buildUsageCheckRow(String sourceLabel, String projectName,
                                               Project project, PsiReference ref) {
        String[] meta = ReadAction.compute(() -> {
            PsiElement refElement = ref.getElement();
            Module module = ModuleUtilCore.findModuleForPsiElement(refElement);
            String moduleName = module != null ? module.getName() : "";
            PsiMethod callerMethod = PsiTreeUtil.getParentOfType(refElement, PsiMethod.class);
            String location = callerMethod != null
                    ? getMethodSignature(callerMethod)
                    : (refElement.getContainingFile() != null
                            ? refElement.getContainingFile().getName()
                            : "(unknown)");
            return new String[]{moduleName, location};
        });

        String usageFlag = isCallerReferenced(ref, project);
        return new String[]{usageFlag, sourceLabel, projectName, meta[0], meta[1]};
    }

    /**
     * 判断引用元素所在的方法（或类）自身在目标项目中是否有调用方。
     *
     * <p>逻辑：
     * <ul>
     *   <li>引用位于某方法体内 → 检查该方法在项目范围内是否有引用</li>
     *   <li>否则（import / 字段 / 类级别）→ 检查所在类是否有引用</li>
     *   <li>拿不到方法或类 → 返回 {@code "-"}</li>
     * </ul>
     *
     * @return {@code "是"} / {@code "否"} / {@code "-"}
     */
    private static String isCallerReferenced(PsiReference ref, Project project) {
        PsiMethod callerMethod = ReadAction.compute(() ->
                PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));

        if (callerMethod != null) {
            return ReferencesSearch.search(callerMethod, GlobalSearchScope.projectScope(project))
                    .findFirst() != null ? "是" : "否";
        }

        PsiClass containingClass = ReadAction.compute(() ->
                PsiTreeUtil.getParentOfType(ref.getElement(), PsiClass.class));
        if (containingClass == null) return "-";

        return ReferencesSearch.search(containingClass, GlobalSearchScope.projectScope(project))
                .findFirst() != null ? "是" : "否";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 包内仅方法引用 + 调用方被引用检查
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 查找包内所有类的所有方法被引用的位置，并检查每条引用的调用方方法自身是否被其他代码调用。
     *
     * <p>只处理方法级别引用（跳过类级别），因此引用位置一定是某个具体方法，列值只有"是"/"否"。
     *
     * <p>输出 5 列：
     * <ol>
     *   <li>源类（方法签名）</li>
     *   <li>目标项目</li>
     *   <li>目标模块</li>
     *   <li>引用位置（调用方方法签名）</li>
     *   <li>引用位置方法是否被引用：{@code "是"} / {@code "否"}</li>
     * </ol>
     */
    public static List<String[]> findPackageMethodRefsWithCallerCheck(
            List<PsiClass> classes, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();

        for (PsiClass sourceClass : classes) {
            List<PsiMethod> methods = ReadAction.compute(() ->
                    Arrays.asList(sourceClass.getMethods()));

            for (PsiMethod sourceMethod : methods) {
                String[] sourceInfo = ReadAction.compute(() -> {
                    String qn = sourceClass.getQualifiedName();
                    if (qn == null) return null;
                    return new String[]{qn, getMethodSignature(sourceMethod)};
                });
                if (sourceInfo == null) continue;
                String methodSig = sourceInfo[1];

                if (indicator != null) {
                    indicator.setText("查找方法引用(调用方检查): " + methodSig);
                    if (indicator.isCanceled()) break;
                }

                for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                    if (indicator != null && indicator.isCanceled()) break;

                    PsiMethod targetMethod = ReadAction.compute(() -> {
                        JavaPsiFacade f = JavaPsiFacade.getInstance(project);
                        PsiClass cls = f.findClass(sourceInfo[0], GlobalSearchScope.allScope(project));
                        return cls == null ? null : findMatchingMethod(cls, sourceMethod);
                    });
                    if (targetMethod == null) continue;
                    String projectName = project.getName();

                    ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                            .forEach(ref -> {
                                if (indicator != null && indicator.isCanceled()) return false;

                                String[] row = ReadAction.compute(() -> {
                                    PsiElement refEl = ref.getElement();
                                    PsiMethod caller = PsiTreeUtil.getParentOfType(refEl, PsiMethod.class);
                                    if (caller == null) return null; // 非方法体内引用，跳过
                                    Module module = ModuleUtilCore.findModuleForPsiElement(refEl);
                                    String moduleName = module != null ? module.getName() : "";
                                    String location = getMethodSignature(caller);
                                    return new String[]{moduleName, location};
                                });
                                if (row == null) return true;

                                PsiMethod caller = ReadAction.compute(() ->
                                        PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));
                                String callerReferenced = ReferencesSearch
                                        .search(caller, GlobalSearchScope.projectScope(project))
                                        .findFirst() != null ? "是" : "否";

                                results.add(new String[]{methodSig, projectName, row[0], row[1], callerReferenced});
                                return true;
                            });
                }
            }
        }
        return results;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 包内仅类引用 + import 能否清理检查
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 查找包内所有类被引用的位置（仅类级别引用），并检查每条引用处的 import 是否可以清理。
     *
     * <p>判断规则：
     * <ul>
     *   <li>引用元素位于 import 语句内 → 在同一文件 scope 内再次搜索该类的所有引用，
     *       若除 import 外无其他实际用法则可清理（{@code "是"}），否则 {@code "否"}</li>
     *   <li>引用元素为实际代码用法（字段类型、方法参数、方法体等）→ {@code "否"}</li>
     * </ul>
     *
     * <p>输出 5 列：
     * <ol>
     *   <li>源类（全限定名）</li>
     *   <li>目标项目</li>
     *   <li>目标模块</li>
     *   <li>引用位置（调用方方法签名或文件名）</li>
     *   <li>能否清理import：{@code "是"} / {@code "否"}</li>
     * </ol>
     */
    public static List<String[]> findPackageClassRefsWithImportCheck(
            List<PsiClass> classes, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();

        for (PsiClass sourceClass : classes) {
            String qualifiedName = ReadAction.compute(() -> sourceClass.getQualifiedName());
            if (qualifiedName == null) continue;

            if (indicator != null) {
                indicator.setText("查找类引用(import清理检查): " + qualifiedName);
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
                        .forEach(ref -> {
                            if (indicator != null && indicator.isCanceled()) return false;

                            String[] meta = ReadAction.compute(() -> {
                                PsiElement refEl = ref.getElement();
                                Module module = ModuleUtilCore.findModuleForPsiElement(refEl);
                                String moduleName = module != null ? module.getName() : "";
                                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(
                                        refEl, PsiMethod.class);
                                String location = callerMethod != null
                                        ? getMethodSignature(callerMethod)
                                        : (refEl.getContainingFile() != null
                                                ? refEl.getContainingFile().getName()
                                                : "(unknown)");
                                return new String[]{moduleName, location};
                            });

                            String canCleanImport = resolveImportCleanable(ref, targetClass);
                            results.add(new String[]{
                                    qualifiedName, projectName, meta[0], meta[1], canCleanImport});
                            return true;
                        });
            }
        }
        return results;
    }

    /**
     * 判断该引用处的 import 是否可以清理。
     *
     * @return {@code "是"} 可清理 / {@code "否"} 不可清理
     */
    private static String resolveImportCleanable(PsiReference ref, PsiClass targetClass) {
        // 判断引用是否位于 import 语句内
        boolean isImport = ReadAction.compute(() ->
                PsiTreeUtil.getParentOfType(ref.getElement(), PsiImportStatement.class) != null);

        if (!isImport) return "否"; // 实际代码用法，import 不可清理

        // 在同一文件内搜索该类的所有引用，看是否存在非 import 的实际用法
        PsiFile file = ReadAction.compute(() -> ref.getElement().getContainingFile());
        if (file == null) return "否";

        boolean hasActualUsage = ReferencesSearch
                .search(targetClass, GlobalSearchScope.fileScope(file))
                .anyMatch(r -> ReadAction.compute(() ->
                        PsiTreeUtil.getParentOfType(r.getElement(), PsiImportStatement.class) == null));

        return hasActualUsage ? "否" : "是";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 引用链分析（死代码检测：找出引用方，判断引用方是否有活跃入口，可否删除）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 分析方法列表的引用情况。
     *
     * <p><b>逻辑：</b>
     * <ol>
     *   <li>对每个源方法，找出所有直接引用它的调用方（记为 A）。</li>
     *   <li>对每个 A，向上追溯最多 {@code chainDepth} 层，判断 A 是否有活跃入口：
     *       <ul>
     *         <li>A 无调用者 → A 可删除（"无引用"）</li>
     *         <li>A 的所有调用链均无活跃入口 → A 可删除（"死代码链"）</li>
     *         <li>A 存在至少一条活跃调用链 → A 不可删除</li>
     *       </ul>
     *   </li>
     *   <li>若源方法本身无任何引用，则输出一行标注源方法可删除。</li>
     * </ol>
     *
     * @return 每条记录为 String[4]：{源方法签名, 引用方签名, 引用方可删除("是"/"否"), 引用状态}
     */
    public static List<String[]> analyzeMethodReferences(
            List<PsiMethod> methods, int chainDepth, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();
        Map<String, Boolean> deadCache = new HashMap<>();

        for (PsiMethod sourceMethod : methods) {
            String[] sourceInfo = ReadAction.compute(() -> {
                PsiClass sourceClass = sourceMethod.getContainingClass();
                if (sourceClass == null) return null;
                String qName = sourceClass.getQualifiedName();
                if (qName == null) return null;
                return new String[]{qName, getMethodSignature(sourceMethod)};
            });
            if (sourceInfo == null) continue;

            String methodSig = sourceInfo[1];
            if (indicator != null) {
                indicator.setText("分析引用: " + methodSig);
                if (indicator.isCanceled()) break;
            }

            // 收集所有直接引用方 A
            boolean[] hasAnyRef    = {false};
            List<PsiMethod> callerMethods   = new ArrayList<>();
            List<String>    nonMethodRefs   = new ArrayList<>(); // 非方法体内的引用位置

            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (indicator != null && indicator.isCanceled()) break;
                PsiMethod target = ReadAction.compute(() -> {
                    JavaPsiFacade f = JavaPsiFacade.getInstance(project);
                    PsiClass cls = f.findClass(sourceInfo[0], GlobalSearchScope.allScope(project));
                    return cls == null ? null : findMatchingMethod(cls, sourceMethod);
                });
                if (target == null) continue;

                ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
                        .forEach(ref -> {
                            if (indicator != null && indicator.isCanceled()) return false;
                            hasAnyRef[0] = true;
                            PsiMethod caller = ReadAction.compute(() ->
                                    PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));
                            if (caller == null) {
                                // 字段/注解/静态块等非方法体引用
                                String loc = ReadAction.compute(() -> {
                                    PsiFile f2 = ref.getElement().getContainingFile();
                                    return f2 != null ? f2.getName() + ":" + ref.getElement().getTextOffset() : "非方法体";
                                });
                                nonMethodRefs.add(loc);
                            } else {
                                callerMethods.add(caller);
                            }
                            return true;
                        });
            }

            if (!hasAnyRef[0]) {
                // 源方法本身无任何引用，直接可删除
                results.add(new String[]{methodSig, "(无引用)", "是", "无引用"});
                continue;
            }

            // 对每个调用方法 A，判断 A 是否可删除
            for (PsiMethod callerA : callerMethods) {
                String callerSig = ReadAction.compute(() -> getMethodSignature(callerA));
                boolean isDead = deadCache.computeIfAbsent(
                        callerSig + "|" + chainDepth,
                        k -> isTransitivelyDead(callerA, chainDepth, new HashSet<>()));
                results.add(new String[]{
                        methodSig,
                        callerSig,
                        isDead ? "是" : "否",
                        isDead ? "死代码链" : "有活跃引用"
                });
            }

            // 非方法体引用（字段/注解等）→ 活跃，不可删除
            for (String loc : nonMethodRefs) {
                results.add(new String[]{methodSig, "非方法体引用: " + loc, "否", "有活跃引用"});
            }
        }
        return results;
    }

    /**
     * 分析类列表的引用情况（类级别引用），找出每条引用所在的调用方，并判断调用方是否可删除。
     *
     * @return 每条记录为 String[4]：{源类全限定名, 引用方签名, 引用方可删除("是"/"否"), 引用状态}
     */
    public static List<String[]> analyzeClassReferences(
            List<PsiClass> classes, int chainDepth, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();
        Map<String, Boolean> deadCache = new HashMap<>();

        for (PsiClass sourceClass : classes) {
            String qualifiedName = ReadAction.compute(() -> sourceClass.getQualifiedName());
            if (qualifiedName == null) continue;

            if (indicator != null) {
                indicator.setText("分析类引用: " + qualifiedName);
                if (indicator.isCanceled()) break;
            }

            boolean[] hasAnyRef  = {false};
            List<PsiMethod> callerMethods  = new ArrayList<>();
            List<String>    nonMethodRefs  = new ArrayList<>();

            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (indicator != null && indicator.isCanceled()) break;
                PsiClass target = ReadAction.compute(() ->
                        JavaPsiFacade.getInstance(project)
                                .findClass(qualifiedName, GlobalSearchScope.allScope(project)));
                if (target == null) continue;

                ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
                        .forEach(ref -> {
                            if (indicator != null && indicator.isCanceled()) return false;
                            hasAnyRef[0] = true;
                            PsiMethod caller = ReadAction.compute(() ->
                                    PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));
                            if (caller == null) {
                                String loc = ReadAction.compute(() -> {
                                    PsiFile f = ref.getElement().getContainingFile();
                                    return f != null ? f.getName() + ":" + ref.getElement().getTextOffset() : "非方法体";
                                });
                                nonMethodRefs.add(loc);
                            } else {
                                callerMethods.add(caller);
                            }
                            return true;
                        });
            }

            if (!hasAnyRef[0]) {
                results.add(new String[]{qualifiedName, "(无引用)", "是", "无引用"});
                continue;
            }

            for (PsiMethod callerA : callerMethods) {
                String callerSig = ReadAction.compute(() -> getMethodSignature(callerA));
                boolean isDead = deadCache.computeIfAbsent(
                        callerSig + "|" + chainDepth,
                        k -> isTransitivelyDead(callerA, chainDepth, new HashSet<>()));
                results.add(new String[]{
                        qualifiedName,
                        callerSig,
                        isDead ? "是" : "否",
                        isDead ? "死代码链" : "有活跃引用"
                });
            }

            for (String loc : nonMethodRefs) {
                results.add(new String[]{qualifiedName, "非方法体引用: " + loc, "否", "有活跃引用"});
            }
        }
        return results;
    }

    /**
     * 判断方法是否为"传递性死代码"（向上最多追溯 depth 层，所有调用路径均无活跃入口）。
     *
     * <ul>
     *   <li>无调用者 → {@code true}（死端：无引用的代码，或 main/test 等入口）</li>
     *   <li>depth=0 且仍有方法调用者 → {@code false}（到达深度上限，保守视为活跃）</li>
     *   <li>非方法体引用（字段/注解等）→ {@code false}（活跃引用）</li>
     *   <li>所有调用者均为传递性死代码 → {@code true}</li>
     *   <li>任意调用者存活 → {@code false}</li>
     *   <li>循环引用 → {@code false}（保守视为活跃）</li>
     * </ul>
     */
    private static boolean isTransitivelyDead(PsiMethod method, int depth, Set<String> visited) {
        String sig = ReadAction.compute(() -> getMethodSignature(method));
        if (visited.contains(sig)) return false; // 循环引用，保守视为活跃
        visited.add(sig);

        AtomicBoolean hasCallers      = new AtomicBoolean(false);
        AtomicBoolean foundLiveCaller = new AtomicBoolean(false);

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (foundLiveCaller.get()) break;

            PsiMethod target = ReadAction.compute(() -> {
                PsiClass cls = method.getContainingClass();
                if (cls == null) return null;
                String qName = cls.getQualifiedName();
                if (qName == null) return null;
                JavaPsiFacade f = JavaPsiFacade.getInstance(project);
                PsiClass targetClass = f.findClass(qName, GlobalSearchScope.allScope(project));
                return targetClass == null ? null : findMatchingMethod(targetClass, method);
            });
            if (target == null) continue;

            ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
                    .forEach(ref -> {
                        if (foundLiveCaller.get()) return false;
                        hasCallers.set(true);

                        PsiMethod caller = ReadAction.compute(() ->
                                PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));

                        if (caller == null) {
                            // 字段/注解等非方法体引用 → 活跃
                            foundLiveCaller.set(true);
                            return false;
                        }
                        if (depth <= 0) {
                            // 到达深度上限且仍有方法调用者 → 保守视为活跃
                            foundLiveCaller.set(true);
                            return false;
                        }
                        if (!isTransitivelyDead(caller, depth - 1, new HashSet<>(visited))) {
                            foundLiveCaller.set(true);
                            return false;
                        }
                        return true;
                    });
        }

        // 无调用者（死端） 或 所有调用者均死亡 → 传递性死代码
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
     * 在所有已打开项目中查找方法引用，对每个引用方 A 收集：
     * A 自身的注释 + A 的直接调用者注释 + 第 2 层调用者注释 + 第 3 层调用者注释（共 chainDepth 层），
     * 以 {@code " | "} 拼接后放到 A 旁边的"引用链注释"列。
     *
     * <p>每个引用方 A 输出一行（而非每个源方法一行），便于逐行查看每条引用的完整调用链注释。
     *
     * @return 每条记录为 String[3]：{源方法签名, 引用方A签名, 引用链注释}
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

                ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                        .forEach(reference -> {
                            if (indicator != null && indicator.isCanceled()) return false;

                            // 取引用方 A 的签名
                            String callerSig = ReadAction.compute(() -> {
                                PsiElement refElement = reference.getElement();
                                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(
                                        refElement, PsiMethod.class);
                                if (callerMethod != null) return getMethodSignature(callerMethod);
                                PsiFile file = refElement.getContainingFile();
                                return file != null ? file.getName() : "(unknown)";
                            });

                            PsiMethod callerMethod = ReadAction.compute(() ->
                                    PsiTreeUtil.getParentOfType(
                                            reference.getElement(), PsiMethod.class));

                            // 收集 A 的注释 + A 往上各层调用者的注释
                            String chainComments;
                            if (callerMethod == null) {
                                // 非方法体引用（字段/注解等）→ 取所在类注释
                                chainComments = ReadAction.compute(() -> {
                                    PsiClass cls = PsiTreeUtil.getParentOfType(
                                            reference.getElement(), PsiClass.class);
                                    return cls != null ? getClassComment(cls) : "";
                                });
                            } else {
                                chainComments = commentsCache.computeIfAbsent(
                                        callerSig + "|" + chainDepth, k -> {
                                            LinkedHashSet<String> commentSet = new LinkedHashSet<>();
                                            collectChainComments(
                                                    callerMethod, chainDepth, new HashSet<>(), commentSet);
                                            return String.join(" | ", commentSet);
                                        });
                            }

                            results.add(new String[]{sourceSignature, callerSig, chainComments});
                            return true;
                        });
            }
        }
        return results;
    }

    /**
     * 在所有已打开项目中查找类引用，对每个引用方 A 收集调用链注释（同 {@link #findReferencesWithComments}）。
     *
     * @return 每条记录为 String[3]：{源类全限定名, 引用方签名, 引用链注释}
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

                ReferencesSearch.search(targetClass, GlobalSearchScope.projectScope(project))
                        .forEach(reference -> {
                            if (indicator != null && indicator.isCanceled()) return false;

                            String callerSig = ReadAction.compute(() -> {
                                PsiElement refElement = reference.getElement();
                                PsiMethod callerMethod = PsiTreeUtil.getParentOfType(
                                        refElement, PsiMethod.class);
                                if (callerMethod != null) return getMethodSignature(callerMethod);
                                PsiFile file = refElement.getContainingFile();
                                return file != null ? file.getName() : "(unknown)";
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
                                chainComments = commentsCache.computeIfAbsent(
                                        callerSig + "|" + chainDepth, k -> {
                                            LinkedHashSet<String> commentSet = new LinkedHashSet<>();
                                            collectChainComments(
                                                    callerMethod, chainDepth, new HashSet<>(), commentSet);
                                            return String.join(" | ", commentSet);
                                        });
                            }

                            results.add(new String[]{qualifiedName, callerSig, chainComments});
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
