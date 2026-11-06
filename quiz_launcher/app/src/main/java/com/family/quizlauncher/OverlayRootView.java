package com.family.quizlauncher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/**
 * 答题遮罩的根视图。
 *
 * 遮罩是 WindowManager 的独立窗口（TYPE_ACCESSIBILITY_OVERLAY），按键会先到这里。
 * 在根视图上统一拦截返回键 / HOME 键，保证焦点在任意选项按钮上时都能生效，
 * 不会因为某个子控件没处理而把按键漏给下层受限应用。
 */
public class OverlayRootView extends FrameLayout {

    /** 供遮罩内部处理方向键焦点跳转（选项最后一排 ↓ 底部操作区等）。 */
    public interface KeyHandler {
        /** 返回 true 表示已处理，事件不再向下传递。 */
        boolean onKeyEvent(KeyEvent event);
    }

    private Runnable backAction;
    private Runnable homeAction;
    /** 任何按键/触摸都算一次「用户还在操作」，用于重置遮罩的超时看门狗。 */
    private Runnable interactionAction;
    private KeyHandler keyHandler;

    public OverlayRootView(Context context) {
        super(context);
    }

    public OverlayRootView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public OverlayRootView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setBackAction(Runnable action) {
        backAction = action;
    }

    public void setHomeAction(Runnable action) {
        homeAction = action;
    }

    public void setInteractionAction(Runnable action) {
        interactionAction = action;
    }

    public void setKeyHandler(KeyHandler handler) {
        keyHandler = handler;
    }

    /** 仅用于日志：当前焦点控件 id 名，便于真机确认遥控器落点。 */
    private String getFocusedViewTag() {
        try {
            android.view.View focused = findFocus();
            if (focused == null) {
                return "none";
            }
            int id = focused.getId();
            return id == android.view.View.NO_ID ? "no-id" : getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (interactionAction != null && event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            interactionAction.run();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            android.util.Log.d("QuizGate", "overlay key code=" + keyCode
                    + " focused=" + (getFocusedViewTag()));
        }
        if (interactionAction != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            interactionAction.run();
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && backAction != null) {
                backAction.run();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP && homeAction != null) {
                homeAction.run();
            }
            return true;
        }
        if (keyHandler != null && keyHandler.onKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
