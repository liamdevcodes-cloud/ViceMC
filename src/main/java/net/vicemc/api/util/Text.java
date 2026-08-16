package net.vicemc.api.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.EnumSet;
import java.util.Set;

/**
 * Legacy '&amp;'-style chat color parser producing Adventure components.
 * Supports &amp;0-9a-f, &amp;k-lmo-nr, &amp;r and &amp;#RRGGBB hex colors.
 */
public final class Text {

    private Text() {
    }

    public static Component color(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        Component root = Component.empty();
        StringBuilder buffer = new StringBuilder();
        TextColor color = null;
        boolean bold = false, italic = false, underline = false, strike = false, obfuscated = false;

        int len = input.length();
        int i = 0;
        while (i < len) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < len) {
                char code = input.charAt(i + 1);
                if (code == '#') {
                    String hex = input.substring(i + 2, Math.min(i + 8, len));
                    if (hex.length() == 6 && hex.matches("[0-9a-fA-F]{6}")) {
                        root = flush(root, buffer, color, bold, italic, underline, strike, obfuscated);
                        color = TextColor.color(Integer.parseInt(hex, 16));
                        i += 8;
                        continue;
                    }
                    i += 2;
                    continue;
                }
                root = flush(root, buffer, color, bold, italic, underline, strike, obfuscated);
                switch (code) {
                    case '0' -> color = NamedTextColor.BLACK;
                    case '1' -> color = NamedTextColor.DARK_BLUE;
                    case '2' -> color = NamedTextColor.DARK_GREEN;
                    case '3' -> color = NamedTextColor.DARK_AQUA;
                    case '4' -> color = NamedTextColor.DARK_RED;
                    case '5' -> color = NamedTextColor.DARK_PURPLE;
                    case '6' -> color = NamedTextColor.GOLD;
                    case '7' -> color = NamedTextColor.GRAY;
                    case '8' -> color = NamedTextColor.DARK_GRAY;
                    case '9' -> color = NamedTextColor.BLUE;
                    case 'a' -> color = NamedTextColor.GREEN;
                    case 'b' -> color = NamedTextColor.AQUA;
                    case 'c' -> color = NamedTextColor.RED;
                    case 'd' -> color = NamedTextColor.LIGHT_PURPLE;
                    case 'e' -> color = NamedTextColor.YELLOW;
                    case 'f' -> color = NamedTextColor.WHITE;
                    case 'k' -> obfuscated = true;
                    case 'l' -> bold = true;
                    case 'm' -> strike = true;
                    case 'n' -> underline = true;
                    case 'o' -> italic = true;
                    case 'r' -> {
                        color = null;
                        bold = italic = underline = strike = obfuscated = false;
                    }
                    default -> buffer.append('&').append(code);
                }
                i += 2;
            } else {
                buffer.append(c);
                i++;
            }
        }
        root = flush(root, buffer, color, bold, italic, underline, strike, obfuscated);
        return root;
    }

    private static Component flush(Component root, StringBuilder buffer, TextColor color,
                                   boolean bold, boolean italic, boolean underline,
                                   boolean strike, boolean obfuscated) {
        if (buffer.length() == 0) {
            return root;
        }
        Component part = Component.text(buffer.toString());
        if (color != null) {
            part = part.color(color);
        }
        Set<TextDecoration> decos = EnumSet.noneOf(TextDecoration.class);
        if (bold) {
            decos.add(TextDecoration.BOLD);
        }
        if (italic) {
            decos.add(TextDecoration.ITALIC);
        }
        if (underline) {
            decos.add(TextDecoration.UNDERLINED);
        }
        if (strike) {
            decos.add(TextDecoration.STRIKETHROUGH);
        }
        if (obfuscated) {
            decos.add(TextDecoration.OBFUSCATED);
        }
        if (!decos.isEmpty()) {
            part = part.decorations(decos, true);
        }
        buffer.setLength(0);
        return root.append(part);
    }

    public static Component money(double value) {
        return color("&a$" + String.format("%,.2f", value));
    }

    public static String moneyPlain(double value) {
        return "$" + String.format("%,.2f", value);
    }
}
