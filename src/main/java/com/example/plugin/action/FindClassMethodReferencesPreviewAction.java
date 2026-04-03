package com.example.plugin.action;

import com.example.plugin.util.CsvExporter;
import com.example.plugin.util.CsvPreviewUtil;
import com.example.plugin.util.ReferenceFinderUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 右键菜单动作：查找光标所在类全部方法的跨项目引用，结果在 Tool Window 中预览（纯文本 CSV）。
 */
public class FindClassMethodReferencesPreviewAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean visible = ReadAction.compute(() -> {
            PsiClass psiClass = getTargetClass(e);
            return psiClass != null && psiClass.getMethods().length > 0;
        });
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PsiClass psiClass = ReadAction.compute(() -> getTargetClass(e));
        if (psiClass == null) return;

        List<PsiMethod> methods = ReadAction.compute(() -> Arrays.asList(psiClass.getMethods()));
        if (methods.isEmpty()) {
            Messages.showInfoMessage(e.getProject(), "该类没有方法，无需查找。", "提示");
            return;
        }

        String className = ReadAction.compute(() ->
                psiClass.getQualifiedName() != null ? psiClass.getQualifiedName() : psiClass.getName());

        List<String[]> results = new ArrayList<>();

        boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(() ->
                results.addAll(ReferenceFinderUtil.findReferences(
                        methods,
                        ProgressManager.getInstance().getProgressIndicator())),
                "查找类方法引用：" + className, true, e.getProject());

        if (!ok) {
            Messages.showInfoMessage(e.getProject(),
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        String csv = CsvExporter.buildCsvString(results);
        CsvPreviewUtil.show(e.getProject(), csv, "class-refs (" + results.size() + " 条)");
    }

    @Nullable
    private PsiClass getTargetClass(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || !(psiFile instanceof PsiJavaFile)) return null;

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        if (psiClass instanceof PsiAnonymousClass) {
            psiClass = PsiTreeUtil.getParentOfType(psiClass, PsiClass.class, true);
        }
        return psiClass;
    }
}
