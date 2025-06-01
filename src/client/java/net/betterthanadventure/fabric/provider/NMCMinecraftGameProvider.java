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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.GameProviderHelper;
import net.fabricmc.loader.impl.game.minecraft.patch.BrandingPatch;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogHandler;
import net.minecraft.client.Minecraft;

/**
 * Launches {@link Minecraft#main(String[])}
 */
public class NMCMinecraftGameProvider implements GameProvider {
	private       Arguments  arguments;
	private final List<Path> gameJars = new ArrayList<>();

	private final GameTransformer transformer = new GameTransformer(new BrandingPatch());

	/**
	 * Simply "bta" is too short for a mod ID. We'll treat it as a subtitle.
	 * @return "minecraft-bta"
	 */
	@Override
	public String getGameId() {
		return "minecraft-bta";
	}

	@Override
	public String getGameName() {
		return "Minecraft: Better Than Adventure";
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

	public Path getGameJar() {
		if (gameJars.isEmpty()) return null;
		return gameJars.get(gameJars.size() - 1);
	}

	@Override
	public String getEntrypoint() {
		return Minecraft.class.getName() + ".class";
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
		assert (EnvType.CLIENT == launcher.getEnvironmentType());

		this.arguments = new Arguments();
		arguments.parse(args);
		processArgumentMap(arguments);

		Path envJar = GameProviderHelper.getEnvGameJar(EnvType.CLIENT);
		if (envJar != null) gameJars.add(envJar);

		Path commonJar = GameProviderHelper.getCommonGameJar();
		if (commonJar != null) gameJars.add(commonJar);

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
		launcher.addToClassPath(getGameJar());
	}

	@Override
	public void launch(ClassLoader loader) {
		Minecraft.main(new String[]{arguments.getOrDefault("username", null), arguments.getOrDefault("session", "")});
	}
}
