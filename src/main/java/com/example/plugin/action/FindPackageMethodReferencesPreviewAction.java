package com.example.plugin.action;

import com.example.plugin.util.CsvExporter;
import com.example.plugin.util.CsvPreviewUtil;
import com.example.plugin.util.ReferenceFinderUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.JavaDirectoryService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 项目视图右键菜单：查找包内所有类的全部方法被引用的位置，在 Tool Window 表格中预览。
 */
public class FindPackageMethodReferencesPreviewAction extends AnAction {

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

        List<PsiMethod> methods = ReadAction.compute(() -> {
            List<PsiMethod> all = new ArrayList<>();
            for (PsiClass cls : ReferenceFinderUtil.collectPackageClasses(pkg, e.getProject())) {
                all.addAll(Arrays.asList(cls.getMethods()));
            }
            return all;
        });
        String pkgName = ReadAction.compute(pkg::getQualifiedName);

        if (methods.isEmpty()) {
            Messages.showInfoMessage(e.getProject(), "该包下没有方法，无需查找。", "提示");
            return;
        }

        List<String[]> results = new ArrayList<>();
        boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(() ->
                results.addAll(ReferenceFinderUtil.findReferences(
                        methods, ProgressManager.getInstance().getProgressIndicator())),
                "查找包内类方法引用：" + pkgName, true, e.getProject());

        if (!ok) {
            Messages.showInfoMessage(e.getProject(),
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        String csv = CsvExporter.buildCsvString(results);
        CsvPreviewUtil.show(e.getProject(), csv, "pkg-method-refs (" + results.size() + " 条)");
    }

    @Nullable
    private PsiPackage getTargetPackage(@NotNull AnActionEvent e) {
        PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);
        if (!(element instanceof PsiDirectory)) return null;
        return JavaDirectoryService.getInstance().getPackage((PsiDirectory) element);
    }
}
