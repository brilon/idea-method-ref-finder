package com.example.plugin.action;

import com.example.plugin.util.CsvExporter;
import com.example.plugin.util.CsvPreviewUtil;
import com.example.plugin.util.ReferenceFinderUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 编辑器右键菜单：输入多行方法签名，向上追溯调用链至终端入口节点
 * （RestController / KafkaListener / Scheduled / 无引用），
 * 支持跨微服务追踪（通过 restService.properties 配置 + exchangeInApp 调用），
 * 结果在 Tool Window 中以 CSV 表格预览。后台执行，取消时弹出确认对话框。
 *
 * <p>输入格式（每行一个）：{@code com.example.ClassName#methodName(param1Type, param2Type)}
 *
 * <p>输出列（动态）：源方法, 源方法所属模块, 最终目标, 所属模块, 入口类型, 接口路径, 节点1, 节点2, ...
 */
public class TraceEndpointChainPreviewAction extends AnAction {

    private static final int MAX_SERVICE_HOPS = 3;

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

        ReferenceFinderUtil.TraceResult[] holder = {null};

        new Task.Backgroundable(project,
                "追溯入口链路（共 " + signatures.size() + " 个方法）", true) {

            private volatile boolean wasCancelled = false;

            @Override
            public void run(@NotNull ProgressIndicator outerIndicator) {
                ConfirmCancelIndicator myIndicator = new ConfirmCancelIndicator(outerIndicator);
                ProgressManager.getInstance().runProcess(() ->
                        holder[0] = ReferenceFinderUtil.traceUpwardToEndpoints(
                                signatures, MAX_SERVICE_HOPS, myIndicator),
                        myIndicator);
                wasCancelled = myIndicator.isConfirmedCancelled();
            }

            @Override
            public void onFinished() {
                ReferenceFinderUtil.TraceResult result = holder[0];
                if (result == null || result.rows.isEmpty()) {
                    Messages.showInfoMessage(project, "已取消或无结果。", "提示");
                    return;
                }
                if (wasCancelled) {
                    Messages.showInfoMessage(project,
                            "已取消，已收集 " + result.rows.size() + " 条链路。", "已取消");
                }
                String csv = CsvExporter.buildCsvString(result.rows, result.header);
                CsvPreviewUtil.show(project, csv,
                        "入口链路追溯 (" + result.rows.size() + " 条)");
            }
        }.queue();
    }

    /** 多行方法签名输入对话框。 */
    static class InputDialog extends DialogWrapper {

        private JTextArea textArea;

        InputDialog(@NotNull Project project) {
            super(project);
            setTitle("输入方法签名 — 追溯入口链路");
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
                    "<br/>从输入方法向上追溯调用链，找到最终的 RestController / KafkaListener / Scheduled 入口。" +
                    "<br/>支持跨微服务追踪（通过 restService.properties 配置），空行自动跳过。</html>");
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
            return "TraceEndpointChainDialog";
        }
    }
}
