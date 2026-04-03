package com.example.plugin.util;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.io.*;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 将引用结果导出为 GBK 编码的 CSV 文件（保存至用户主目录）。
 */
public class CsvExporter {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String HEADER = "源方法,目标项目,目标模块,引用方法\n";

    /**
     * 将引用结果拼装为 CSV 字符串（UTF-8）。
     *
     * @param results 引用结果列表，每条为 String[4]：{源方法, 目标项目, 目标模块, 引用方法}
     * @return CSV 文本（含表头）
     */
    public static String buildCsvString(List<String[]> results) {
        StringBuilder sb = new StringBuilder(HEADER);
        for (String[] row : results) {
            sb.append(escapeCsv(row[0])).append(",")
              .append(escapeCsv(row[1])).append(",")
              .append(escapeCsv(row[2])).append(",")
              .append(escapeCsv(row[3])).append("\n");
        }
        return sb.toString();
    }

    /**
     * @param results    引用结果列表，每条为 String[4]：{源方法, 目标项目, 目标模块, 引用方法}
     * @param filePrefix 文件名前缀，如 "method-refs" 或 "class-refs"
     * @param project    当前 IntelliJ 项目（用于显示弹窗）
     */
    public static void export(List<String[]> results, String filePrefix, Project project) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filePath = System.getProperty("user.home") + File.separator
                + filePrefix + "-" + timestamp + ".csv";

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(filePath), GBK)) {
            writer.write(buildCsvString(results));
            Messages.showInfoMessage(project,
                    "共找到 " + results.size() + " 条引用，已导出至：\n" + filePath,
                    "导出成功");
        } catch (IOException ex) {
            Messages.showErrorDialog(project, "CSV 导出失败：" + ex.getMessage(), "错误");
        }
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
