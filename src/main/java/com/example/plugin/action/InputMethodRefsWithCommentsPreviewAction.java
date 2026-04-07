package com.example.plugin.action;

import com.example.plugin.util.CsvExporter;
import com.example.plugin.util.CsvPreviewUtil;
import com.example.plugin.util.ReferenceFinderUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 编辑器右键菜单：输入多行方法签名，批量查找每个方法的跨项目引用，
 * 并输出源方法注释（含接口/父类来源标签）和引用位置注释（含上层引用链最多 3 层，每层含接口/父类来源标签）。
 *
 * <p>输入格式（每行一个）：{@code com.example.ClassName#methodName(param1Type, param2Type)}
 *
 * <p>输出列：源方法引用, 源方法注释, 引用位置, 引用链注释
 *
 * <p>注释列格式示例：
 * <ul>
 *   <li>源方法注释：{@code [自身] 注释 | [接口: IFoo#bar] 接口注释 | [父类: Base#bar] 父类注释}</li>
 *   <li>引用链注释：{@code [L1: ServiceImpl#process] 注释 | [L1接口: IService#process] 注释 | [L2: Controller#exec] 注释}</li>
 * </ul>
 */
public class InputMethodRefsWithCommentsPreviewAction extends AnAction {

    private static final String HEADER = "源方法引用,源方法注释,引用位置,引用链注释\n";
    private static final int CHAIN_DEPTH = 3;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        InputDialog dialog = new InputDialog(project);
        if (!dialog.showAndGet()) return;

        String input = dialog.getInputText().trim();
        if (input.isEmpty()) {
            Messages.showInfoMessage(project, "未输入任何方法签名。", "提示");
            return;
        }

        List<String> signatures = Arrays.stream(input.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        if (signatures.isEmpty()) {
            Messages.showInfoMessage(project, "未输入任何有效方法签名。", "提示");
            return;
        }

        List<String[]> results = new ArrayList<>();
        boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            results.addAll(ReferenceFinderUtil.findInputMethodRefsWithAnnotatedComments(
                    signatures, CHAIN_DEPTH, indicator));
        }, "分析方法引用注释（共 " + signatures.size() + " 个方法）", true, project);

        if (!ok) {
            Messages.showInfoMessage(project,
                    "已取消，已收集 " + results.size() + " 条引用。", "已取消");
            if (results.isEmpty()) return;
        }

        String csv = CsvExporter.buildCsvString(results, HEADER);
        CsvPreviewUtil.show(project, csv, "方法引用注释 (" + results.size() + " 条)");
    }

    /** 多行方法签名输入对话框。 */
    private static class InputDialog extends DialogWrapper {

        private JTextArea textArea;

        protected InputDialog(@NotNull Project project) {
            super(project);
            setTitle("输入方法签名（每行一个）");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            textArea = new JTextArea(16, 80);
            textArea.setLineWrap(false);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

            JScrollPane scrollPane = new JScrollPane(textArea,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

            JLabel hint = new JLabel(
                    "<html>每行一个方法签名，格式：" +
                    "<code>com.example.ClassName#methodName(param1Type, param2Type)</code>" +
                    "<br/>支持直接粘贴 CSV 引用位置列内容，空行自动跳过。</html>");
            hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

            JPanel panel = new JPanel(new BorderLayout());
            panel.setPreferredSize(new Dimension(750, 380));
            panel.add(hint, BorderLayout.NORTH);
            panel.add(scrollPane, BorderLayout.CENTER);
            return panel;
        }

        public String getInputText() {
            return textArea != null ? textArea.getText() : "";
        }

        @Override
        protected @Nullable String getDimensionServiceKey() {
            return "InputMethodRefsWithCommentsDialog";
        }
    }
}
