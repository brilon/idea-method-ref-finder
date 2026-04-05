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
 * 项目视图右键菜单：查找包内所有类及其方法被引用的位置，
 * 并对每条引用的调用方检查其是否为 RestController/Controller 接口方法，
 * 若是则输出完整 HTTP 接口路径，结果在 Tool Window 中预览。
 *
 * <p>输出列：源类, 目标项目, 目标模块, 引用位置, 是否Controller接口(是/否/-), 完整接口路径
 */
public class FindPackageWithControllerPreviewAction extends AnAction {

    static final String HEADER = "源类,目标项目,目标模块,引用位置,是否Controller接口,完整接口路径\n";

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
            results.addAll(ReferenceFinderUtil.findPackageReferencesWithControllerCheck(
                    classes, indicator));
        }, "查找包内引用(Controller检查)：" + pkgName, true, e.getProject());

        if (!ok) {
            Messages.showInfoMessage(e.getProject(),
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        String csv = CsvExporter.buildCsvString(results, HEADER);
        CsvPreviewUtil.show(e.getProject(), csv, "pkg-ctrl-refs (" + results.size() + " 条)");
    }
}
