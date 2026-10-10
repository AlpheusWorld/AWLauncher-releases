package ru.aw.assistant.ui;

import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;

public final class SearchInput {
    private String value = "";
    private int cursor;
    private boolean selectedAll;
    public String value() { return value; }
    public int cursor() { return cursor; }
    public boolean selectedAll() { return selectedAll; }
    public void value(String text) { value = text; cursor = text.length(); selectedAll = false; }
    public void insert(String text) {
        text = text.replaceAll("[\\p{Cntrl}\\r\\n]", " ");
        if (selectedAll) { value = ""; cursor = 0; selectedAll = false; }
        int remaining = 160 - value.length();
        if (remaining <= 0) return;
        text = text.substring(0, Math.min(remaining, text.length()));
        value = value.substring(0, cursor) + text + value.substring(cursor); cursor += text.length();
    }
    public boolean key(KeyEvent event, String clipboard, java.util.function.Consumer<String> copy) {
        int key = event.key();
        if (event.hasControlDown()) {
            if (key == InputConstants.KEY_A) { selectedAll = true; return true; }
            if (key == InputConstants.KEY_C) { if (selectedAll) copy.accept(value); return true; }
            if (key == InputConstants.KEY_X) { if (selectedAll) { copy.accept(value); value(""); } return true; }
            if (key == InputConstants.KEY_V) { insert(clipboard); return true; }
        }
        if (key == InputConstants.KEY_BACKSPACE || key == InputConstants.KEY_DELETE) {
            if (selectedAll) value("");
            else if (key == InputConstants.KEY_BACKSPACE && cursor > 0) {
                int from = value.offsetByCodePoints(cursor, -1); value = value.substring(0, from) + value.substring(cursor); cursor = from;
            } else if (key == InputConstants.KEY_DELETE && cursor < value.length()) {
                int to = value.offsetByCodePoints(cursor, 1); value = value.substring(0, cursor) + value.substring(to);
            }
            return true;
        }
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT) {
            selectedAll = false;
            if (key == InputConstants.KEY_LEFT && cursor > 0) cursor = value.offsetByCodePoints(cursor, -1);
            if (key == InputConstants.KEY_RIGHT && cursor < value.length()) cursor = value.offsetByCodePoints(cursor, 1);
            return true;
        }
        if (key == InputConstants.KEY_HOME || key == InputConstants.KEY_END) { cursor = key == InputConstants.KEY_HOME ? 0 : value.length(); selectedAll = false; return true; }
        return false;
    }
}
