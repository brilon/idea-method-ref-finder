package com.example.plugin.action;

import com.example.plugin.util.CsvExporter;
import com.example.plugin.util.ReferenceFinderUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 编辑器右键菜单：输入多行方法签名，向上追溯调用链至终端入口节点，
 * 边追溯边将结果流式写入 CSV 文件（GBK 编码，保存至用户主目录）。
 *
 * <p>每完成一个签名的追溯即立即将该签名的链路行写入文件并 flush，
 * 取消时已写入的行仍保留。CSV 使用固定列宽（8 个固定列 + 20 个节点列）。
 */
public class TraceEndpointChainCsvAction extends AnAction {

    private static final int MAX_SERVICE_HOPS = 3;
    private static final int MAX_NODE_COLS = 20;
    private static final int TOTAL_COLS = 8 + MAX_NODE_COLS; // 28

    private static final Charset GBK = Charset.forName("GBK");

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

        TraceEndpointChainPreviewAction.InputDialog dialog =
                new TraceEndpointChainPreviewAction.InputDialog(project);
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

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filePath = System.getProperty("user.home") + File.separator
                + "trace-endpoint-" + timestamp + ".csv";

        new Task.Backgroundable(project,
                "追溯入口链路（共 " + signatures.size() + " 个方法）", true) {

            private final AtomicInteger rowCount = new AtomicInteger(0);
            private volatile boolean wasCancelled = false;
            private volatile String ioError = null;

            @Override
            public void run(@NotNull ProgressIndicator outerIndicator) {
                ConfirmCancelIndicator myIndicator = new ConfirmCancelIndicator(outerIndicator);

                try (Writer writer = new OutputStreamWriter(new FileOutputStream(filePath), GBK)) {
                    writer.write(buildFixedHeader());

                    ProgressManager.getInstance().runProcess(() -> {
                        for (String sig : signatures) {
                            if (myIndicator.isCanceled()) {
                                wasCancelled = true;
                                break;
                            }
                            myIndicator.setText("追溯: " + sig);

                            ReferenceFinderUtil.TraceResult result =
                                    ReferenceFinderUtil.traceUpwardToEndpoints(
                                            Collections.singletonList(sig),
                                            MAX_SERVICE_HOPS, myIndicator);

                            try {
                                for (String[] row : result.rows) {
                                    writer.write(buildPaddedRow(row));
                                    rowCount.incrementAndGet();
                                }
                                writer.flush();
                            } catch (IOException ex) {
                                ioError = ex.getMessage();
                                wasCancelled = true;
                                break;
                            }
                        }
                        wasCancelled = wasCancelled || myIndicator.isConfirmedCancelled();
                    }, myIndicator);

                } catch (IOException ex) {
                    ioError = ex.getMessage();
                }
            }

            @Override
            public void onFinished() {
                if (ioError != null) {
                    Messages.showErrorDialog(project, "CSV 导出失败：" + ioError, "错误");
                    return;
                }
                if (wasCancelled) {
                    Messages.showInfoMessage(project,
                            "已取消，已收集 " + rowCount.get() + " 条链路，已保存至：\n" + filePath,
                            "已取消");
                } else {
                    Messages.showInfoMessage(project,
                            "共找到 " + rowCount.get() + " 条链路，已导出至：\n" + filePath,
                            "导出成功");
                }
            }
        }.queue();
    }

    /** Fixed 28-column header (8 fixed + 20 node columns). */
    private static String buildFixedHeader() {
        StringBuilder sb = new StringBuilder(
                "源方法,源方法所属模块,源模块接口路径,API名称,最终目标,所属模块,入口类型,接口路径");
        for (int i = 1; i <= MAX_NODE_COLS; i++) {
            sb.append(",节点").append(i);
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Pads / trims {@code row} to exactly {@link #TOTAL_COLS} columns and returns a CSV line.
     * The row from {@link ReferenceFinderUtil#traceUpwardToEndpoints} has {@code 8 + maxNodeCount}
     * columns where {@code maxNodeCount} may be less than {@link #MAX_NODE_COLS}.
     */
    private static String buildPaddedRow(String[] row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TOTAL_COLS; i++) {
            if (i > 0) sb.append(",");
            String val = (i < row.length && row[i] != null) ? row[i] : "";
            sb.append(CsvExporter.escapeCsv(val));
        }
        sb.append("\n");
        return sb.toString();
    }
}
