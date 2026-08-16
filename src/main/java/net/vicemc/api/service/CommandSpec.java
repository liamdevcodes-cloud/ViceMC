package net.vicemc.api.service;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Immutable description of a command bound by {@link CommandService}.
 */
public final class CommandSpec {

    public final String name;
    public final List<String> aliases;
    public final String permission;
    public final String description;
    public final String usage;
    public final Consumer<CommandContext> executor;
    public final BiFunction<CommandContext, List<String>, List<String>> tab;

    private CommandSpec(Builder b) {
        this.name = b.name;
        this.aliases = b.aliases;
        this.permission = b.permission;
        this.description = b.description;
        this.usage = b.usage;
        this.executor = b.executor;
        this.tab = b.tab;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name = "";
        private List<String> aliases = List.of();
        private String permission;
        private String description = "";
        private String usage = "";
        private Consumer<CommandContext> executor = c -> {
        };
        private BiFunction<CommandContext, List<String>, List<String>> tab = (c, s) -> List.of();

        public Builder name(String v) {
            name = v;
            return this;
        }

        public Builder aliases(String... v) {
            aliases = List.of(v);
            return this;
        }

        public Builder permission(String v) {
            permission = v;
            return this;
        }

        public Builder description(String v) {
            description = v;
            return this;
        }

        public Builder usage(String v) {
            usage = v;
            return this;
        }

        public Builder executes(Consumer<CommandContext> v) {
            executor = v;
            return this;
        }

        public Builder tabulates(BiFunction<CommandContext, List<String>, List<String>> v) {
            tab = v;
            return this;
        }

        public CommandSpec build() {
            return new CommandSpec(this);
        }
    }
}
