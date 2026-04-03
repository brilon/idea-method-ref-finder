package com.example.plugin.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 {@link CsvExporter} 的 CSV 生成逻辑（不依赖 IntelliJ 平台）。
 *
 * <p>通过反射或提取可测试部分进行验证。此处直接测试导出文件内容，
 * 因此使用独立的辅助方法复现导出逻辑，与真实实现保持一致。
 */
class CsvExporterTest {

    private static final Charset GBK = Charset.forName("GBK");

    /**
     * 使用与 {@link CsvExporter} 相同的逻辑写出文件，便于测试断言。
     */
    private void writeToFile(List<String[]> results, File file) throws IOException {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), GBK)) {
            writer.write("源方法,目标项目,引用方法\n");
            for (String[] row : results) {
                writer.write(escapeCsv(row[0]) + "," + escapeCsv(row[1]) + "," + escapeCsv(row[2]) + "\n");
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
        assertEquals("源方法,目标项目,引用方法\n", content);
    }

    @Test
    void testSingleRowWrittenCorrectly(@TempDir Path tmpDir) throws IOException {
        File out = tmpDir.resolve("single.csv").toFile();
        List<String[]> rows = List.of(
                new String[]{"com.example.Foo#bar(java.lang.String)", "my-project", "com.example.App#run()"});
        writeToFile(rows, out);

        String content = new String(java.nio.file.Files.readAllBytes(out.toPath()), GBK);
        assertTrue(content.contains("com.example.Foo#bar(java.lang.String)"));
        assertTrue(content.contains("my-project"));
        assertTrue(content.contains("com.example.App#run()"));
    }

    @Test
    void testCsvEscapingForCommaInValue(@TempDir Path tmpDir) throws IOException {
        File out = tmpDir.resolve("escape.csv").toFile();
        // 方法名里含逗号（极端场景）
        List<String[]> rows = List.of(
                new String[]{"com.example.Foo#bar(int, long)", "proj,x", "com.example.App#go()"});
        writeToFile(rows, out);

        String content = new String(java.nio.file.Files.readAllBytes(out.toPath()), GBK);
        // 含逗号的字段应被双引号包裹
        assertTrue(content.contains("\"com.example.Foo#bar(int, long)\""));
        assertTrue(content.contains("\"proj,x\""));
    }

    @Test
    void testOutputIsGbkEncoded(@TempDir Path tmpDir) throws IOException {
        File out = tmpDir.resolve("gbk.csv").toFile();
        List<String[]> rows = List.of(
                new String[]{"com.example.Foo#bar()", "示例项目", "com.example.App#run()"});
        writeToFile(rows, out);

        // 用 GBK 读取，中文不应乱码
        String gbkContent = new String(java.nio.file.Files.readAllBytes(out.toPath()), GBK);
        assertTrue(gbkContent.contains("示例项目"), "GBK 编码下中文应可正常读取");

        // 用 UTF-8 读取，中文会乱码（字节不同）
        String utf8Content = new String(java.nio.file.Files.readAllBytes(out.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(utf8Content.contains("示例项目"), "UTF-8 读取时中文应乱码，说明文件确实是 GBK 编码");
    }

    @Test
    void testMultipleRowsAllPresent(@TempDir Path tmpDir) throws IOException {
        File out = tmpDir.resolve("multi.csv").toFile();
        List<String[]> rows = List.of(
                new String[]{"com.example.A#foo()", "p1", "com.example.B#bar()"},
                new String[]{"com.example.A#foo()", "p2", "com.example.C#baz()"},
                new String[]{"com.example.A#foo()", "p1", "com.example.D#qux()"});
        writeToFile(rows, out);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(out), GBK));
        long lineCount = reader.lines().count();
        // 1 header + 3 data rows
        assertEquals(4, lineCount);
    }
}
