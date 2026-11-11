package com.wb.qinlin;

/**
 * 4×1 横条小部件：门名 + 上次结果 + 胶囊开门按钮，一行高。
 * 逻辑全部继承自 {@link OpenWidget}，这里只声明自己用哪种版式。
 */
public class OpenWidgetBar extends OpenWidget {
    @Override
    protected int style() {
        return STYLE_BAR;
    }
}
