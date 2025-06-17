package net.betterthanadventure.fabric.provider;

final class SharedConstants {
    static final String[] ALLOWED_EARLY_CLASS_PREFIXES = { "org.apache.logging.log4j.", "com.mojang.util." };
    static final LogLibrary[] LOGGING = { LogLibrary.LOG4J_API, LogLibrary.LOG4J_CORE, LogLibrary.LOG4J_CONFIG, LogLibrary.LOG4J_PLUGIN, LogLibrary.GSON, LogLibrary.SLF4J_API, LogLibrary.SLF4J_CORE };
}
