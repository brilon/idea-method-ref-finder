package com.example.plugin.action;

import com.example.plugin.util.CsvExporter;
import com.example.plugin.util.ReferenceFinderUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 项目视图右键菜单：查找包内所有类及其方法被引用的位置，导出 CSV。
 * 同时包含类引用（类名被使用的位置）和方法引用（方法被调用的位置）。
 */
public class FindPackageReferencesAction extends AnAction {

    static final String HEADER = "源目标,目标项目,目标模块,引用位置\n";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean visible = ReadAction.compute(() -> {
            PsiPackage pkg = getTargetPackage(e);
            if (pkg == null) return false;
            return !ReferenceFinderUtil.collectPackageClasses(pkg, e.getProject()).isEmpty();
        });
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PsiPackage pkg = ReadAction.compute(() -> getTargetPackage(e));
        if (pkg == null) return;

        List<PsiClass> classes = ReadAction.compute(() ->
                ReferenceFinderUtil.collectPackageClasses(pkg, e.getProject()));
        String pkgName = ReadAction.compute(pkg::getQualifiedName);

        List<String[]> results = new ArrayList<>();
        boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            results.addAll(ReferenceFinderUtil.findClassReferences(classes, indicator));
            List<PsiMethod> methods = ReadAction.compute(() -> {
                List<PsiMethod> all = new ArrayList<>();
                for (PsiClass cls : classes) all.addAll(Arrays.asList(cls.getMethods()));
                return all;
            });
            results.addAll(ReferenceFinderUtil.findReferences(methods, indicator));
        }, "查找包内引用：" + pkgName, true, e.getProject());

        if (!ok) {
            Messages.showInfoMessage(e.getProject(),
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        CsvExporter.export(results, "pkg-refs", HEADER, e.getProject());
    }

    @Nullable
    static PsiPackage getTargetPackage(@NotNull AnActionEvent e) {
        PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);
        if (element instanceof PsiPackage) return (PsiPackage) element;
        if (element instanceof PsiDirectory)
            return JavaDirectoryService.getInstance().getPackage((PsiDirectory) element);
        return null;
    }
}
