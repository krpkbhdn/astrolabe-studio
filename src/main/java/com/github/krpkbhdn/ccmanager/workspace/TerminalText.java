package com.github.krpkbhdn.ccmanager.workspace;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders raw pty output the way a terminal would show it: a carriage return or cursor move lets later text overwrite earlier
 * text, colors, modes and titles are dropped. Tabs become one space. Linear time, only the last {@link #MAX_LINES} lines are kept.
 * shortcut: absolute cursor positions (ESC[row;colH) map onto the rendered lines, not onto a real screen, and a row past the
 * last line lands on the last line; upgrade if a tool redraws a full-screen UI.
 */
public final class TerminalText {

    static final int MAX_LINES = 2000;
    private static final char ESC = '\u001B';
    private static final char BEL = '\u0007';
    private static final int TOP = 1;

    private final List<StringBuilder> lines = new ArrayList<>();
    private int row;
    private int column;

    private TerminalText() {
        lines.add(new StringBuilder());
    }

    public static String render(String raw) {
        var screen = new TerminalText();
        screen.feed(raw);
        return screen.text();
    }

    private void feed(String raw) {
        int length = raw.length();
        int i = 0;
        while (i < length) {
            char c = raw.charAt(i);
            if (c == ESC) {
                i = escape(raw, i);
            } else {
                control(c);
                i++;
            }
        }
    }

    private void control(char c) {
        switch (c) {
            case '\n' -> newLine();
            case '\r' -> column = 0;
            case '\b' -> column = Math.max(0, column - 1);
            case '\t' -> put(' ');
            default -> {
                if (c >= ' ' && c != '\u007F') {
                    put(c);
                }
            }
        }
    }

    /** Returns the index after the sequence; an unfinished one swallows the rest of the input. */
    private int escape(String raw, int start) {
        int next = start + 1;
        if (next >= raw.length()) {
            return raw.length();
        }
        return switch (raw.charAt(next)) {
            case '[' -> csi(raw, next + 1);
            case ']' -> osc(raw, next + 1);
            case '(', ')', '#' -> Math.min(raw.length(), next + 2);
            default -> next + 1;
        };
    }

    private int osc(String raw, int from) {
        for (int i = from; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == BEL) {
                return i + 1;
            }
            if (c == ESC) {
                return Math.min(raw.length(), i + 2);
            }
        }
        return raw.length();
    }

    private int csi(String raw, int from) {
        int i = from;
        while (i < raw.length() && raw.charAt(i) >= 0x20 && raw.charAt(i) <= 0x3F) {
            i++;
        }
        if (i >= raw.length()) {
            return raw.length();
        }
        csiCommand(raw.substring(from, i), raw.charAt(i));
        return i + 1;
    }

    private void csiCommand(String parameters, char command) {
        if (!parameters.isEmpty() && "<=>?".indexOf(parameters.charAt(0)) >= 0) {
            return;
        }
        var numbers = numbers(parameters);
        int first = numbers.isEmpty() || numbers.getFirst() == 0 ? 1 : numbers.getFirst();
        switch (command) {
            case 'K' -> eraseLine(numbers.isEmpty() ? 0 : numbers.getFirst());
            case 'J' -> eraseDisplay(numbers.isEmpty() ? 0 : numbers.getFirst());
            case 'G' -> column = first - 1;
            case 'A' -> row = Math.max(0, row - first);
            case 'B' -> row = Math.min(lines.size() - 1, row + first);
            case 'C' -> column += first;
            case 'D' -> column = Math.max(0, column - first);
            case 'H', 'f' -> position(numbers.isEmpty() ? TOP : Math.max(TOP, numbers.getFirst()),
                    numbers.size() < 2 ? TOP : Math.max(TOP, numbers.get(1)));
            default -> { }
        }
    }

    private static List<Integer> numbers(String parameters) {
        var result = new ArrayList<Integer>();
        for (var part : parameters.split(";", -1)) {
            if (part.isEmpty()) {
                result.add(0);
            } else if (part.chars().allMatch(Character::isDigit) && part.length() < 6) {
                result.add(Integer.parseInt(part));
            } else {
                return List.of();
            }
        }
        return result;
    }

    private void position(int targetRow, int targetColumn) {
        row = Math.min(lines.size() - 1, targetRow - 1);
        column = targetColumn - 1;
    }

    private void eraseLine(int mode) {
        var line = lines.get(row);
        switch (mode) {
            case 0 -> {
                if (column < line.length()) {
                    line.setLength(column);
                }
            }
            case 1 -> {
                for (int i = 0; i < Math.min(column + 1, line.length()); i++) {
                    line.setCharAt(i, ' ');
                }
            }
            case 2 -> line.setLength(0);
            default -> { }
        }
    }

    private void eraseDisplay(int mode) {
        if (mode == 2 || mode == 3) {
            lines.clear();
            lines.add(new StringBuilder());
            row = 0;
        } else if (mode == 0) {
            eraseLine(0);
            lines.subList(row + 1, lines.size()).clear();
        }
    }

    private void put(char c) {
        var line = lines.get(row);
        while (line.length() < column) {
            line.append(' ');
        }
        if (column < line.length()) {
            line.setCharAt(column, c);
        } else {
            line.append(c);
        }
        column++;
    }

    private void newLine() {
        row++;
        column = 0;
        while (row >= lines.size()) {
            lines.add(new StringBuilder());
        }
        if (lines.size() > 2 * MAX_LINES) {
            int drop = lines.size() - MAX_LINES;
            lines.subList(0, drop).clear();
            row = Math.max(0, row - drop);
        }
    }

    private String text() {
        int from = Math.max(0, lines.size() - MAX_LINES);
        var out = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            if (i > from) {
                out.append('\n');
            }
            out.append(lines.get(i), 0, lines.get(i).length());
        }
        return out.toString();
    }
}
