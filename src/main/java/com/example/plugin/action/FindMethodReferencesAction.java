package com.example.plugin.action;

import com.example.plugin.util.CsvExporter;
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
import java.util.Collections;
import java.util.List;

/**
 * 右键菜单动作：在光标所在方法上，查找该方法在所有已打开项目中的引用，导出 GBK CSV。
 *
 * <p>仅当光标位于 Java 方法声明内时，菜单项才可见。
 */
public class FindMethodReferencesAction extends AnAction {

    /**
     * 声明 update() 在后台线程执行（IDEA 2022.3+ 要求）。
     * PSI 访问需包裹在 ReadAction 内。
     */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean visible = ReadAction.compute(() -> getTargetMethod(e) != null);
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // actionPerformed 在 EDT 执行，PSI 读取需用 ReadAction
        PsiMethod method = ReadAction.compute(() -> getTargetMethod(e));
        if (method == null) return;

        String signature = ReadAction.compute(() -> ReferenceFinderUtil.getMethodSignature(method));
        List<String[]> results = new ArrayList<>();

        boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            // 后台线程：findReferences 内部自行管理 ReadAction
            results.addAll(ReferenceFinderUtil.findReferences(
                    Collections.singletonList(method),
                    ProgressManager.getInstance().getProgressIndicator()));
        }, "查找方法引用：" + signature, true, e.getProject());

        if (!ok) {
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
