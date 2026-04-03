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
import java.util.Arrays;
import java.util.List;

/**
 * 右键菜单动作：在光标所在类上，查找该类全部方法在所有已打开项目中的引用，导出 GBK CSV。
 *
 * <p>仅当光标位于 Java 类声明内时，菜单项才可见。
 */
public class FindClassMethodReferencesAction extends AnAction {

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
        boolean visible = ReadAction.compute(() -> {
            PsiClass psiClass = getTargetClass(e);
            // 有类且包含至少一个方法时才显示
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

        boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            results.addAll(ReferenceFinderUtil.findReferences(
                    methods,
                    ProgressManager.getInstance().getProgressIndicator()));
        }, "查找类方法引用：" + className, true, e.getProject());

        if (!ok) {
            Messages.showInfoMessage(e.getProject(),
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        CsvExporter.export(results, "class-refs", e.getProject());
    }

    @Nullable
    private PsiClass getTargetClass(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || !(psiFile instanceof PsiJavaFile)) return null;

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        // 优先取最近的具名类（非匿名类）
        PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        if (psiClass instanceof PsiAnonymousClass) {
            psiClass = PsiTreeUtil.getParentOfType(psiClass, PsiClass.class, true);
        }
        return psiClass;
    }
}
