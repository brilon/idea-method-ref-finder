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
import java.util.Arrays;
import java.util.List;

/**
 * 右键菜单动作：在光标所在类上，查找该类全部方法在所有已打开项目中的引用，导出 GBK CSV。
 *
 * <p>仅当光标位于 Java 类声明内时，菜单项才可见。
 */
public class FindClassMethodReferencesAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiClass psiClass = getTargetClass(e);
        // 有类且类中存在至少一个方法时才显示
        e.getPresentation().setEnabledAndVisible(
                psiClass != null && psiClass.getMethods().length > 0);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PsiClass psiClass = getTargetClass(e);
        if (psiClass == null) return;

        List<PsiMethod> methods = Arrays.asList(psiClass.getMethods());
        if (methods.isEmpty()) {
            Messages.showInfoMessage(e.getProject(), "该类没有方法，无需查找。", "提示");
            return;
        }

        String className = psiClass.getQualifiedName() != null
                ? psiClass.getQualifiedName() : psiClass.getName();

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
            // 若光标在匿名类中，取外层具名类
            psiClass = PsiTreeUtil.getParentOfType(psiClass, PsiClass.class, true);
        }
        return psiClass;
    }
}
