package com.example.plugin.action;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.UserDataHolderBase;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 包装外部 {@link ProgressIndicator}，在用户点击取消时弹出确认对话框。
 *
 * <p>用法：在 {@link com.intellij.openapi.progress.Task.Backgroundable#run} 中创建此对象，
 * 然后通过 {@link com.intellij.openapi.progress.ProgressManager#runProcess} 将其设为当前线程的指示器。
 *
 * <pre>{@code
 *   public void run(@NotNull ProgressIndicator outerIndicator) {
 *       ConfirmCancelIndicator myIndicator = new ConfirmCancelIndicator(outerIndicator);
 *       ProgressManager.getInstance().runProcess(() -> {
 *           // ... actual work ...
 *       }, myIndicator);
 *   }
 * }</pre>
 *
 * <p>当用户点击"取消"后：
 * <ul>
 *   <li>弹出确认对话框（在 EDT 上，通过 invokeAndWait 阻塞后台线程）</li>
 *   <li>选"停止追溯"→ {@link #isCanceled()} 返回 {@code true}，PSI 代码将抛出 {@link ProcessCanceledException}</li>
 *   <li>选"继续追溯"→ {@link #isCanceled()} 返回 {@code false}，工作正常继续</li>
 * </ul>
 */
class ConfirmCancelIndicator extends UserDataHolderBase implements ProgressIndicator {

    private final ProgressIndicator delegate;
    private final AtomicBoolean dialogShown = new AtomicBoolean(false);
    private volatile boolean confirmed = false;

    ConfirmCancelIndicator(ProgressIndicator delegate) {
        this.delegate = delegate;
    }

    // ── Cancellation with dialog confirmation ───────────────────────────────

    @Override
    public boolean isCanceled() {
        if (confirmed) return true;
        if (delegate.isCanceled()) {
            if (dialogShown.compareAndSet(false, true)) {
                boolean[] stop = {false};
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    int r = Messages.showYesNoDialog(
                            null,
                            "确定要停止追溯吗？已收集的链路将保留。",
                            "停止确认",
                            "停止追溯", "继续追溯",
                            Messages.getQuestionIcon());
                    stop[0] = (r == Messages.YES);
                });
                if (stop[0]) {
                    confirmed = true;
                    return true;
                }
                // User chose "继续追溯": dialogShown stays true so the dialog won't
                // re-appear; confirmed stays false → isCanceled() returns false → work continues.
            }
            return false;
        }
        return false;
    }

    @Override
    public void checkCanceled() throws ProcessCanceledException {
        if (isCanceled()) throw new ProcessCanceledException();
    }

    /** Whether the user explicitly confirmed stop — useful for onFinished() messaging. */
    boolean isConfirmedCancelled() {
        return confirmed;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void start() { delegate.start(); }

    @Override
    public void stop() { delegate.stop(); }

    @Override
    public boolean isRunning() { return delegate.isRunning(); }

    @Override
    public void cancel() { delegate.cancel(); }

    // ── Progress text / fraction ─────────────────────────────────────────────

    @Override
    public void setText(String text) { delegate.setText(text); }

    @Override
    public String getText() { return delegate.getText(); }

    @Override
    public void setText2(String text) { delegate.setText2(text); }

    @Override
    public String getText2() { return delegate.getText2(); }

    @Override
    public double getFraction() { return delegate.getFraction(); }

    @Override
    public void setFraction(double fraction) { delegate.setFraction(fraction); }

    @Override
    public boolean isIndeterminate() { return delegate.isIndeterminate(); }

    @Override
    public void setIndeterminate(boolean indeterminate) { delegate.setIndeterminate(indeterminate); }

    // ── State / modality ─────────────────────────────────────────────────────

    @Override
    public void pushState() { delegate.pushState(); }

    @Override
    public void popState() { delegate.popState(); }

    @Override
    public boolean isModal() { return delegate.isModal(); }

    @Override
    public com.intellij.openapi.application.ModalityState getModalityState() {
        return delegate.getModalityState();
    }

    @Override
    public void setModalityProgress(ProgressIndicator modalityProgress) {
        delegate.setModalityProgress(modalityProgress);
    }

    @Override
    public boolean isPopupWasShown() { return delegate.isPopupWasShown(); }

    @Override
    public boolean isShowing() { return delegate.isShowing(); }
}
