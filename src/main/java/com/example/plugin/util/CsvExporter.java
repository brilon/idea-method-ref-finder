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
     * 将引用结果拼装为 CSV 字符串（使用默认表头）。
     */
    public static String buildCsvString(List<String[]> results) {
        return buildCsvString(results, HEADER);
    }

    /**
     * 将引用结果拼装为 CSV 字符串（自定义表头）。
     *
     * @param header 表头行，末尾需含 '\n'，例如 "源类,目标项目,目标模块,引用位置\n"
     */
    public static String buildCsvString(List<String[]> results, String header) {
        StringBuilder sb = new StringBuilder(header);
        for (String[] row : results) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(escapeCsv(row[i]));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * @param results    引用结果列表，每条为 String[4]
     * @param filePrefix 文件名前缀
     * @param header     自定义表头行（末尾含 '\n'）
     * @param project    当前 IntelliJ 项目
     */
    public static void export(List<String[]> results, String filePrefix, String header, Project project) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filePath = System.getProperty("user.home") + File.separator
                + filePrefix + "-" + timestamp + ".csv";

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(filePath), GBK)) {
            writer.write(buildCsvString(results, header));
            Messages.showInfoMessage(project,
                    "共找到 " + results.size() + " 条引用，已导出至：\n" + filePath,
                    "导出成功");
        } catch (IOException ex) {
            Messages.showErrorDialog(project, "CSV 导出失败：" + ex.getMessage(), "错误");
        }
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

    public static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
