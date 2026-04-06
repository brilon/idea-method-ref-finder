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
import java.util.List;

/**
 * 项目视图右键菜单：查找包内所有类被引用的位置（仅类级别引用），
 * 并检查每条引用处的 import 是否可以清理，结果在 Tool Window 中预览。
 *
 * <p>输出列：源类, 目标项目, 目标模块, 引用位置, 能否清理import
 */
public class FindPackageClassRefsWithImportCheckPreviewAction extends AnAction {

    static final String HEADER = "源类,目标项目,目标模块,引用位置,能否清理import\n";

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
            results.addAll(ReferenceFinderUtil.findPackageClassRefsWithImportCheck(
                    classes, indicator));
        }, "查找包内类引用(import清理检查)：" + pkgName, true, e.getProject());

        if (!ok) {
            Messages.showInfoMessage(e.getProject(),
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        String csv = CsvExporter.buildCsvString(results, HEADER);
        CsvPreviewUtil.show(e.getProject(), csv, "pkg-import-check (" + results.size() + " 条)");
    }
}
