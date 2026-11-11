package com.wb.qinlin;

/**
 * 1×1 迷你小部件：整格就是一个开门按钮。
 * 逻辑全部继承自 {@link OpenWidget}，这里只声明自己用哪种版式。
 */
public class OpenWidgetSquare extends OpenWidget {
    @Override
    protected int style() {
        return STYLE_SQUARE;
    }
}
