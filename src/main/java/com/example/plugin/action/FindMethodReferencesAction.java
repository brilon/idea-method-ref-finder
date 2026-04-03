package com.example.plugin.action;

import com.example.plugin.util.CsvExporter;
import com.example.plugin.util.ReferenceFinderUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 右键菜单动作：在光标所在方法上，查找该方法在所有已打开项目中的引用，导出 GBK CSV。
 *
 * <p>仅当光标位于 Java 方法声明内时，菜单项才可见。
 */
public class FindMethodReferencesAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(getTargetMethod(e) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PsiMethod method = getTargetMethod(e);
        if (method == null) return;

        String signature = ReferenceFinderUtil.getMethodSignature(method);
        List<String[]> results = new ArrayList<>();

        boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            results.addAll(ReferenceFinderUtil.findReferences(
                    Collections.singletonList(method),
                    ProgressManager.getInstance().getProgressIndicator()));
        }, "查找方法引用：" + signature, true, e.getProject());

        if (!ok) {
            // 用户取消
            Messages.showInfoMessage(e.getProject(),
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        CsvExporter.export(results, "method-refs", e.getProject());
    }

    @Nullable
    private PsiMethod getTargetMethod(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || !(psiFile instanceof PsiJavaFile)) return null;

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class);
    }
}
