/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.betterthanadventure.fabric.provider;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.GameProviderHelper;
import net.fabricmc.loader.impl.game.LibClassifier;
import net.fabricmc.loader.impl.game.minecraft.Hooks;
import net.fabricmc.loader.impl.game.patch.GamePatch;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.ExceptionUtil;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.LogHandler;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Launches {@link Minecraft#main(String[])}
 */
public class NMCMinecraftGameProvider implements GameProvider {
	private Arguments arguments;
	private Collection<Path> validParentClassPath;
	private final List<Path> gameJars = new ArrayList<>();
	private final Set<Path> logJars = new HashSet<>();

	private final GameTransformer transformer = new GameTransformer(new GamePatch() {
		@Override
		public void process(FabricLauncher launcher, Function<String, ClassNode> classSource, Consumer<ClassNode> classEmitter) {
			String entrypoint = launcher.getEntrypoint();
			if (!entrypoint.startsWith("net.minecraft.")) {
				return;
			}

			ClassNode mainClass = classSource.apply(entrypoint);
			if (mainClass == null) {
				throw new RuntimeException("Could not load main class " + entrypoint + "!");
			}

			for (MethodNode method : mainClass.methods) {
				if (method.name.equals("run") && method.desc.equals("()V")) {
					Log.debug(LogCategory.GAME_PATCH, "Applying entrypoint hook to %s::%s", mainClass.name, method.name);

					ListIterator<AbstractInsnNode> instructions = method.instructions.iterator();

					// inject before startGame() call in run()
					AbstractInsnNode startGameNode = findInsn(method, node -> {
						if (node instanceof MethodInsnNode) {
							MethodInsnNode _node = (MethodInsnNode) node;
							return _node.name.equals("startGame") && _node.desc.equals("()V");
						}
						return false;
					}, false);
					moveBefore(instructions, startGameNode);
					instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
					instructions.add(new MethodInsnNode(
							Opcodes.INVOKESPECIAL,
							"net/minecraft/client/Minecraft",
							"getMinecraftDir",
							"()Ljava/io/File;",
							false
					));
					instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
					instructions.add(new MethodInsnNode(
							Opcodes.INVOKESTATIC,
							Hooks.INTERNAL_NAME,
							"startClient",
							"(Ljava/io/File;Ljava/lang/Object;)V",
							false
					));
					classEmitter.accept(mainClass);
					break;
				}
			}
		}
	});

	/**
	 * Still provides <code>minecraft</code> for backward compatibility.
	 * @return "minecraft"
	 */
	@Override
	public String getGameId() {
		return "minecraft";
	}

	@Override
	public String getGameName() {
		return "Better Than Adventure!";
	}

	@Override
	public String getRawGameVersion() {
		return Minecraft.VERSION;
	}

	@Override
	public String getNormalizedGameVersion() {
		return Minecraft.VERSION.replace("_", ".");
	}

	@Override
	public Collection<BuiltinMod> getBuiltinMods() {
		BuiltinModMetadata.Builder metadata = new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion()).setName(getGameName());

		return Collections.singletonList(new BuiltinMod(gameJars, metadata.build()));
	}

	@Override
	public String getEntrypoint() {
		return Minecraft.class.getName();
	}

	@Override
	public Path getLaunchDirectory() {
		if (arguments == null) return Paths.get(".");
		return getLaunchDirectory(arguments);
	}

	@Override
	public boolean isObfuscated() {
		return false;
	}

	@Override
	public boolean requiresUrlClassLoader() {
		return false;
	}

	@Override
	public boolean isEnabled() {
		return System.getProperty("fabric.skipBTAProvider") == null;
	}

	@Override
	public boolean locateGame(FabricLauncher launcher, String[] args) {
		EnvType envType = launcher.getEnvironmentType();
		assert (envType == EnvType.CLIENT);

		this.arguments = new Arguments();
		arguments.parse(args);
		processArgumentMap(arguments);

		Path envJar = GameProviderHelper.getEnvGameJar(envType);
		if (envJar != null) gameJars.add(envJar);

		Path commonJar = GameProviderHelper.getCommonGameJar();
		if (commonJar != null) gameJars.add(commonJar);

        try {
            LibClassifier<LogLibrary> classifier = new LibClassifier<>(LogLibrary.class, envType, this);
			if (envJar != null) classifier.process(envJar);
			if (commonJar != null) classifier.process(commonJar);
			classifier.process(launcher.getClassPath());

			boolean log4jAvailable = classifier.has(LogLibrary.LOG4J_API) && classifier.has(LogLibrary.LOG4J_CORE);
			boolean slf4jAvailable = classifier.has(LogLibrary.SLF4J_API) && classifier.has(LogLibrary.SLF4J_CORE);
			boolean hasLogLib = log4jAvailable || slf4jAvailable;

			Log.configureBuiltin(hasLogLib, !hasLogLib);

			for (LogLibrary lib: SharedConstants.LOGGING) {
				Path path = classifier.getOrigin(lib);
				if (path != null && hasLogLib) {
					logJars.add(path);
				}
			}

			validParentClassPath = classifier.getSystemLibraries();
        } catch (IOException e) {
            throw ExceptionUtil.wrap(e);
        }

		try {
			if (gameJars.isEmpty()) {
				Path jarOf = Paths.get(Minecraft.class.getProtectionDomain().getCodeSource().getLocation().toURI());
				gameJars.add(jarOf);
			}
		} catch (Exception ignored) {
		}
		return !gameJars.isEmpty();
	}

	private static void processArgumentMap(Arguments argMap) {
		if (!argMap.containsKey("accessToken")) {
			argMap.put("accessToken", "FabricMC");
		}

		if (!argMap.containsKey("version")) {
			argMap.put("version", "Fabric");
		}

		String versionType = "";

		if (argMap.containsKey("versionType") && !argMap.get("versionType").equalsIgnoreCase("release")) {
			versionType = argMap.get("versionType") + "/";
		}

		argMap.put("versionType", versionType + "Fabric");

		if (!argMap.containsKey("gameDir")) {
			argMap.put("gameDir", getLaunchDirectory(argMap).toAbsolutePath().normalize().toString());
		}
	}

	private static Path getLaunchDirectory(Arguments argMap) {
		return Paths.get(argMap.getOrDefault("gameDir", "."));
	}

	@Override
	public void initialize(FabricLauncher launcher) {
		launcher.setValidParentClassPath(validParentClassPath);

		if (!logJars.isEmpty() && !Boolean.getBoolean(SystemProperties.UNIT_TEST)) {
			for (Path jar : logJars) {
				if (gameJars.contains(jar)) {
					launcher.addToClassPath(jar, SharedConstants.ALLOWED_EARLY_CLASS_PREFIXES);
				} else {
					launcher.addToClassPath(jar);
				}
			}
		}

		setupLogHandler(launcher);
		if (!gameJars.isEmpty()) transformer.locateEntrypoints(launcher, gameJars);
	}

	private void setupLogHandler(FabricLauncher launcher) {
		System.setProperty("log4j2.formatMsgNoLookups", "true"); // lookups are not used by mc and cause issues with older log4j2 versions

		try {
			final String logHandlerClsName = "net.fabricmc.loader.impl.game.minecraft.Slf4jLogHandler";

			ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
			Class<?> logHandlerCls;

			Thread.currentThread().setContextClassLoader(launcher.getTargetClassLoader());
			logHandlerCls = launcher.loadIntoTarget(logHandlerClsName);

			Log.init((LogHandler) logHandlerCls.getConstructor().newInstance());
			Thread.currentThread().setContextClassLoader(prevCl);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Arguments getArguments() {
		return arguments;
	}

	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		if (arguments == null) return new String[0];

		String[] ret = arguments.toArray();
		if (!sanitize) return ret;

		int writeIdx = 0;

		for (int i = 0; i < ret.length; i++) {
			String arg = ret[i];

			ret[writeIdx++] = arg;
		}

		if (writeIdx < ret.length) ret = Arrays.copyOf(ret, writeIdx);

		return ret;
	}

	@Override
	public GameTransformer getEntrypointTransformer() {
		return transformer;
	}

	@Override
	public boolean hasAwtSupport() {
		// MC always sets -XstartOnFirstThread for LWJGL
		return !LoaderUtil.hasMacOs();
	}

	@Override
	public void unlockClassPath(FabricLauncher launcher) {
		for (Path gameJar : gameJars) {
			if (logJars.contains(gameJar)) {
				launcher.setAllowedPrefixes(gameJar);
			} else {
				launcher.addToClassPath(gameJar);
			}
		}
	}

	@Override
	public void launch(ClassLoader loader) {
		String targetClass = getEntrypoint();
		MethodHandle invoker;

		try {
			Class<?> c = loader.loadClass(targetClass);
			invoker = MethodHandles.lookup().findStatic(c, "main", MethodType.methodType(void.class, String[].class));
		} catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
			throw FormattedException.ofLocalized("exception.minecraft.invokeFailure", e);
		}

		try {
			invoker.invokeExact(arguments.toArray());
		} catch (Throwable t) {
			throw FormattedException.ofLocalized("exception.minecraft.generic", t);
		}
	}
}
