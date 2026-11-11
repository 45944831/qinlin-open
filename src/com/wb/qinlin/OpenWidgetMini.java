package com.wb.qinlin;

/**
 * 2×1 迷你小部件：左边门名、右边胶囊开门按钮。
 * 逻辑全部继承自 {@link OpenWidget}，这里只声明自己用哪种版式。
 */
public class OpenWidgetMini extends OpenWidget {
    @Override
    protected int style() {
        return STYLE_MINI;
    }
}
