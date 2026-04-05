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
 * 项目视图右键菜单：查找包内所有类及其方法被引用的位置，
 * 并分析每条引用方法再往上 4 层的调用链，标注"链路终止"或"链路继续"，结果在 Tool Window 中预览。
 */
public class AnalyzePackageChainPreviewAction extends AnAction {

    static final String HEADER = "源方法/类,直接引用数,可删除,引用状态,样本调用者\n";
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
            results.addAll(ReferenceFinderUtil.analyzeClassReferences(
                    classes, CHAIN_DEPTH, indicator));
            List<PsiMethod> methods = ReadAction.compute(() -> {
                List<PsiMethod> all = new ArrayList<>();
                for (PsiClass cls : classes) all.addAll(Arrays.asList(cls.getMethods()));
                return all;
            });
            results.addAll(ReferenceFinderUtil.analyzeMethodReferences(
                    methods, CHAIN_DEPTH, indicator));
        }, "分析包内引用链：" + pkgName, true, e.getProject());

        if (!ok) {
            Messages.showInfoMessage(e.getProject(),
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        String csv = CsvExporter.buildCsvString(results, HEADER);
        CsvPreviewUtil.show(e.getProject(), csv, "包引用分析 (" + results.size() + " 个条目)");
    }
}
