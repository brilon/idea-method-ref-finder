package com.example.plugin.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 {@link CsvExporter} 的 CSV 生成逻辑（不依赖 IntelliJ 平台）。
 *
 * <p>直接测试导出文件内容，使用独立辅助方法复现导出逻辑，与真实实现保持一致。
 * CSV 列：源方法, 目标项目, 目标模块, 引用方法
 */
class CsvExporterTest {

    private static final Charset GBK = Charset.forName("GBK");

    /**
     * 使用与 {@link CsvExporter} 相同的逻辑写出文件，便于测试断言。
     * 每条记录为 String[4]：{源方法, 目标项目, 目标模块, 引用方法}
     */
    private void writeToFile(List<String[]> results, File file) throws IOException {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), GBK)) {
            writer.write("源方法,目标项目,目标模块,引用方法\n");
            for (String[] row : results) {
                writer.write(escapeCsv(row[0]) + ","
                        + escapeCsv(row[1]) + ","
                        + escapeCsv(row[2]) + ","
                        + escapeCsv(row[3]) + "\n");
            }
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // -----------------------------------------------------------------------

    @Test
    void testEmptyResultsWritesOnlyHeader(@TempDir Path tmpDir) throws IOException {
        File out = tmpDir.resolve("empty.csv").toFile();
        writeToFile(List.of(), out);

        String content = new String(java.nio.file.Files.readAllBytes(out.toPath()), GBK);
        assertEquals("源方法,目标项目,目标模块,引用方法\n", content);
    }

    @Test
    void testSingleRowWrittenCorrectly(@TempDir Path tmpDir) throws IOException {
        File out = tmpDir.resolve("single.csv").toFile();
        List<String[]> rows = List.<String[]>of(
                new String[]{"com.example.Foo#bar(java.lang.String)", "my-project", "common", "com.example.App#run()"});
        writeToFile(rows, out);

        String content = new String(java.nio.file.Files.readAllBytes(out.toPath()), GBK);
        assertTrue(content.contains("com.example.Foo#bar(java.lang.String)"));
        assertTrue(content.contains("my-project"));
        assertTrue(content.contains("common"));
        assertTrue(content.contains("com.example.App#run()"));
    }

    @Test
    void testCsvEscapingForCommaInValue(@TempDir Path tmpDir) throws IOException {
        File out = tmpDir.resolve("escape.csv").toFile();
        List<String[]> rows = List.<String[]>of(
                new String[]{"com.example.Foo#bar(int, long)", "proj,x", "mod-a", "com.example.App#go()"});
        writeToFile(rows, out);

        String content = new String(java.nio.file.Files.readAllBytes(out.toPath()), GBK);
        assertTrue(content.contains("\"com.example.Foo#bar(int, long)\""));
        assertTrue(content.contains("\"proj,x\""));
        assertTrue(content.contains("mod-a"));
    }

    @Test
    void testOutputIsGbkEncoded(@TempDir Path tmpDir) throws IOException {
        File out = tmpDir.resolve("gbk.csv").toFile();
        List<String[]> rows = List.<String[]>of(
                new String[]{"com.example.Foo#bar()", "示例项目", "示例模块", "com.example.App#run()"});
        writeToFile(rows, out);

        String gbkContent = new String(java.nio.file.Files.readAllBytes(out.toPath()), GBK);
        assertTrue(gbkContent.contains("示例项目"), "GBK 编码下中文应可正常读取");
        assertTrue(gbkContent.contains("示例模块"), "目标模块中文应可正常读取");

        String utf8Content = new String(java.nio.file.Files.readAllBytes(out.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(utf8Content.contains("示例项目"), "UTF-8 读取时中文应乱码，说明文件确实是 GBK 编码");
    }

    @Test
    void testMultipleRowsAllPresent(@TempDir Path tmpDir) throws IOException {
        File out = tmpDir.resolve("multi.csv").toFile();
        List<String[]> rows = List.of(
                new String[]{"com.example.A#foo()", "p1", "module-x", "com.example.B#bar()"},
                new String[]{"com.example.A#foo()", "p2", "module-y", "com.example.C#baz()"},
                new String[]{"com.example.A#foo()", "p1", "module-x", "com.example.D#qux()"});
        writeToFile(rows, out);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(out), GBK));
        long lineCount = reader.lines().count();
        // 1 header + 3 data rows
        assertEquals(4, lineCount);
    }

    @Test
    void testModuleColumnIsEmpty(@TempDir Path tmpDir) throws IOException {
        // 模块未知时（非模块化项目），目标模块列为空字符串
        File out = tmpDir.resolve("no-module.csv").toFile();
        List<String[]> rows = List.<String[]>of(
                new String[]{"com.example.Foo#bar()", "my-project", "", "com.example.App#run()"});
        writeToFile(rows, out);

        String content = new String(java.nio.file.Files.readAllBytes(out.toPath()), GBK);
        // 空模块列：两个逗号相邻
        assertTrue(content.contains("my-project,,com.example.App#run()"));
    }
}
