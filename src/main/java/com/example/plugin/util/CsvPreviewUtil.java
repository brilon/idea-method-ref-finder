package com.example.plugin.util;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.wm.RegisterToolWindowTask;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;

/**
 * 在 Tool Window 中嵌入只读编辑器，直接展示 CSV 源文本。
 * 用户可在窗口内 Ctrl+A → Ctrl+C 复制全部内容。
 */
public class CsvPreviewUtil {

    private static final String TOOL_WINDOW_ID = "CSV 引用预览";

    /**
     * 打开（或刷新）Tool Window，将 csvString 显示在只读编辑器中。
     *
     * @param project   当前 IntelliJ 项目
     * @param csvString 要展示的 CSV 文本
     * @param tabTitle  Tab 标签文字，例如 "method-refs (42 条)"
     */
    public static void show(@NotNull Project project,
                            @NotNull String csvString,
                            @NotNull String tabTitle) {
        ToolWindowManager twm = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = twm.getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            toolWindow = twm.registerToolWindow(RegisterToolWindowTask.closable(TOOL_WINDOW_ID, AllIcons.Actions.Find));
        }

        ContentManager contentManager = toolWindow.getContentManager();

        // 移除旧内容（ContentManagerListener 负责释放旧 Editor）
        contentManager.removeAllContents(true);

        // 创建只读编辑器（isViewer = true）
        EditorFactory editorFactory = EditorFactory.getInstance();
        Document doc = editorFactory.createDocument(csvString);
        Editor editor = editorFactory.createEditor(doc, project, PlainTextFileType.INSTANCE, true);

        Content content = ContentFactory.getInstance()
                .createContent(editor.getComponent(), tabTitle, false);

        // 内容关闭时释放 Editor，防止内存泄漏
        contentManager.addContentManagerListener(new ContentManagerListener() {
            @Override
            public void contentRemoved(@NotNull ContentManagerEvent event) {
                if (event.getContent() == content) {
                    editorFactory.releaseEditor(editor);
                    contentManager.removeContentManagerListener(this);
                }
            }
        });

        contentManager.addContent(content);
        toolWindow.show();
    }
}
