package net.betterthanadventure.fabric.provider;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.LibClassifier;

enum LogLibrary implements LibClassifier.LibraryType {
    LOG4J_API("org/apache/logging/log4j/LogManager.class"),
    LOG4J_CORE("META-INF/services/org.apache.logging.log4j.spi.Provider", "META-INF/log4j-provider.properties"),
    LOG4J_CONFIG("log4j2.xml"),
    LOG4J_PLUGIN("net/minecrell/terminalconsole/util/LoggerNamePatternSelector.class"),
    GSON("com/google/gson/TypeAdapter.class"),
    SLF4J_API("org/slf4j/Logger.class"),
    SLF4J_CORE("META-INF/services/org.slf4j.spi.SLF4JServiceProvider");

    private final EnvType env;
    private final String[] paths;

    LogLibrary(String path) {
        this(null, new String[] { path });
    }

    LogLibrary(String... paths) {
        this(null, paths);
    }

    LogLibrary(EnvType env, String... paths) {
        this.paths = paths;
        this.env = env;
    }

    @Override
    public boolean isApplicable(EnvType env) {
        return this.env == null || this.env == env;
    }

    @Override
    public String[] getPaths() {
        return paths;
    }
}
