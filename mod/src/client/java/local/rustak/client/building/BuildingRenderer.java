package local.rustak.client.building;

import com.mojang.blaze3d.vertex.PoseStack;
import local.rustak.building.BuildingEntity;
import local.rustak.building.RoofShape;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;

/** Draws a building block's grade mesh at its yaw, lit per vertex (BuildLight). */
public class BuildingRenderer extends EntityRenderer<BuildingEntity, BuildingRenderer.State> {
	public static class State extends EntityRenderState {
		int piece, grade;
		float yaw;
		BuildLight.Lit lit;
		long shape;
	}

	public BuildingRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(BuildingEntity b, State state, float partialTick) {
		super.extractRenderState(b, state, partialTick);
		state.piece = b.piece();
		state.grade = b.grade();
		state.yaw = b.yawRad();
		state.shape = RoofShape.mask(b);
		state.lit = BuildLight.get(b);
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		BuildMesh.get(state.piece, state.grade, state.shape).submit(pose, collector, state.yaw, state.lightCoords, -1, false, state.lit);
		super.submit(state, pose, collector, camera);
	}
}
