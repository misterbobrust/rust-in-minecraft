package local.rustak.decor;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

/** Client callbacks the common code calls (set by the client at start; no-ops on a dedicated server). */
public final class DecorHooks {
	/** An anchor cell appeared or went: the client remeshes it, so the real block there shows or hides. */
	public static Consumer<BlockPos> onAnchor = pos -> {
	};

	private DecorHooks() {
	}

	static void anchorChanged(BlockPos pos) {
		onAnchor.accept(pos);
	}
}
