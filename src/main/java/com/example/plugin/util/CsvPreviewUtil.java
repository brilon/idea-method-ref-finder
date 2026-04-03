package com.example.plugin.util;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.RegisterToolWindowTask;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 在 Tool Window 中以表格形式展示 CSV 数据。
 * 全选（Ctrl+A）后复制（Ctrl+C），内容为制表符分隔，可直接粘贴到 Excel。
 */
public class CsvPreviewUtil {

    private static final String TOOL_WINDOW_ID = "CSV 引用预览";

    public static void show(@NotNull Project project,
                            @NotNull String csvString,
                            @NotNull String tabTitle) {
        // ── 解析 CSV ──────────────────────────────────────────────────
        String[] lines = csvString.split("\n", -1);
        if (lines.length == 0) return;

        String[] headers = parseLine(lines[0]);
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                rows.add(parseLine(lines[i]));
            }
        }

        // ── 构建只读表格 ──────────────────────────────────────────────
        DefaultTableModel model = new DefaultTableModel(
                rows.toArray(new Object[0][]), headers) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);       // 点击表头可排序
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        // 默认 TransferHandler：Ctrl+A + Ctrl+C 复制为 TSV，可直接粘贴到 Excel
        JScrollPane scrollPane = new JScrollPane(table);

        // ── 注册或复用 Tool Window ────────────────────────────────────
        ToolWindowManager twm = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = twm.getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            toolWindow = twm.registerToolWindow(
                    RegisterToolWindowTask.closable(TOOL_WINDOW_ID, AllIcons.Actions.Find));
        }

        ContentManager contentManager = toolWindow.getContentManager();
        contentManager.removeAllContents(true);

        Content content = ContentFactory.getInstance()
                .createContent(scrollPane, tabTitle, false);
        contentManager.addContent(content);
        toolWindow.show();
    }

    /** 解析一行 CSV，支持双引号转义（"" → "）和带逗号的引号字段。 */
    private static String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }
}
