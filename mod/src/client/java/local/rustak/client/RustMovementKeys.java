package local.rustak.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Files;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

/** Migrate the old standard pair once, after saved options have loaded; later user bindings stay editable. */
final class RustMovementKeys {
	private static boolean checked;

	static void apply(Options options) {
		if (checked || options == null) return;
		checked = true;
		var marker = FabricLoader.getInstance().getConfigDir().resolve("rustak-movement-keys-v1.txt");
		try {
			if (Files.exists(marker)) return;
			if (options.keyShift.saveString().equals("key.keyboard.left.shift")
					&& options.keySprint.saveString().equals("key.keyboard.left.control")) {
				options.keyShift.setKey(InputConstants.getKey("key.keyboard.left.control"));
				options.keySprint.setKey(InputConstants.getKey("key.keyboard.left.shift"));
				KeyMapping.resetMapping();
				options.save();
			}
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, "1\n");
		} catch (IOException e) {
			org.slf4j.LoggerFactory.getLogger("rustak").error("rustak: movement key migration", e);
		}
	}
}
