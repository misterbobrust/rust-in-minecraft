package local.rustak.client.building;

import com.mojang.blaze3d.vertex.PoseStack;
import local.rustak.building.DoorDefs;
import local.rustak.building.DoorEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;

/** Draws a door: its frame and its leaves where the open or close animation has them (DoorClient.draw). */
public class DoorRenderer extends EntityRenderer<DoorEntity, DoorRenderer.State> {
	public static class State extends EntityRenderState {
		int kind;
		float yaw;
		float[] turns = new float[0];
		BuildLight.Lit[] lit;
	}

	public DoorRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(DoorEntity d, State state, float partialTick) {
		super.extractRenderState(d, state, partialTick);
		state.kind = d.kind();
		state.yaw = d.yawRad();
		state.turns = d.def() == null ? new float[0] : d.turns(partialTick);
		state.lit = d.def() == null ? null : lit(d);
	}

	/** BuildLight's lighting for the frame and each leaf (null entries until traced). */
	private static BuildLight.Lit[] lit(DoorEntity d) {
		String base = "models/doors/" + DoorDefs.DOORS[d.kind()];
		BuildLight.Lit[] out = new BuildLight.Lit[1 + d.def().hinges.size()];
		if (d.def().hasFrame) out[0] = BuildLight.door(d, 0, BuildMesh.at(base + "_frame.bin"));
		for (int i = 1; i < out.length; i++) out[i] = BuildLight.door(d, i, BuildMesh.at(base + "_leaf" + (i - 1) + ".bin"));
		return out;
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		DoorClient.draw(pose, collector, state.kind, state.yaw, state.turns, state.lightCoords, -1, false, state.lit);
		super.submit(state, pose, collector, camera);
	}
}
