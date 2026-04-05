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
import java.util.Collections;
import java.util.List;

/**
 * 右键菜单动作：查找光标所在方法的跨项目引用，并分析每条引用方法再往上 4 层的调用链，
 * 标注"链路终止"或"链路继续"，结果在 Tool Window 中预览。
 */
public class AnalyzeMethodChainPreviewAction extends AnAction {

    private static final String HEADER = "源方法,引用方,引用方可删除,引用状态\n";
    private static final int CHAIN_DEPTH = 4;

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
        PsiMethod method = ReadAction.compute(() -> getTargetMethod(e));
        if (method == null) return;

        String signature = ReadAction.compute(() -> ReferenceFinderUtil.getMethodSignature(method));
        List<String[]> results = new ArrayList<>();

        boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(() ->
                results.addAll(ReferenceFinderUtil.analyzeMethodReferences(
                        Collections.singletonList(method),
                        CHAIN_DEPTH,
                        ProgressManager.getInstance().getProgressIndicator())),
                "分析方法引用链：" + signature, true, e.getProject());

        if (!ok) {
            Messages.showInfoMessage(e.getProject(),
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        String csv = CsvExporter.buildCsvString(results, HEADER);
        CsvPreviewUtil.show(e.getProject(), csv, "方法引用分析 (" + results.size() + " 个方法)");
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
