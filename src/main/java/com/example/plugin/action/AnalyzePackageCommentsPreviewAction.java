package com.example.plugin.action;

import com.example.plugin.util.CsvExporter;
import com.example.plugin.util.CsvPreviewUtil;
import com.example.plugin.util.ReferenceFinderUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 项目视图右键菜单：查找包内所有类及其方法的跨项目引用，并收集每条引用方法及其向上 4 层
 * 调用链中所有方法/类的 Javadoc 注释（无注释时回退到接口/父类），
 * 拼接后作为"引用链注释"列，在 Tool Window 中预览。
 */
public class AnalyzePackageCommentsPreviewAction extends AnAction {

    static final String HEADER = "源目标,目标项目,目标模块,引用位置,引用链注释\n";
    private static final int CHAIN_DEPTH = 4;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean visible = ReadAction.compute(() -> {
            PsiPackage pkg = FindPackageReferencesAction.getTargetPackage(e);
            if (pkg == null) return false;
            return !ReferenceFinderUtil.collectPackageClasses(pkg, e.getProject()).isEmpty();
        });
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PsiPackage pkg = ReadAction.compute(() -> FindPackageReferencesAction.getTargetPackage(e));
        if (pkg == null) return;

        List<PsiClass> classes = ReadAction.compute(() ->
                ReferenceFinderUtil.collectPackageClasses(pkg, e.getProject()));
        String pkgName = ReadAction.compute(pkg::getQualifiedName);

        List<String[]> results = new ArrayList<>();
        boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            results.addAll(ReferenceFinderUtil.findClassReferencesWithComments(
                    classes, CHAIN_DEPTH, indicator));
            List<PsiMethod> methods = ReadAction.compute(() -> {
                List<PsiMethod> all = new ArrayList<>();
                for (PsiClass cls : classes) all.addAll(Arrays.asList(cls.getMethods()));
                return all;
            });
            results.addAll(ReferenceFinderUtil.findReferencesWithComments(
                    methods, CHAIN_DEPTH, indicator));
        }, "收集包内引用链注释：" + pkgName, true, e.getProject());

        if (!ok) {
            Messages.showInfoMessage(e.getProject(),
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        String csv = CsvExporter.buildCsvString(results, HEADER);
        CsvPreviewUtil.show(e.getProject(), csv, "pkg-comments (" + results.size() + " 条)");
    }
}
