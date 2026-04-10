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

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * 查找包内所有类及其方法的引用，并检查每条引用位置的方法体文本是否含 "cache" 关键词（不区分大小写）。
     *
     * <p>输出 5 列：
     * <ol>
     *   <li>源类（全限定名或方法签名）</li>
     *   <li>目标项目</li>
     *   <li>目标模块</li>
     *   <li>引用位置（调用方方法签名或文件名）</li>
     *   <li>含cache关键词：{@code "是"} / {@code "否"} / {@code "-"}（非方法体引用时）</li>
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

    /** 构造一行 cache 检查结果：{源目标, 项目, 模块, 引用位置, 含cache关键词}。 */
    private static String[] buildCacheRow(String sourceLabel, String projectName, PsiReference ref) {
        return ReadAction.compute(() -> {
            PsiElement refElement = ref.getElement();
            Module module = ModuleUtilCore.findModuleForPsiElement(refElement);
            String moduleName = module != null ? module.getName() : "";
            PsiMethod callerMethod = PsiTreeUtil.getParentOfType(refElement, PsiMethod.class);
            String location = callerMethod != null
                    ? getMethodSignature(callerMethod)
                    : (refElement.getContainingFile() != null
                            ? refElement.getContainingFile().getName()
                            : "(unknown)");
            String cacheFlag = callerMethod == null ? "-"
                    : (callerMethod.getText().toLowerCase().contains("cache") ? "是" : "否");
            return new String[]{sourceLabel, projectName, moduleName, location, cacheFlag};
        });
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
     *   <li>引用位于某方法体内 → 检查该方法在项目范围内是否有引用；
     *       若无引用，再检查其上层接口方法及父类方法是否被引用</li>
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
            return isMethodOrSuperReferenced(callerMethod, project) ? "是" : "否";
        }

        PsiClass containingClass = ReadAction.compute(() ->
                PsiTreeUtil.getParentOfType(ref.getElement(), PsiClass.class));
        if (containingClass == null) return "-";

        return ReferencesSearch.search(containingClass, GlobalSearchScope.projectScope(project))
                .findFirst() != null ? "是" : "否";
    }

    /**
     * 检查方法自身是否被引用；若无引用，继续检查其所有上层接口方法和父类方法。
     *
     * <p>使用 {@link PsiMethod#findSuperMethods()} 获取直接上层方法（接口 + 父类均包含），
     * 只要任意一层存在引用即视为"被引用"。
     */
    private static boolean isMethodOrSuperReferenced(PsiMethod method, Project project) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        if (ReferencesSearch.search(method, scope).findFirst() != null) {
            return true;
        }
        // 检查上层接口方法 / 父类方法
        PsiMethod[] superMethods = ReadAction.compute(method::findSuperMethods);
        for (PsiMethod superMethod : superMethods) {
            if (ReferencesSearch.search(superMethod, scope).findFirst() != null) {
                return true;
            }
        }
        return false;
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
                                String callerReferenced = isMethodOrSuperReferenced(caller, project) ? "是" : "否";

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

    // ──────────────────────────────────────────────────────────────────────────
    // 输入签名 → 带出处标签的引用注释分析
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 根据输入的方法签名列表，在所有已打开项目中查找每个方法的引用，
     * 并为每条引用输出：源方法带出处标签注释 + 引用位置 + 引用链带出处标签注释（最多 chainDepth 层）。
     *
     * <p>注释格式示例：
     * <ul>
     *   <li>源方法注释列：{@code [自身] 方法注释 | [接口: IFoo#bar] 接口注释 | [父类: Base#bar] 父类注释}</li>
     *   <li>引用链注释列：{@code [L1: ServiceImpl#process] 注释 | [L1接口: IService#process] 注释 | [L2: Controller#exec] 注释}</li>
     * </ul>
     *
     * @param signatures 方法签名字符串列表，格式 {@code com.example.ClassName#methodName(param1Type, param2Type)}
     * @param chainDepth 引用链追溯层数上限（建议 3）
     * @param indicator  进度指示器，可为 null
     * @return 每条记录为 String[4]：{源方法签名, 源方法注释, 引用位置, 引用链注释}
     */
    public static List<String[]> findInputMethodRefsWithAnnotatedComments(
            List<String> signatures, int chainDepth, ProgressIndicator indicator) {
        List<String[]> results = new ArrayList<>();

        for (String rawSig : signatures) {
            String signature = rawSig.trim();
            if (signature.isEmpty()) continue;

            if (indicator != null) {
                indicator.setText("分析: " + signature);
                if (indicator.isCanceled()) break;
            }

            // ── 在所有已打开项目中查找对应 PsiMethod（支持多重载）──
            List<PsiMethod> sourceMethods = new ArrayList<>();
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                final String sig = signature;
                List<PsiMethod> found = ReadAction.compute(() -> findMethodsBySignature(sig, project));
                if (!found.isEmpty()) {
                    sourceMethods.addAll(found);
                    break; // 同一个类在多个项目中出现时只取第一个项目
                }
            }

            if (sourceMethods.isEmpty()) {
                results.add(new String[]{signature, "(未在任何已打开项目中找到该方法)", "", ""});
                continue;
            }

            for (PsiMethod sourceMethod : sourceMethods) {
                final PsiMethod finalSourceMethod = sourceMethod;

                // ── 源方法带出处标签注释 ──
                String sourceAnnotatedComments = ReadAction.compute(() -> {
                    List<String> entries = getAnnotatedMethodComments(finalSourceMethod, "");
                    return String.join(" | ", entries);
                });

                String sourceSignature = ReadAction.compute(() -> getMethodSignature(finalSourceMethod));
                String qualifiedClassName = ReadAction.compute(() -> {
                    PsiClass cls = finalSourceMethod.getContainingClass();
                    return cls != null ? cls.getQualifiedName() : null;
                });
                if (qualifiedClassName == null) continue;

                // ── 在各已打开项目中查找引用 ──
                for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                    if (indicator != null && indicator.isCanceled()) break;

                    PsiMethod targetMethod = ReadAction.compute(() -> {
                        PsiClass cls = JavaPsiFacade.getInstance(project)
                                .findClass(qualifiedClassName, GlobalSearchScope.allScope(project));
                        return cls == null ? null : findMatchingMethod(cls, finalSourceMethod);
                    });
                    if (targetMethod == null) continue;

                    ReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project))
                            .forEach(ref -> {
                                if (indicator != null && indicator.isCanceled()) return false;

                                PsiMethod callerMethod = ReadAction.compute(() ->
                                        PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));

                                String callerSig = ReadAction.compute(() -> {
                                    if (callerMethod != null) return getMethodSignature(callerMethod);
                                    PsiFile file = ref.getElement().getContainingFile();
                                    return file != null ? file.getName() : "(unknown)";
                                });

                                // ── 引用链带出处标签注释（最多 chainDepth 层）──
                                List<String> chainEntries = new ArrayList<>();
                                if (callerMethod != null) {
                                    collectAnnotatedChainComments(
                                            callerMethod, 1, chainDepth, new HashSet<>(), chainEntries);
                                } else {
                                    // 非方法体引用（import / 字段 / 类级别）→ 取所在类注释
                                    String classComment = ReadAction.compute(() -> {
                                        PsiClass cls = PsiTreeUtil.getParentOfType(
                                                ref.getElement(), PsiClass.class);
                                        if (cls == null) return "";
                                        String text = getClassComment(cls);
                                        return text.isEmpty() ? "" : "[类: " + (cls.getName() != null ? cls.getName() : "") + "] " + text;
                                    });
                                    if (!classComment.isEmpty()) chainEntries.add(classComment);
                                }
                                String chainComments = String.join(" | ", chainEntries);

                                results.add(new String[]{sourceSignature, sourceAnnotatedComments, callerSig, chainComments});
                                return true;
                            });
                }
            }
        }
        return results;
    }

    /**
     * 递归收集方法（及其接口/父类方法）的带出处标签注释，向上追溯至 maxLevel 层。
     *
     * <p>每层注释格式：{@code [Ln: ClassName#method] 注释 | [Ln接口: IFace#m] 注释 | [Ln父类: Base#m] 注释}
     *
     * @param method     当前层方法
     * @param level      当前层级（从 1 开始，1 = 直接引用方）
     * @param maxLevel   最大追溯层数
     * @param visited    已访问方法签名集合（避免循环）
     * @param result     收集到的注释条目列表（按追溯顺序追加）
     */
    private static void collectAnnotatedChainComments(
            PsiMethod method, int level, int maxLevel,
            Set<String> visited, List<String> result) {

        if (level > maxLevel) return;

        String sig = ReadAction.compute(() -> getMethodSignature(method));
        if (visited.contains(sig)) return;
        visited.add(sig);

        // 获取本层方法的带标签注释
        String prefix = "L" + level;
        List<String> ownEntries = ReadAction.compute(() -> getAnnotatedMethodComments(method, prefix));
        result.addAll(ownEntries);

        // 若未到上限，继续向上追溯调用方
        if (level < maxLevel) {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                PsiMethod target = ReadAction.compute(() -> {
                    PsiClass cls = method.getContainingClass();
                    if (cls == null) return null;
                    String qn = cls.getQualifiedName();
                    if (qn == null) return null;
                    PsiClass targetCls = JavaPsiFacade.getInstance(project)
                            .findClass(qn, GlobalSearchScope.allScope(project));
                    return targetCls == null ? null : findMatchingMethod(targetCls, method);
                });
                if (target == null) continue;

                List<PsiMethod> callers = new ArrayList<>();
                ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
                        .forEach(ref -> {
                            PsiMethod caller = ReadAction.compute(() ->
                                    PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class));
                            if (caller != null) callers.add(caller);
                            return true;
                        });

                for (PsiMethod caller : callers) {
                    collectAnnotatedChainComments(caller, level + 1, maxLevel, new HashSet<>(visited), result);
                }
            }
        }
    }

    /**
     * 获取方法的带出处标签注释列表，涵盖类/接口级别注释和方法级别注释。
     *
     * <p>输出顺序（由大到小）：
     * <ol>
     *   <li>所在类（或接口）自身的 Javadoc</li>
     *   <li>所在类实现的各接口的 Javadoc</li>
     *   <li>所在类的父类 Javadoc（排除 {@code java.lang.Object}）</li>
     *   <li>方法自身 Javadoc</li>
     *   <li>方法在接口中的 Javadoc</li>
     *   <li>方法在父类中的 Javadoc</li>
     * </ol>
     *
     * <p>调用方必须持有 ReadAction。
     *
     * @param method      目标方法
     * @param levelPrefix 层级前缀，空串表示源方法层，"L1"/"L2"/"L3" 表示引用链各层
     */
    private static List<String> getAnnotatedMethodComments(PsiMethod method, String levelPrefix) {
        List<String> entries = new ArrayList<>();
        String p = levelPrefix;

        PsiClass containingClass = method.getContainingClass();

        // ── 1. 类/接口级别注释 ──
        if (containingClass != null) {
            // 所在类（或接口）自身
            String classDoc = getClassComment(containingClass);
            if (!classDoc.isEmpty()) {
                String kind = containingClass.isInterface() ? "所在接口" : "所在类";
                String name = containingClass.getName() != null ? containingClass.getName() : "";
                entries.add("[" + p + kind + ": " + name + "] " + classDoc);
            }
            // 所在类实现的各接口
            for (PsiClass iface : containingClass.getInterfaces()) {
                String ifaceDoc = getClassComment(iface);
                if (!ifaceDoc.isEmpty()) {
                    String name = iface.getName() != null ? iface.getName() : "";
                    entries.add("[" + p + "所在接口: " + name + "] " + ifaceDoc);
                }
            }
            // 父类（排除 java.lang.Object）
            PsiClass superCls = containingClass.getSuperClass();
            if (superCls != null && !"java.lang.Object".equals(superCls.getQualifiedName())) {
                String superDoc = getClassComment(superCls);
                if (!superDoc.isEmpty()) {
                    String name = superCls.getName() != null ? superCls.getName() : "";
                    entries.add("[" + p + "所在父类: " + name + "] " + superDoc);
                }
            }
        }

        // ── 2. 方法级别注释 ──
        String selfLabel = p.isEmpty() ? "[自身]" : "[" + p + ": " + shortMethodSig(method) + "]";
        PsiDocComment doc = method.getDocComment();
        if (doc != null) {
            String text = extractDocText(doc);
            if (!text.isEmpty()) entries.add(selfLabel + " " + text);
        }

        for (PsiMethod superMethod : method.findSuperMethods()) {
            PsiDocComment superDoc = superMethod.getDocComment();
            if (superDoc == null) continue;
            String text = extractDocText(superDoc);
            if (text.isEmpty()) continue;
            PsiClass superClass = superMethod.getContainingClass();
            boolean isInterface = superClass != null && superClass.isInterface();
            String kind = isInterface ? "接口" : "父类";
            entries.add("[" + p + kind + ": " + shortMethodSig(superMethod) + "] " + text);
        }

        return entries;
    }

    /**
     * 返回方法的短签名 {@code ClassName#methodName}（不含包名和参数），用于注释标签。
     *
     * <p>调用方必须持有 ReadAction。
     */
    private static String shortMethodSig(PsiMethod method) {
        PsiClass cls = method.getContainingClass();
        String className = (cls != null && cls.getName() != null) ? cls.getName() : "";
        return className + "#" + method.getName();
    }

    /**
     * 根据签名字符串在指定项目中查找匹配的 PsiMethod 列表。
     *
     * <p>支持两种格式：
     * <ul>
     *   <li>带参数：{@code com.example.ClassName#methodName(param1Type, param2Type)} — 精确匹配</li>
     *   <li>无参数：{@code com.example.ClassName#methodName} — 按方法名匹配，返回所有重载</li>
     * </ul>
     *
     * <p>调用方必须持有 ReadAction。
     *
     * @return 匹配的方法列表，未找到时返回空列表
     */
    private static List<PsiMethod> findMethodsBySignature(String signature, Project project) {
        int hashIdx = signature.indexOf('#');
        if (hashIdx < 0) return Collections.emptyList();
        String className = signature.substring(0, hashIdx).trim();
        String methodPart = signature.substring(hashIdx + 1).trim();

        PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.allScope(project));
        if (psiClass == null) return Collections.emptyList();

        int parenStart = methodPart.indexOf('(');
        int parenEnd   = methodPart.lastIndexOf(')');

        // 无括号：按方法名匹配所有重载
        if (parenStart < 0 || parenEnd < 0 || parenEnd < parenStart) {
            String methodName = methodPart.trim();
            List<PsiMethod> matched = new ArrayList<>();
            for (PsiMethod m : psiClass.getMethods()) {
                if (m.getName().equals(methodName)) matched.add(m);
            }
            return matched;
        }

        // 有括号：精确匹配（方法名 + 参数类型）
        String methodName = methodPart.substring(0, parenStart).trim();
        String paramsStr  = methodPart.substring(parenStart + 1, parenEnd).trim();
        String[] paramTypes = paramsStr.isEmpty() ? new String[0] : paramsStr.split(",\\s*");

        outer:
        for (PsiMethod method : psiClass.getMethods()) {
            if (!method.getName().equals(methodName)) continue;
            PsiParameter[] params = method.getParameterList().getParameters();
            if (params.length != paramTypes.length) continue;
            for (int i = 0; i < params.length; i++) {
                if (!typeMatches(params[i].getType().getCanonicalText(), paramTypes[i].trim()))
                    continue outer;
            }
            return Collections.singletonList(method);
        }
        return Collections.emptyList();
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
                if (!typeMatches(sourceType, candidateType)) {
                    match = false;
                    break;
                }
            }
            if (match) return candidate;
        }
        return null;
    }

    /**
     * 比较两个类型文本是否匹配，支持泛型擦除匹配。
     *
     * <p>当精确匹配失败时，擦除双方的泛型参数（去掉 {@code <...>} 部分）后再比较。
     * 例如 {@code "java.util.List"} 可匹配 {@code "java.util.List<java.lang.String>"}。
     */
    private static boolean typeMatches(String type1, String type2) {
        if (type1.equals(type2)) return true;
        return eraseGenerics(type1).equals(eraseGenerics(type2));
    }

    /** 擦除类型文本中的泛型参数：{@code "java.util.Map<K, V>"} → {@code "java.util.Map"}。 */
    private static String eraseGenerics(String type) {
        int lt = type.indexOf('<');
        return lt >= 0 ? type.substring(0, lt) : type;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 追溯入口链路：从输入方法向上追踪至最终 API / KafkaListener / Scheduled 入口
    // ──────────────────────────────────────────────────────────────────────────

    /** 追溯结果容器：动态表头 + 数据行。 */
    public static class TraceResult {
        public final String header;
        public final List<String[]> rows;

        public TraceResult(String header, List<String[]> rows) {
            this.header = header;
            this.rows = rows;
        }
    }

    /** 一条完整调用链（内部使用）。 */
    private static class ChainResult {
        final List<String> nodes;           // 中间节点签名（不含源方法和终端）
        final String terminalSig;           // 终端方法签名
        final String terminalModule;        // 终端所属模块
        final String terminalType;          // RestController / KafkaListener / Scheduled / 无引用 / 深度上限
        final String endpointPath;          // "POST /path" / topic / cron / "-"
        final String sourceModuleEndpoint;  // 源方法所属模块的 RestController 接口路径
        final String crossServiceApiName;   // 跨服务调用使用的 API 名称

        ChainResult(List<String> nodes, String terminalSig, String terminalModule,
                    String terminalType, String endpointPath,
                    String sourceModuleEndpoint, String crossServiceApiName) {
            this.nodes = nodes;
            this.terminalSig = terminalSig;
            this.terminalModule = terminalModule;
            this.terminalType = terminalType;
            this.endpointPath = endpointPath;
            this.sourceModuleEndpoint = sourceModuleEndpoint != null ? sourceModuleEndpoint : "-";
            this.crossServiceApiName = crossServiceApiName != null ? crossServiceApiName : "-";
        }
    }

    /** 跨服务调用方信息：PsiMethod + 匹配的 API 名称。 */
    private static class CrossServiceCaller {
        final PsiMethod method;
        final String apiName;

        CrossServiceCaller(PsiMethod method, String apiName) {
            this.method = method;
            this.apiName = apiName;
        }
    }

    /** 单服务内最大追溯层数。 */
    private static final int MAX_INTRA_DEPTH = 20;
    /** 单个输入方法的最大链路数，防止组合爆炸。 */
    private static final int MAX_CHAINS_PER_INPUT = 500;
    /** 单个方法最大展开调用方数量。 */
    private static final int MAX_CALLERS_PER_METHOD = 50;

    /** properties key 正则：restConfig.restMap.{apiname}.requestUrl = ... */
    private static final Pattern REST_URL_KEY_PATTERN =
            Pattern.compile("restConfig\\.restMap\\.([^.]+)\\.requestUrl\\s*=\\s*(.+)");

    /** 从 URL 中提取路径部分（去掉 http(s)://host(:port) 前缀）。 */
    private static final Pattern URL_PATH_PATTERN =
            Pattern.compile("https?://[^/]+(/.*)");

    // ── 终端分类辅助 ──────────────────────────────────────────────────────────

    /**
     * 检查方法是否有 {@code @KafkaListener} 注解，若有则返回 topics 字符串。
     *
     * <p>调用方必须持有 ReadAction。
     *
     * @return topics 字符串，无注解时返回 null
     */
    private static String extractKafkaTopics(PsiMethod method) {
        for (PsiAnnotation ann : method.getAnnotations()) {
            if ("KafkaListener".equals(shortName(ann))) {
                PsiAnnotationMemberValue val = ann.findAttributeValue("topics");
                if (val != null) return stripQuotes(val.getText());
                val = ann.findAttributeValue("value");
                if (val != null) return stripQuotes(val.getText());
                return "(topics未指定)";
            }
        }
        return null;
    }

    /**
     * 检查方法是否有 {@code @Scheduled} 注解，若有则返回调度表达式。
     *
     * <p>调用方必须持有 ReadAction。
     *
     * @return 调度表达式字符串，无注解时返回 null
     */
    private static String extractScheduledExpression(PsiMethod method) {
        for (PsiAnnotation ann : method.getAnnotations()) {
            if ("Scheduled".equals(shortName(ann))) {
                for (String attr : new String[]{"cron", "fixedRate", "fixedDelay", "fixedRateString", "fixedDelayString"}) {
                    PsiAnnotationMemberValue val = ann.findAttributeValue(attr);
                    if (val != null) {
                        String text = stripQuotes(val.getText());
                        if (!text.isEmpty()) return attr + "=" + text;
                    }
                }
                return "(表达式未指定)";
            }
        }
        return null;
    }

    /**
     * 解析 PsiExpression 为字符串值（支持字符串字面量和常量引用）。
     *
     * <p>调用方必须持有 ReadAction。
     *
     * @return 解析出的字符串值，无法解析时返回 null
     */
    private static String resolveStringValue(PsiExpression expr) {
        if (expr instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) expr).getValue();
            return value instanceof String ? (String) value : null;
        }
        if (expr instanceof PsiReferenceExpression) {
            PsiElement resolved = ((PsiReferenceExpression) expr).resolve();
            if (resolved instanceof PsiField) {
                PsiField field = (PsiField) resolved;
                PsiExpression initializer = field.getInitializer();
                if (initializer instanceof PsiLiteralExpression) {
                    Object value = ((PsiLiteralExpression) initializer).getValue();
                    return value instanceof String ? (String) value : null;
                }
            }
        }
        return null;
    }

    // ── 跨服务解析 ──────────────────────────────────────────────────────────

    /**
     * 从 HTTP 接口路径（如 "POST /edm/private/getConfig"）中提取纯路径部分。
     */
    private static String extractPathFromEndpointInfo(String endpointInfo) {
        if (endpointInfo == null || "-".equals(endpointInfo)) return null;
        // endpointInfo 格式为 "POST /path" 或 "GET /path"
        int space = endpointInfo.indexOf(' ');
        return space >= 0 ? endpointInfo.substring(space + 1).trim() : endpointInfo.trim();
    }

    /**
     * 当追溯到 RestController 端点时，在所有已打开项目的 .properties 文件中
     * 查找对应的 restConfig.restMap.*.url 配置，定位调用端的 exchangeInApp 调用点。
     *
     * @param endpointPath 端点路径，如 "/private/getConfig"
     * @param indicator    进度指示器
     * @return 调用 exchangeInApp 的 PsiMethod 列表
     */
    private static List<CrossServiceCaller> findCrossServiceCallers(
            String endpointPath, ProgressIndicator indicator) {
        if (endpointPath == null || endpointPath.isEmpty()) return Collections.emptyList();

        List<CrossServiceCaller> allCallers = new ArrayList<>();

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (indicator != null && indicator.isCanceled()) break;

            // 扫描项目中的 .properties 文件，查找 URL 配置
            List<String> matchedApiNames = ReadAction.compute(() -> {
                List<String> apiNames = new ArrayList<>();
                Collection<VirtualFile> propFiles = FilenameIndex.getAllFilesByExt(
                        project, "properties", GlobalSearchScope.projectScope(project));
                for (VirtualFile vf : propFiles) {
                    try {
                        String content = new String(vf.contentsToByteArray(),
                                vf.getCharset() != null ? vf.getCharset() : StandardCharsets.UTF_8);
                        for (String line : content.split("\\r?\\n")) {
                            Matcher m = REST_URL_KEY_PATTERN.matcher(line.trim());
                            if (!m.matches()) continue;
                            String apiName = m.group(1);
                            String urlValue = m.group(2).trim();
                            // 从 URL 值中提取路径（去掉 http://host 前缀和 ?query 后缀）
                            Matcher pathMatcher = URL_PATH_PATTERN.matcher(urlValue);
                            String urlPath = pathMatcher.matches()
                                    ? pathMatcher.group(1)
                                    : urlValue; // 无 http 前缀时当作纯路径
                            // 去掉查询参数
                            int qIdx = urlPath.indexOf('?');
                            if (qIdx >= 0) urlPath = urlPath.substring(0, qIdx);
                            // 标准化后做尾部匹配
                            String normalizedUrl = normalizePath(urlPath);
                            String normalizedEndpoint = normalizePath(endpointPath);
                            if (normalizedUrl.endsWith(normalizedEndpoint)) {
                                apiNames.add(apiName);
                            }
                        }
                    } catch (Exception ignored) {
                        // 读取失败的文件跳过
                    }
                }
                return apiNames;
            });

            // 对每个 apiName，查找 exchangeInApp 调用点
            for (String apiName : matchedApiNames) {
                if (indicator != null && indicator.isCanceled()) break;
                for (PsiMethod caller : findExchangeInAppCallers(apiName, project)) {
                    allCallers.add(new CrossServiceCaller(caller, apiName));
                }
            }
        }

        return allCallers;
    }

    /**
     * 在指定项目中查找 {@code exchangeInApp("apiName", ...)} 的调用点。
     *
     * @param apiName 接口名称
     * @param project 目标项目
     * @return 包含该调用的方法列表
     */
    private static List<PsiMethod> findExchangeInAppCallers(String apiName, Project project) {
        List<PsiMethod> callers = new ArrayList<>();

        ReadAction.run(() -> {
            PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
            helper.processElementsWithWord(
                    (element, offsetInElement) -> {
                        // 向上找到方法调用表达式
                        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(
                                element, PsiMethodCallExpression.class);
                        if (call == null) return true;

                        // 验证方法名
                        String methodName = call.getMethodExpression().getReferenceName();
                        if (!"exchangeInApp".equals(methodName)) return true;

                        // 提取第一个参数，解析为字符串值
                        PsiExpression[] args = call.getArgumentList().getExpressions();
                        if (args.length == 0) return true;

                        String resolvedName = resolveStringValue(args[0]);
                        if (!apiName.equals(resolvedName)) return true;

                        // 匹配！获取包含方法
                        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(
                                call, PsiMethod.class);
                        if (containingMethod != null) {
                            callers.add(containingMethod);
                        }
                        return true;
                    },
                    GlobalSearchScope.projectScope(project),
                    "exchangeInApp",
                    UsageSearchContext.IN_CODE,
                    true);
        });

        return callers;
    }

    // ── 核心追溯逻辑 ──────────────────────────────────────────────────────────

    /**
     * 在所有已打开项目中查找 method 的所有直接调用方（仅方法体内调用，忽略 import/字段等）。
     *
     * <p>三层回退策略：
     * <ol>
     *   <li>直接搜索方法自身的引用</li>
     *   <li>搜索<b>项目级</b>父类/接口方法的引用（排除 JDK / Spring 等框架接口）</li>
     *   <li>若方法实现了框架接口（Callable / Runnable 等），搜索所在类的引用（找到 submit/execute 调用方）</li>
     * </ol>
     *
     * @return 调用方 PsiMethod 列表
     */
    private static List<PsiMethod> findAllCallersAcrossProjects(
            PsiMethod method, ProgressIndicator indicator) {
        List<PsiMethod> callers = new ArrayList<>();

        String[] methodInfo = ReadAction.compute(() -> {
            PsiClass cls = method.getContainingClass();
            if (cls == null) return null;
            String qn = cls.getQualifiedName();
            if (qn == null) return null;
            return new String[]{qn};
        });
        if (methodInfo == null) return callers;

        // ── 第 1 层：直接搜索方法自身的引用 ──
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (indicator != null && indicator.isCanceled()) break;

            PsiMethod target = ReadAction.compute(() -> {
                JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                PsiClass targetClass = facade.findClass(
                        methodInfo[0], GlobalSearchScope.allScope(project));
                return targetClass == null ? null : findMatchingMethod(targetClass, method);
            });
            if (target == null) continue;

            searchMethodCallers(target, project, callers, indicator);
        }
        if (!callers.isEmpty()) return callers;

        // ── 第 2 层：搜索项目级父类/接口方法的引用（跳过框架/JDK 接口）──
        PsiMethod[] superMethods = ReadAction.compute(method::findSuperMethods);
        boolean hasFrameworkSuper = false;

        for (PsiMethod superMethod : superMethods) {
            if (indicator != null && indicator.isCanceled()) break;

            // 判断父方法所在类是否为项目源码（非框架/库）
            if (!isProjectClass(superMethod)) {
                hasFrameworkSuper = true;
                continue; // 跳过框架接口方法
            }

            if (!callers.isEmpty()) break;

            String[] superInfo = ReadAction.compute(() -> {
                PsiClass cls = superMethod.getContainingClass();
                if (cls == null) return null;
                String qn = cls.getQualifiedName();
                if (qn == null) return null;
                return new String[]{qn};
            });
            if (superInfo == null) continue;

            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (indicator != null && indicator.isCanceled()) break;

                PsiMethod target = ReadAction.compute(() -> {
                    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                    PsiClass targetClass = facade.findClass(
                            superInfo[0], GlobalSearchScope.allScope(project));
                    return targetClass == null ? null : findMatchingMethod(targetClass, superMethod);
                });
                if (target == null) continue;

                searchMethodCallers(target, project, callers, indicator);
            }
        }
        if (!callers.isEmpty()) return callers;

        // ── 第 3 层：实现了框架接口（Callable/Runnable 等）→ 搜索所在类的引用 ──
        // 场景：MyTask implements Callable → executor.submit(new MyTask()) → 找到 submit 所在方法
        if (hasFrameworkSuper) {
            PsiClass containingClass = ReadAction.compute(method::getContainingClass);
            if (containingClass != null && !(containingClass instanceof PsiAnonymousClass)) {
                searchClassInstantiationCallers(containingClass, callers, indicator);
            }
        }

        return callers;
    }

    /**
     * 判断方法所在类是否为项目源码（非框架/JDK/第三方库）。
     *
     * <p>在所有已打开项目的 {@code projectScope}（仅源码）中查找该类，
     * 找到则为项目类，仅在 {@code allScope}（含库）中找到则为框架类。
     */
    private static boolean isProjectClass(PsiMethod method) {
        String qn = ReadAction.compute(() -> {
            PsiClass cls = method.getContainingClass();
            return cls != null ? cls.getQualifiedName() : null;
        });
        if (qn == null) return false;

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            PsiClass found = ReadAction.compute(() ->
                    JavaPsiFacade.getInstance(project)
                            .findClass(qn, GlobalSearchScope.projectScope(project)));
            if (found != null) return true;
        }
        return false;
    }

    /**
     * 搜索类的引用（构造 / new / 类型引用），找到实例化该类的外层方法作为调用方。
     *
     * <p>用于处理 Callable/Runnable 等任务类：{@code executor.submit(new MyTask())} 场景。
     */
    private static void searchClassInstantiationCallers(
            PsiClass taskClass, List<PsiMethod> callers, ProgressIndicator indicator) {
        String qn = ReadAction.compute(taskClass::getQualifiedName);
        if (qn == null) return;

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (indicator != null && indicator.isCanceled()) break;

            PsiClass target = ReadAction.compute(() ->
                    JavaPsiFacade.getInstance(project)
                            .findClass(qn, GlobalSearchScope.allScope(project)));
            if (target == null) continue;

            ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
                    .forEach(ref -> {
                        if (indicator != null && indicator.isCanceled()) return false;
                        PsiMethod caller = ReadAction.compute(() ->
                                resolveOuterCaller(ref.getElement()));
                        if (caller != null) {
                            callers.add(caller);
                        }
                        return callers.size() < MAX_CALLERS_PER_METHOD;
                    });
        }
    }

    /** 在指定项目中搜索方法的调用方，结果追加到 callers 列表。 */
    private static void searchMethodCallers(PsiMethod target, Project project,
                                            List<PsiMethod> callers, ProgressIndicator indicator) {
        ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
                .forEach(ref -> {
                    if (indicator != null && indicator.isCanceled()) return false;
                    PsiMethod caller = ReadAction.compute(() ->
                            resolveOuterCaller(ref.getElement()));
                    if (caller != null) {
                        callers.add(caller);
                    }
                    return callers.size() < MAX_CALLERS_PER_METHOD;
                });
    }

    /**
     * 从引用元素找到真正的外部调用方法。
     *
     * <p>如果引用位于匿名类（如 {@code new Callable<T>() { call() { ... } }}）内部的方法中，
     * 则向上穿透匿名类，返回真正包含该匿名类的外部方法。
     *
     * <p>调用方必须持有 ReadAction。
     */
    private static PsiMethod resolveOuterCaller(PsiElement element) {
        PsiMethod caller = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        // 向上穿透匿名类：如果 caller 所在类是匿名类，继续向外找
        while (caller != null) {
            PsiClass containingClass = caller.getContainingClass();
            if (containingClass instanceof PsiAnonymousClass) {
                caller = PsiTreeUtil.getParentOfType(containingClass, PsiMethod.class);
            } else {
                break;
            }
        }
        return caller;
    }

    /**
     * 递归向上追溯调用链，直到到达终端入口节点。
     *
     * @param method          当前方法
     * @param chain           已积累的中间节点签名列表
     * @param serviceHops     已跨越的微服务数
     * @param maxServiceHops  最大跨服务跳数
     * @param intraDepth      当前服务内已追溯层数
     * @param visited         已访问方法签名集合（防环）
     * @param inputSig        原始输入方法签名
     * @param inputModule     原始输入方法所属模块
     * @param results         收集到的完整链路结果
     * @param indicator       进度指示器
     */
    private static void traceUpward(
            PsiMethod method, List<String> chain,
            int serviceHops, int maxServiceHops,
            int intraDepth,
            Set<String> visited,
            String inputSig, String inputModule,
            String sourceModuleEndpoint, String crossServiceApiName,
            List<ChainResult> results,
            ProgressIndicator indicator) {

        if (indicator != null && indicator.isCanceled()) return;
        if (results.size() >= MAX_CHAINS_PER_INPUT) return;

        String sig = ReadAction.compute(() -> getMethodSignature(method));
        if (visited.contains(sig)) return;
        visited.add(sig);

        String moduleName = ReadAction.compute(() -> {
            Module mod = ModuleUtilCore.findModuleForPsiElement(method);
            return mod != null ? mod.getName() : "";
        });

        // ── 检查 KafkaListener / Scheduled 终端（始终为终端，不再向上）──
        String kafkaTopics = ReadAction.compute(() -> extractKafkaTopics(method));
        if (kafkaTopics != null) {
            results.add(new ChainResult(new ArrayList<>(chain), sig, moduleName,
                    "KafkaListener", kafkaTopics, sourceModuleEndpoint, crossServiceApiName));
            return;
        }

        String scheduledExpr = ReadAction.compute(() -> extractScheduledExpression(method));
        if (scheduledExpr != null) {
            results.add(new ChainResult(new ArrayList<>(chain), sig, moduleName,
                    "Scheduled", scheduledExpr, sourceModuleEndpoint, crossServiceApiName));
            return;
        }

        // ── 深度上限 ──
        if (intraDepth >= MAX_INTRA_DEPTH) {
            results.add(new ChainResult(new ArrayList<>(chain), sig, moduleName,
                    "深度上限", "-", sourceModuleEndpoint, crossServiceApiName));
            return;
        }

        // ── 查找 Java 调用方 ──
        List<PsiMethod> callers = findAllCallersAcrossProjects(method, indicator);

        if (callers.isEmpty()) {
            // 无 Java 调用方 → 检查是否为 RestController 端点
            String[] endpointInfo = ReadAction.compute(() -> getEndpointInfo(method));
            if ("是".equals(endpointInfo[0])) {
                // 是 RestController 端点
                if (serviceHops < maxServiceHops) {
                    // 尝试跨服务解析
                    String path = extractPathFromEndpointInfo(endpointInfo[1]);
                    List<CrossServiceCaller> crossCallers = findCrossServiceCallers(path, indicator);
                    if (!crossCallers.isEmpty()) {
                        // 记录首次跨服务边界信息（后续跳数不覆盖）
                        String smEndpoint = sourceModuleEndpoint != null
                                ? sourceModuleEndpoint : endpointInfo[1];
                        // 将当前端点加入链路，继续在另一个服务追溯
                        List<String> newChain = new ArrayList<>(chain);
                        newChain.add(sig);
                        for (CrossServiceCaller cc : crossCallers) {
                            if (results.size() >= MAX_CHAINS_PER_INPUT) break;
                            String apiName = crossServiceApiName != null
                                    ? crossServiceApiName : cc.apiName;
                            traceUpward(cc.method, newChain,
                                    serviceHops + 1, maxServiceHops,
                                    0, // 重置服务内深度
                                    new HashSet<>(visited),
                                    inputSig, inputModule,
                                    smEndpoint, apiName,
                                    results, indicator);
                        }
                        return;
                    }
                }
                // 无跨服务调用方 或 已达跳数上限 → 这就是最终 API 入口
                results.add(new ChainResult(new ArrayList<>(chain), sig, moduleName,
                        "RestController", endpointInfo[1], sourceModuleEndpoint, crossServiceApiName));
            } else {
                // 不是 RestController → 无引用终端
                results.add(new ChainResult(new ArrayList<>(chain), sig, moduleName,
                        "无引用", "-", sourceModuleEndpoint, crossServiceApiName));
            }
            return;
        }

        // ── 有 Java 调用方 → 对每个调用方继续向上 ──
        for (PsiMethod caller : callers) {
            if (results.size() >= MAX_CHAINS_PER_INPUT) break;
            List<String> newChain = new ArrayList<>(chain);
            newChain.add(sig);
            traceUpward(caller, newChain,
                    serviceHops, maxServiceHops,
                    intraDepth + 1,
                    new HashSet<>(visited),
                    inputSig, inputModule,
                    sourceModuleEndpoint, crossServiceApiName,
                    results, indicator);
        }
    }

    /**
     * 从输入的方法签名列表出发，向上追溯调用链直至终端入口节点
     * （RestController / KafkaListener / Scheduled / 无引用），
     * 支持跨微服务追踪（通过 .properties 文件的 restConfig.restMap.*.url 配置）。
     *
     * @param signatures     方法签名列表
     * @param maxServiceHops 跨服务最大跳数（建议 3）
     * @param indicator      进度指示器
     * @return 追溯结果（动态表头 + 数据行）
     */
    public static TraceResult traceUpwardToEndpoints(
            List<String> signatures, int maxServiceHops, ProgressIndicator indicator) {

        // 收集所有输入方法的链路结果：inputSig → (inputModule, List<ChainResult>)
        Map<String, String> inputModules = new LinkedHashMap<>();
        Map<String, List<ChainResult>> allChains = new LinkedHashMap<>();

        for (String rawSig : signatures) {
            String signature = rawSig.trim();
            if (signature.isEmpty()) continue;
            if (indicator != null) {
                indicator.setText("追溯: " + signature);
                if (indicator.isCanceled()) break;
            }

            // 在所有已打开项目中查找该方法
            List<PsiMethod> sourceMethods = new ArrayList<>();
            String sourceModule = "";
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                List<PsiMethod> found = ReadAction.compute(() ->
                        findMethodsBySignature(signature, project));
                if (!found.isEmpty()) {
                    sourceMethods.addAll(found);
                    final PsiMethod firstMethod = found.get(0);
                    sourceModule = ReadAction.compute(() -> {
                        Module mod = ModuleUtilCore.findModuleForPsiElement(firstMethod);
                        return mod != null ? mod.getName() : "";
                    });
                    break;
                }
            }

            inputModules.put(signature, sourceModule);

            if (sourceMethods.isEmpty()) {
                // 未找到方法
                List<ChainResult> empty = new ArrayList<>();
                empty.add(new ChainResult(Collections.emptyList(),
                        "(未找到该方法)", "", "未找到", "-", null, null));
                allChains.put(signature, empty);
                continue;
            }

            List<ChainResult> chains = new ArrayList<>();
            for (PsiMethod sourceMethod : sourceMethods) {
                if (indicator != null && indicator.isCanceled()) break;
                traceUpward(sourceMethod, new ArrayList<>(),
                        0, maxServiceHops, 0,
                        new HashSet<>(),
                        signature, sourceModule,
                        null, null,
                        chains, indicator);
            }

            if (chains.isEmpty()) {
                chains.add(new ChainResult(Collections.emptyList(),
                        signature, sourceModule, "无引用", "-", null, null));
            }

            allChains.put(signature, chains);
        }

        // ── 计算最大节点列数 ──
        int maxNodeCount = 0;
        for (List<ChainResult> chains : allChains.values()) {
            for (ChainResult cr : chains) {
                maxNodeCount = Math.max(maxNodeCount, cr.nodes.size());
            }
        }

        // ── 构造动态表头 ──
        StringBuilder header = new StringBuilder(
                "源方法,源方法所属模块,源模块接口路径,API名称,最终目标,所属模块,入口类型,接口路径");
        for (int i = 1; i <= maxNodeCount; i++) {
            header.append(",节点").append(i);
        }
        header.append("\n");

        // ── 构造数据行 ──
        List<String[]> rows = new ArrayList<>();
        int fixedCols = 8;
        int totalCols = fixedCols + maxNodeCount;

        for (Map.Entry<String, List<ChainResult>> entry : allChains.entrySet()) {
            String inputSig = entry.getKey();
            String inputModule = inputModules.get(inputSig);
            for (ChainResult cr : entry.getValue()) {
                String[] row = new String[totalCols];
                row[0] = inputSig;
                row[1] = inputModule != null ? inputModule : "";
                row[2] = cr.sourceModuleEndpoint;
                row[3] = cr.crossServiceApiName;
                row[4] = cr.terminalSig;
                row[5] = cr.terminalModule;
                row[6] = cr.terminalType;
                row[7] = cr.endpointPath;
                for (int i = 0; i < maxNodeCount; i++) {
                    row[fixedCols + i] = i < cr.nodes.size() ? cr.nodes.get(i) : "";
                }
                rows.add(row);
            }
        }

        return new TraceResult(header.toString(), rows);
    }
}
